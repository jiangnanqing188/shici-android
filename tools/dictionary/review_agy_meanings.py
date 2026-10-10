"""Validate the pinned AGY handoff and reuse one non-GPT CLI conversation."""
import argparse
from collections import Counter
from datetime import datetime
import fcntl
import json
import os
from pathlib import Path
import subprocess
import tempfile

import audit_exam_sources as audit
import review_exam_meanings as exam_review
import review_kajweb_meanings as review

MODEL = 'gemini-3.8-flash-high'
PILOT = 'agy-pilot-001'


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save_json(path, value):
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.' + path.name + '-', delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(review.json_bytes(value))
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def load_queue(root):
    inventory = read_json(root / 'inventory.json')
    source = root.parent
    workspace = Path(inventory['workspace_root']).resolve()
    if not source.resolve().is_relative_to(workspace):
        raise ValueError('handoff lies outside original AGY workspace')
    if (Path(inventory['source_review_root']).resolve() != source.resolve()
            or audit.sha256(source / 'inventory.json') != inventory['source_inventory_sha256']):
        raise ValueError('source inventory identity/hash changed')
    if (inventory['executor'] != 'agy' or inventory['model'] != MODEL
            or inventory['effort'] != 'high' or inventory['candidate_revisions_applied']):
        raise ValueError('handoff executor/model/acceptance differs from agreed scope')
    original_inventory = read_json(source / 'inventory.json')
    originals, _ = exam_review.assignments(source, original_inventory)
    legacy_hashes = {str(path.relative_to(source)): audit.sha256(path)
                     for path in sorted((source / 'results').glob('*.jsonl'))}
    if legacy_hashes != inventory['legacy_result_sha256']:
        raise ValueError('legacy result hashes changed since AGY handoff')
    old = exam_review.summarize(source)
    supported = set()
    for slot in original_inventory['slots']:
        for batch in slot['batches']:
            path = source / 'results' / (batch['id'] + '.jsonl')
            if path.exists():
                supported.update(json.loads(line)['headword'] for line in path.read_text(encoding='utf-8').splitlines()
                                 if line.strip() and json.loads(line)['status'] == 'supported')
    if (old['review_record_count'] != inventory['prior_luna_result_count']
            or old['status_counts'] != inventory['prior_luna_status_counts']):
        raise ValueError('legacy results changed since AGY handoff; do not replan in-flight inputs')
    expected = set(originals) - supported
    batches, seen, kinds = {}, set(), Counter()
    for spec in inventory['batches']:
        batch_id = spec['id']
        if (not isinstance(batch_id, str) or '/' in batch_id or '\\' in batch_id
                or batch_id in batches):
            raise ValueError('invalid/duplicate handoff batch id')
        path = root / 'batches' / (batch_id + '.json')
        if audit.sha256(path) != spec['sha256']:
            raise ValueError('handoff batch hash changed')
        data = read_json(path)
        if data['handoff_batch_id'] != batch_id or len(data['entries']) != spec['entry_count']:
            raise ValueError('handoff batch identity/count changed')
        for entry in data['entries']:
            head = entry['headword']
            if head not in expected or head in seen:
                raise ValueError('duplicate/excluded/unknown handoff word')
            original_batch, original = originals[head]
            if entry['source_batch_id'] != original_batch or any(
                    entry[key] != original[key] for key in original):
                raise ValueError('handoff differs from fixed original senses/tags')
            seen.add(head)
            kinds[entry['task_kind']] += 1
        batches[batch_id] = data
    excluded = original_inventory['excluded_complete_headwords'] + len(supported)
    if (seen != expected or len(seen) != inventory['remaining_headwords']
            or excluded != inventory['excluded_all_senses_supported']
            or len(seen) + excluded != inventory['source_headwords']
            or dict(kinds) != inventory['task_kind_counts'] or PILOT not in batches):
        raise ValueError('handoff coverage/exclusion counts changed')
    return inventory, batches


def batch_records(root, batch):
    batch_id = batch['handoff_batch_id']
    originals = {entry['headword']: entry for entry in batch['entries']}
    records = {}
    path = root / 'results' / (batch_id + '.jsonl')
    if not path.exists():
        return records
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        head = record['headword']
        if head not in originals or head in records:
            raise ValueError('unknown/duplicate AGY result')
        entry = originals[head]
        if (record['handoff_batch_id'] != batch_id or record['batch_id'] != entry['source_batch_id']
                or record['executor'] != 'agy' or record['model'] != MODEL):
            raise ValueError('AGY result identity/model changed')
        review.validate_review(record, {'headword': head, 'senses': entry['pending_senses']})
        if not record['lookup_attempted']:
            raise ValueError('unattempted skeleton is not a review')
        records[head] = record
    return records


def progress(root, inventory, batches):
    records, completed = {}, []
    for batch_id, batch in batches.items():
        current = batch_records(root, batch)
        records.update(current)
        if len(current) == len(batch['entries']):
            completed.append(batch_id)
    counts = Counter(record['status'] for record in records.values())
    supported = inventory['excluded_all_senses_supported'] + counts['supported']
    return {'executor': 'agy', 'model': MODEL, 'source_headwords': inventory['source_headwords'],
            'agy_attempted': len(records), 'agy_status_counts': dict(counts),
            'agy_external_evidence': sum(bool(r['references']) for r in records.values()),
            'fully_supported_headwords': supported,
            'remaining_not_fully_supported': inventory['source_headwords'] - supported,
            'handoff_without_record': inventory['remaining_headwords'] - len(records),
            'completed_first_pass_batches': completed,
            'patches_applied': False, 'status': 'ai_review_not_professional_certification'}


def approve_pilot(root, inventory, batches):
    records = batch_records(root, batches[PILOT])
    if len(records) != len(batches[PILOT]['entries']):
        raise ValueError('pilot has missing actual review records')
    if not any(record['references'] for record in records.values()):
        raise ValueError('pilot has no external evidence to check')
    save_json(root / 'pilot-accepted.json', {
        'accepted_at': datetime.now().astimezone().isoformat(), 'evidence_checked_by_coordinator': True,
        'source_inventory_sha256': inventory['source_inventory_sha256'],
        'batch_sha256': audit.sha256(root / 'batches' / (PILOT + '.json')),
        'result_sha256': audit.sha256(root / 'results' / (PILOT + '.jsonl')),
        'approval_scope': 'execution_and_evidence_sample_not_all_senses_or_candidate_acceptance'})


def pilot_accepted(root, inventory):
    path = root / 'pilot-accepted.json'
    if not path.exists():
        return False
    acceptance = read_json(path)
    if (acceptance['source_inventory_sha256'] != inventory['source_inventory_sha256']
            or acceptance['batch_sha256'] != audit.sha256(root / 'batches' / (PILOT + '.json'))
            or acceptance['result_sha256'] != audit.sha256(root / 'results' / (PILOT + '.jsonl'))
            or acceptance['evidence_checked_by_coordinator'] is not True):
        raise ValueError('pilot acceptance no longer matches immutable input/result')
    return True


def run(root, inventory, batches, max_batches, resume_blocked=False, cli='/mnt/agy/bin/agy'):
    with (root / 'dispatch.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ValueError('another AGY dispatcher is active') from error
        state = read_json(root / 'run-state.json')
        if state['state'].startswith(('blocked_', 'failed_', 'pilot_cli_running', 'batch_running')) and not resume_blocked:
            raise ValueError('previous run blocked/interrupted; explicit resume needs a resolved cause or user-authorized bounded retry')
        if state['model'] != MODEL or state.get('gpt_review_enabled') is not False:
            raise ValueError('GPT disabled and fixed AGY model required')
        if not state.get('conversation_id'):
            raise ValueError('existing AGY conversation id required; do not create replacement')
        (root / 'logs').mkdir(exist_ok=True)
        inventory_hash = audit.sha256(root / 'inventory.json')
        for _ in range(max_batches):
            accepted = pilot_accepted(root, inventory)
            target = next((batch for batch_id, batch in batches.items()
                           if len(batch_records(root, batch)) < len(batch['entries'])), None)
            if target is None:
                state.update(state='first_pass_recorded_not_semantically_complete', current_batch=None)
                save_json(root / 'run-state.json', state)
                break
            batch_id = target['handoff_batch_id']
            if batch_id != PILOT and not accepted:
                raise ValueError('pilot awaits coordinator evidence check; remaining queue not dispatched')
            attempt = root / 'logs' / (batch_id + '-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
            prompt = (f'继续本会话词义核验，仅执行一个批次 {batch_id}。先读 {root / "instructions.md"} '
                      f'和 {root / "batches" / (batch_id + ".json")}。'
                      f'只写 {root / "results" / (batch_id + ".jsonl")} 与对应-summary.md。'
                      '保留已有有效行，逐词真实查询，只补pending_senses，不重复covered_senses；'
                      '不改输入、历史结果、App、工具或Git，不调用其他模型或子代理，不购买额度/reset/绕过权限。'
                      '遇权限、账号、额度或429保存断点并停止，不生成未尝试骨架。'
                      '完成返回实际尝试与supported/needs_revision/unresolved统计；候选不应用。'
                      + ('5词试跑已通过主线程执行与证据抽检，本次仅授权当前批次，不要自行接下一批。' if accepted else '首次仅执行5词试跑，等待主线程验收。'))
            state.update(state='batch_running', current_batch=batch_id, executor='agy', dispatcher_pid=os.getpid())
            save_json(root / 'run-state.json', state)
            try:
                with attempt.with_suffix('.response.json').open('xb') as out, attempt.with_suffix('.stderr.log').open('xb') as err:
                    result = subprocess.run([cli, '--conversation', state['conversation_id'], '--model', MODEL,
                        '--effort', 'high', '--mode', 'accept-edits', '--sandbox', '--output-format', 'json',
                        '--log-file', str(attempt.with_suffix('.cli.log')), '--print', prompt], cwd=inventory['workspace_root'], stdout=out, stderr=err)
                response = read_json(attempt.with_suffix('.response.json'))
                if audit.sha256(root / 'inventory.json') != inventory_hash:
                    raise ValueError('handoff inventory changed during execution')
                load_queue(root)  # Recheck immutable source/input hashes after the external worker writes.
                state.update(last_response=str(attempt.with_suffix('.response.json')), dispatcher_pid=None,
                             exec_session_id=None)
                if result.returncode or response.get('status') != 'SUCCESS' or response.get('denied_actions'):
                    # Do not persist raw account URLs/error strings in state or documentation.
                    state.update(state='blocked_cli_error_or_permission', blocking_reason='inspect private CLI response/stderr')
                    save_json(root / 'run-state.json', state)
                    break
                if response.get('conversation_id', state['conversation_id']) != state['conversation_id']:
                    raise ValueError('CLI returned a different conversation; do not switch silently')
                current = batch_records(root, target)
                save_json(root / 'progress.json', progress(root, inventory, batches))
                if len(current) != len(target['entries']):
                    state.update(state='blocked_incomplete_batch', blocking_reason='SUCCESS is not proof of completed queries')
                    save_json(root / 'run-state.json', state)
                    break
            except (ValueError, KeyError, TypeError, OSError):
                state.update(state='failed_validation_or_transport', dispatcher_pid=None, exec_session_id=None)
                save_json(root / 'run-state.json', state)
                raise
            state.update(state='ready' if accepted else 'pilot_awaiting_evidence_check')
            save_json(root / 'run-state.json', state)
            if not accepted:
                break
        return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--handoff-dir', type=Path, required=True)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('summarize')
    approve = commands.add_parser('approve-pilot')
    approve.add_argument('--evidence-checked', action='store_true', required=True,
                         help='Only after coordinator has actually checked the pilot dictionary evidence')
    launch = commands.add_parser('run')
    launch.add_argument('--max-batches', type=int, default=1)
    launch.add_argument('--resume-blocked', action='store_true', help='Only after resolving the cause or receiving explicit user authorization for a bounded retry')
    args = parser.parse_args()
    root = args.handoff_dir.resolve()
    try:
        inventory, batches = load_queue(root)
        if args.command == 'approve-pilot':
            approve_pilot(root, inventory, batches)
        elif args.command == 'run':
            if args.max_batches < 1:
                raise ValueError('max-batches must be positive')
            run(root, inventory, batches, args.max_batches, args.resume_blocked)
        result = progress(root, inventory, batches)
        save_json(root / 'progress.json', result)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if args.command == 'run' and read_json(root / 'run-state.json')['state'].startswith('blocked_'):
            parser.exit(1, 'AGY paused; inspect run-state.json before resuming\n')
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(2, f'AGY review rejected: {error}\n')


if __name__ == '__main__':
    main()
