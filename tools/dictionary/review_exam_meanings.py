"""Validate incremental five-book batches without treating missing evidence as completion."""
import argparse
from collections import Counter
import json
from pathlib import Path

import audit_exam_sources as audit
import plan_exam_meaning_review as planner
import review_kajweb_meanings as review


def assignments(root, inventory):
    originals, batches = {}, []
    pending_senses = 0
    for slot in inventory['slots']:
        slot_words, slot_senses = 0, 0
        for batch in slot['batches']:
            batch_id = batch['id']
            if batch_id in {b['id'] for b in batches}:
                raise ValueError('duplicate batch assignment')
            path = root / 'batches' / f'{batch_id}.json'
            if audit.sha256(path) != batch['sha256']:
                raise ValueError('assignment batch hash changed')
            data = json.loads(path.read_text(encoding='utf-8'))
            if (data['batch_id'] != batch_id or data['slot_id'] != slot['id']
                    or data['exam_group'] != slot['exam_group']):
                raise ValueError('batch identity differs from inventory')
            if len(data['entries']) != batch['entry_count']:
                raise ValueError('batch entry count differs from inventory')
            for entry in data['entries']:
                head = entry['headword']
                if head in originals or slot['exam_group'] not in entry['exams']:
                    raise ValueError('duplicate/ineligible assigned headword')
                todo = {planner.sense_key(s) for s in entry['pending_senses']}
                covered = {planner.sense_key(s) for s in entry['covered_senses']}
                if (not todo or len(todo) != len(entry['pending_senses']) or todo & covered
                        or todo | covered != {planner.sense_key(s) for s in entry['senses']}
                        or entry['weight'] != len(todo)):
                    raise ValueError('pending/covered senses are not a disjoint source partition')
                originals[head] = (batch_id, entry)
                slot_senses += len(todo)
            slot_words += len(data['entries'])
            batches.append({'id': batch_id, 'headwords': [r['headword'] for r in data['entries']]})
        if slot_words != slot['entry_count'] or slot_senses != slot['sense_count']:
            raise ValueError('slot counts differ from assignments')
        pending_senses += slot_senses
    excluded = {r['headword'] for r in inventory['excluded_words']}
    if (len(originals) != inventory['pending_headwords']
            or pending_senses != inventory['pending_sense_count']
            or len(excluded) != inventory['excluded_complete_headwords']
            or len(excluded) != len(inventory['excluded_words'])
            or excluded & originals.keys()
            or len(originals) + len(excluded) != inventory['source_headwords']):
        raise ValueError('inventory coverage/counts differ from assignments')
    return originals, batches


def summarize(root, require_complete=False, batch_id=None):
    inventory = json.loads((root / 'inventory.json').read_text(encoding='utf-8'))
    originals, batches = assignments(root, inventory)
    if batch_id is not None:
        batches = [b for b in batches if b['id'] == batch_id]
        if not batches:
            raise ValueError('unknown batch id')
        originals = {h: value for h, value in originals.items() if value[0] == batch_id}
    counts, records, seen, progress = Counter(), [], set(), []
    for batch in batches:
        path = root / 'results' / f"{batch['id']}.jsonl"
        current = []
        if path.exists():
            for line_number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
                if not line.strip():
                    continue
                record = json.loads(line)
                head = record['headword']
                if head not in originals or head in seen:
                    raise ValueError(f"unknown/duplicate review: {batch['id']} row {line_number}")
                expected_batch_id, entry = originals[head]
                if expected_batch_id != batch['id'] or record['batch_id'] != expected_batch_id:
                    raise ValueError('review appears in the wrong batch')
                review.validate_review(record, {'headword': head, 'senses': entry['pending_senses']})
                counts[record['status']] += 1
                records.append(record)
                current.append(record)
                seen.add(head)
        attempted = sum(r['lookup_attempted'] for r in current)
        progress.append({'batch_id': batch['id'], 'assigned_count': len(batch['headwords']),
                        'record_count': len(current), 'lookup_attempted_count': attempted,
                        'first_pass_attempted': attempted == len(batch['headwords']),
                        'fully_supported': sum(r['status'] == 'supported' for r in current) == len(batch['headwords'])})
    missing = sorted(set(originals) - seen)
    if require_complete and missing:
        raise ValueError(f'{len(missing)} assigned words have no review record')
    # A complete file is not equivalent to an attempted lookup or semantic acceptance.
    return {'schema_version': 1, 'scope': 'all_batches' if batch_id is None else batch_id,
            'source_headwords': inventory['source_headwords'],
            'assigned_count': len(originals), 'excluded_complete_headwords': inventory['excluded_complete_headwords'],
            'reused_supported_sense_count': inventory['reused_supported_sense_count'],
            'review_record_count': len(records), 'lookup_attempted_count': sum(r['lookup_attempted'] for r in records),
            'not_attempted_count': sum(not r['lookup_attempted'] for r in records),
            'external_evidence_count': sum(bool(r['references']) for r in records),
            'pending_count': len(missing), 'pending_headwords': missing, 'status_counts': dict(counts),
            'batch_progress': progress,
            'unresolved_headwords': sorted(r['headword'] for r in records if r['status'] == 'unresolved'),
            'proposed_patches': sorted((r for r in records if r['status'] == 'needs_revision'), key=lambda r: r['headword']),
            'patches_applied': False, 'status': 'ai_review_not_professional_certification'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--review-dir', type=Path, required=True)
    parser.add_argument('--batch-id', help='Validate just this result batch; other workers may still be writing')
    parser.add_argument('--require-complete', action='store_true', help='Require one row per word, not semantic acceptance')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = summarize(args.review_dir, args.require_complete, args.batch_id)
        with args.output.open('xb') as stream:
            stream.write(review.json_bytes(result))
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(2, f'review rejected: {error}\n')
    print(json.dumps({k: result[k] for k in ('scope', 'assigned_count', 'excluded_complete_headwords',
          'review_record_count', 'lookup_attempted_count', 'external_evidence_count',
          'not_attempted_count', 'pending_count', 'status_counts')}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
