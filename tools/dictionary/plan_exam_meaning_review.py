"""Deduplicate all pinned exam senses, reuse valid reviews, and balance bounded agent tasks."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import tempfile

import audit_exam_sources as audit
import build_kajweb_app as builder
import merge_exam_dictionary as merger
import review_kajweb_meanings as review

EXAMS = builder.PRIORITY


def sense_key(sense):
    return (sense['pos'] or '', sense['meaning'])


def previous_records(root):
    """Do not overwrite damaged old results or count ambiguous replay records as completed."""
    inventory = json.loads((root / 'inventory.json').read_text(encoding='utf-8'))
    records, warnings = {}, []
    for shard in inventory['shards']:
        path = root / 'shards' / shard['file']
        if audit.sha256(path) != shard['sha256']:
            raise ValueError('previous assignment hash changed')
        originals = {r['headword']: r for r in json.loads(path.read_text())['entries']}
        seen = defaultdict(list)
        output = root / 'results' / f"shard-{shard['id']}.jsonl"
        if not output.exists():
            continue
        for line_number, line in enumerate(output.read_text(encoding='utf-8').splitlines(), 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
                head = record['headword']
                if head not in originals:
                    raise ValueError('unknown headword')
                review.validate_review(record, originals[head])
            except (ValueError, KeyError, TypeError) as error:
                warnings.append({'file': str(output), 'line': line_number, 'error': str(error)})
                continue
            seen[head].append(record)
        for head, versions in seen.items():
            unique = {merger.json_text(r): r for r in versions}
            if len(unique) > 1:
                warnings.append({'file': str(output), 'headword': head,
                                 'error': 'conflicting duplicate reviews; not reusable'})
                continue
            if head in records:
                raise ValueError('previous headword assigned across multiple shards')
            records[head] = next(iter(unique.values()))
    return records, warnings


def combine(sources, source_dir):
    words = {}
    for source in sources:
        for row in merger.source_entries(source, source_dir):
            word = words.setdefault(row['headword'], {'headword': row['headword'], 'exams': [], 'senses': [],
                                                      'source_variants': []})
            if source['exam'] not in word['exams']:
                word['exams'].append(source['exam'])
            known = {sense_key(s) for s in word['senses']}
            for sense in row['senses']:
                if sense_key(sense) not in known:
                    word['senses'].append(sense)
                    known.add(sense_key(sense))
            word['source_variants'].append({'exam': source['exam'], 'source_file': source['file'],
                                           'source_sha256': source['sha256'], 'senses': row['senses']})
    return words


def frontier(words, previous):
    pending, excluded, reused = [], [], 0
    for head, word in sorted(words.items()):
        old = previous.get(head)
        supported = {sense_key(s) for s in old['original_senses']} if old and old['status'] == 'supported' else set()
        covered = [s for s in word['senses'] if sense_key(s) in supported]
        unresolved = [s for s in word['senses'] if sense_key(s) not in supported]
        reused += len(covered)
        if not unresolved:
            excluded.append({'headword': head, 'exams': word['exams'], 'reason': 'all identical senses supported'})
            continue
        pending.append({**word, 'covered_senses': covered, 'pending_senses': unresolved,
                        'previous_review': old, 'weight': len(unresolved)})
    return pending, excluded, reused


def balance(entries, split_threshold=2000):
    """Place exclusive words first, then distribute shared words once to less-loaded eligible groups."""
    if split_threshold <= 0:
        raise ValueError('split threshold must be positive')
    slot_counts = {exam: 1 for exam in EXAMS}
    for _ in range(len(EXAMS) + 1):
        groups = {exam: [] for exam in EXAMS}
        for entry in sorted(entries, key=lambda r: (len(r['exams']), -r['weight'], r['headword'])):
            # Compare words per agent first; then pending senses per agent to break load ties.
            exam = min(entry['exams'], key=lambda e: (len(groups[e]) / slot_counts[e],
                       sum(r['weight'] for r in groups[e]) / slot_counts[e], EXAMS.index(e)))
            groups[exam].append(entry)
        new_counts = {e: 2 if len(groups[e]) >= split_threshold else 1 for e in EXAMS}
        if new_counts == slot_counts:
            break
        # Never undo a split within planning; prevents overlap-driven oscillation.
        slot_counts = {e: max(slot_counts[e], new_counts[e]) for e in EXAMS}
    slots = []
    for exam in EXAMS:
        buckets = [[] for _ in range(slot_counts[exam])]
        for entry in sorted(groups[exam], key=lambda r: (-r['weight'], r['headword'])):
            bucket = min(buckets, key=lambda b: (len(b), sum(r['weight'] for r in b)))
            bucket.append(entry)
        for index, bucket in enumerate(buckets, 1):
            if bucket:
                slots.append({'id': f'{exam}-{index}', 'exam_group': exam,
                              'entry_count': len(bucket), 'sense_count': sum(r['weight'] for r in bucket),
                              'entries': sorted(bucket, key=lambda r: r['headword'])})
    flat = [r['headword'] for slot in slots for r in slot['entries']]
    if len(flat) != len(set(flat)) or set(flat) != {r['headword'] for r in entries}:
        raise ValueError('assignment is not disjoint and complete')
    return slots


def plan(manifest_path, source_dir, previous_dir, output_dir, batch_size=25, split_threshold=2000):
    if output_dir.exists() or batch_size <= 0:
        raise ValueError('output must be new and batch size positive')
    inventory = builder.select_inventory(manifest_path)
    previous, warnings = previous_records(previous_dir)
    words = combine(inventory['sources'], source_dir)
    pending, excluded, reused = frontier(words, previous)
    slots = balance(pending, split_threshold)
    membership = {e: {h for h, w in words.items() if e in w['exams']} for e in EXAMS}
    pending_membership = {e: {r['headword'] for r in pending if e in r['exams']} for e in EXAMS}
    summary = {'schema_version': 1, 'model': 'gpt-6-luna', 'reasoning_effort': 'medium',
               'source_inventory': inventory, 'source_headwords': len(words),
               'pending_headwords': len(pending), 'excluded_complete_headwords': len(excluded),
               'reused_supported_sense_count': reused, 'pending_sense_count': sum(r['weight'] for r in pending),
               'source_domain_counts': {e: len(membership[e]) for e in EXAMS},
               'pending_domain_counts_before_overlap_assignment': {e: len(pending_membership[e]) for e in EXAMS},
               'cet4_cet6_overlap': len(membership['cet4'] & membership['cet6']),
               'pending_cet4_cet6_overlap': len(pending_membership['cet4'] & pending_membership['cet6']),
               'split_threshold': split_threshold, 'batch_size': batch_size,
               'previous_review_warnings': warnings, 'excluded_words': excluded,
               'candidate_revisions_applied': False, 'slots': []}
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.exam-review-plan-', dir=output_dir.parent) as temporary:
        stage = Path(temporary)
        (stage / 'slots').mkdir()
        (stage / 'batches').mkdir()
        (stage / 'results').mkdir()
        for slot in slots:
            payload = review.json_bytes({'schema_version': 1, **slot})
            (stage / 'slots' / f"{slot['id']}.json").write_bytes(payload)
            batches = []
            for index, start in enumerate(range(0, len(slot['entries']), batch_size), 1):
                batch_id = f"{slot['id']}-{index:03d}"
                entries = slot['entries'][start:start + batch_size]
                data = {'schema_version': 1, 'batch_id': batch_id, 'slot_id': slot['id'],
                        'exam_group': slot['exam_group'], 'entries': entries}
                encoded = review.json_bytes(data)
                (stage / 'batches' / f'{batch_id}.json').write_bytes(encoded)
                batches.append({'id': batch_id, 'entry_count': len(entries),
                                'sha256': hashlib.sha256(encoded).hexdigest()})
            summary['slots'].append({k: slot[k] for k in ('id', 'exam_group', 'entry_count', 'sense_count')}
                                    | {'batches': batches})
        (stage / 'inventory.json').write_bytes(review.json_bytes(summary))
        stage.rename(output_dir)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--previous-review-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--batch-size', type=int, default=25)
    parser.add_argument('--split-threshold', type=int, default=2000)
    args = parser.parse_args()
    try:
        result = plan(args.manifest, args.source_dir, args.previous_review_dir, args.output_dir,
                      args.batch_size, args.split_threshold)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(2, f'planning rejected: {error}\n')
    print(json.dumps({k: result[k] for k in ('source_headwords','pending_headwords','excluded_complete_headwords',
          'reused_supported_sense_count','pending_sense_count','cet4_cet6_overlap','pending_cet4_cet6_overlap')}
          | {'slots': [{k: s[k] for k in ('id','entry_count','sense_count')} for s in result['slots']],
             'previous_warnings': len(result['previous_review_warnings'])}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
