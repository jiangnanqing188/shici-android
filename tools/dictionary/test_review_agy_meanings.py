import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock

import review_agy_meanings as agy
import test_plan_exam_meaning_review as fixtures


class AgyMeaningReviewTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.PlanExamMeaningReviewTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.write_record('résumé')
        self.legacy = self.fixture.output
        old = self.fixture.build()
        self.root = self.legacy / 'agy-handoff'
        (self.root / 'batches').mkdir(parents=True)
        (self.root / 'results').mkdir()
        (self.root / 'instructions.md').write_text('synthetic fixture only')
        entries = []
        for slot in old['slots']:
            for spec in slot['batches']:
                for entry in agy.read_json(self.legacy / 'batches' / (spec['id'] + '.json'))['entries']:
                    entries.append(dict(entry, source_batch_id=spec['id'], task_kind='unattempted'))
        self.inventory = {'executor': 'agy', 'model': agy.MODEL, 'effort': 'high',
            'candidate_revisions_applied': False, 'workspace_root': str(self.fixture.root.resolve()), 'source_review_root': str(self.legacy.resolve()),
            'source_inventory_sha256': agy.audit.sha256(self.legacy / 'inventory.json'),
            'source_headwords': old['source_headwords'],
            'prior_luna_result_count': 0, 'prior_luna_status_counts': {}, 'legacy_result_sha256': {},
            'remaining_headwords': len(entries), 'excluded_all_senses_supported': old['excluded_complete_headwords'],
            'task_kind_counts': {'unattempted': len(entries)}, 'batches': []}
        for start in range(0, len(entries), 2):
            name = agy.PILOT if start == 0 else f'agy-{start:04d}'
            path = self.root / 'batches' / (name + '.json')
            agy.save_json(path, {'handoff_batch_id': name, 'entries': entries[start:start+2]})
            self.inventory['batches'].append({'id': name, 'entry_count': len(entries[start:start+2]),
                                            'sha256': agy.audit.sha256(path)})
        self.write_inventory()
        agy.save_json(self.root / 'run-state.json', {'state': 'ready', 'model': agy.MODEL,
            'gpt_review_enabled': False, 'conversation_id': 'existing-conversation'})

    def write_inventory(self):
        agy.save_json(self.root / 'inventory.json', self.inventory)

    def queue(self):
        return agy.load_queue(self.root)

    def row(self, batch, entry, status='supported'):
        record = self.fixture.record({'headword': entry['headword'], 'senses': entry['pending_senses']}, status)
        record.update(handoff_batch_id=batch['handoff_batch_id'], batch_id=entry['source_batch_id'],
                      executor='agy', model=agy.MODEL)
        if status == 'needs_revision':
            record.update(proposed_senses=[{'pos': 'n', 'meaning': 'fixture candidate'}], issues=['fixture issue'])
        return record

    def write_batch(self, batch, status='supported'):
        path = self.root / 'results' / (batch['handoff_batch_id'] + '.jsonl')
        path.write_text('\n'.join(json.dumps(self.row(batch, entry, status)) for entry in batch['entries'])+'\n')

    def test_queue_progress_is_not_fake_completion(self):
        inventory, batches = self.queue()
        progress = agy.progress(self.root, inventory, batches)
        self.assertEqual(0, progress['agy_attempted'])
        self.assertEqual(1, progress['fully_supported_headwords'])
        self.assertEqual(5, progress['remaining_not_fully_supported'])

    def test_source_and_handoff_hash_changes_rejected(self):
        for path in (self.legacy / 'inventory.json', self.root / 'batches' / (agy.PILOT + '.json')):
            original = path.read_bytes()
            path.write_bytes(original + b' ')
            with self.assertRaisesRegex(ValueError, 'hash changed'):
                self.queue()
            path.write_bytes(original)

    def test_original_tags_cannot_change_even_with_rehashed_batch(self):
        spec = self.inventory['batches'][0]
        path = self.root / 'batches' / (spec['id']+'.json')
        data = agy.read_json(path)
        data['entries'][0]['exams'] = ['different-exam']
        agy.save_json(path, data)
        spec['sha256'] = agy.audit.sha256(path)
        self.write_inventory()
        with self.assertRaisesRegex(ValueError, 'fixed original'):
            self.queue()

    def test_legacy_results_cannot_change_after_handoff(self):
        (self.legacy / 'results' / 'extra.jsonl').write_text('{}\n')
        with self.assertRaisesRegex(ValueError, 'legacy result hashes'):
            self.queue()

    def test_duplicate_and_excluded_heads_rejected(self):
        spec = self.inventory['batches'][1]
        path = self.root / 'batches' / (spec['id'] + '.json')
        data = agy.read_json(path)
        pilot = agy.read_json(self.root / 'batches' / (agy.PILOT + '.json'))
        data['entries'][0] = pilot['entries'][0]
        agy.save_json(path, data)
        spec['sha256'] = agy.audit.sha256(path)
        self.write_inventory()
        with self.assertRaisesRegex(ValueError, 'duplicate/excluded/unknown'):
            self.queue()

    def test_result_identity_original_model_and_actual_lookup_enforced(self):
        _, batches = self.queue()
        batch = batches[agy.PILOT]
        original = self.row(batch, batch['entries'][0])
        path = self.root / 'results' / (agy.PILOT + '.jsonl')
        for patch in ({'batch_id': 'wrong'}, {'handoff_batch_id': 'wrong'}, {'model': 'gpt-6-luna'},
                      {'original_senses': []}, {'lookup_attempted': False}, {'references': []}):
            path.write_text(json.dumps(dict(original, **patch))+'\n')
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                agy.batch_records(self.root, batch)
        path.write_text(json.dumps(original)+'\n'+json.dumps(original)+'\n')
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            agy.batch_records(self.root, batch)

    def test_unresolved_and_candidates_are_not_supported(self):
        inventory, batches = self.queue()
        for index, batch in enumerate(batches.values()):
            self.write_batch(batch, 'needs_revision' if index == 0 else 'unresolved')
        progress = agy.progress(self.root, inventory, batches)
        self.assertEqual(5, progress['agy_attempted'])
        self.assertEqual(1, progress['fully_supported_headwords'])
        self.assertEqual(5, progress['remaining_not_fully_supported'])
        self.assertEqual(0, progress['handoff_without_record'])
        self.assertFalse(progress['patches_applied'])

    def test_unattempted_unresolved_skeleton_rejected(self):
        _, batches = self.queue()
        batch = batches[agy.PILOT]
        record = self.row(batch, batch['entries'][0], 'unresolved')
        record.update(lookup_attempted=False, references=[])
        (self.root / 'results' / (agy.PILOT + '.jsonl')).write_text(json.dumps(record)+'\n')
        with self.assertRaisesRegex(ValueError, 'unattempted skeleton'):
            agy.batch_records(self.root, batch)

    def test_pilot_approval_requires_records_and_keeps_result_hash(self):
        inventory, batches = self.queue()
        with self.assertRaisesRegex(ValueError, 'missing actual'):
            agy.approve_pilot(self.root, inventory, batches)
        self.write_batch(batches[agy.PILOT])
        agy.approve_pilot(self.root, inventory, batches)
        self.assertTrue(agy.pilot_accepted(self.root, inventory))
        path = self.root / 'results' / (agy.PILOT+'.jsonl')
        path.write_text(path.read_text()+' ')
        with self.assertRaisesRegex(ValueError, 'no longer matches'):
            agy.pilot_accepted(self.root, inventory)

    def test_runner_reuses_conversation_and_stops_for_pilot_evidence_check(self):
        inventory, batches = self.queue()
        def execute(args, **kwargs):
            self.assertEqual('existing-conversation', args[args.index('--conversation')+1])
            self.assertEqual(agy.MODEL, args[args.index('--model')+1])
            self.assertEqual(self.inventory['workspace_root'], kwargs['cwd'])
            self.assertIn('--sandbox', args)
            self.assertNotIn('--dangerously-skip-permissions', args)
            self.write_batch(batches[agy.PILOT])
            kwargs['stdout'].write(b'{"status":"SUCCESS"}')
            return SimpleNamespace(returncode=0)
        with mock.patch.object(agy.subprocess, 'run', side_effect=execute) as cli:
            state = agy.run(self.root, inventory, batches, 493)
        self.assertEqual(1, cli.call_count)
        self.assertEqual('pilot_awaiting_evidence_check', state['state'])
        with self.assertRaisesRegex(ValueError, 'awaits coordinator'):
            agy.run(self.root, inventory, batches, 1)

    def test_success_without_results_and_permission_denial_stop_without_retry(self):
        inventory, batches = self.queue()
        for response, expected in (({'status':'SUCCESS'}, 'blocked_incomplete_batch'),
                                   ({'status':'SUCCESS','denied_actions':[{'action':'read_url'}]}, 'blocked_cli_error_or_permission')):
            agy.save_json(self.root / 'run-state.json', {'state':'ready','model':agy.MODEL,
                'gpt_review_enabled':False,'conversation_id':'existing-conversation'})
            def execute(args, **kwargs):
                kwargs['stdout'].write(json.dumps(response).encode())
                return SimpleNamespace(returncode=0)
            with mock.patch.object(agy.subprocess, 'run', side_effect=execute) as cli:
                state = agy.run(self.root, inventory, batches, 493)
            self.assertEqual(expected, state['state'])
            self.assertEqual(1, cli.call_count)
            with self.assertRaisesRegex(ValueError, 'resolve cause'):
                agy.run(self.root, inventory, batches, 493)

    def test_cli_error_preserves_partial_rows_but_does_not_count_them_as_supported(self):
        inventory, batches = self.queue()
        batch = batches[agy.PILOT]
        def execute(args, **kwargs):
            record = self.row(batch, batch['entries'][0], 'unresolved')
            (self.root/'results'/(agy.PILOT+'.jsonl')).write_text(json.dumps(record)+'\n')
            kwargs['stdout'].write(b'{"status":"ERROR"}')
            return SimpleNamespace(returncode=1)
        with mock.patch.object(agy.subprocess, 'run', side_effect=execute):
            agy.run(self.root, inventory, batches, 493)
        progress = agy.progress(self.root, inventory, batches)
        self.assertEqual(1, progress['agy_attempted'])
        self.assertEqual(1, progress['fully_supported_headwords'])
        self.assertEqual(5, progress['remaining_not_fully_supported'])

    def test_corrupt_response_stops_at_durable_failure(self):
        inventory, batches = self.queue()
        def execute(args, **kwargs):
            kwargs['stdout'].write(b'not json')
            return SimpleNamespace(returncode=0)
        with mock.patch.object(agy.subprocess, 'run', side_effect=execute), self.assertRaises(ValueError):
            agy.run(self.root, inventory, batches, 493)
        self.assertEqual('failed_validation_or_transport', agy.read_json(self.root/'run-state.json')['state'])

    def test_other_active_dispatcher_is_not_overwritten(self):
        inventory, batches = self.queue()
        before = (self.root/'run-state.json').read_bytes()
        with mock.patch.object(agy.fcntl, 'flock', side_effect=BlockingIOError), self.assertRaisesRegex(ValueError, 'active'):
            agy.run(self.root, inventory, batches, 1)
        self.assertEqual(before, (self.root/'run-state.json').read_bytes())

    def test_approved_pilot_allows_next_batches_but_no_gpt_fallback(self):
        inventory, batches = self.queue()
        self.write_batch(batches[agy.PILOT])
        agy.approve_pilot(self.root, inventory, batches)
        remaining = iter(batch for key, batch in batches.items() if key != agy.PILOT)
        def execute(args, **kwargs):
            self.write_batch(next(remaining), 'unresolved')
            kwargs['stdout'].write(b'{"status":"SUCCESS"}')
            return SimpleNamespace(returncode=0)
        with mock.patch.object(agy.subprocess, 'run', side_effect=execute) as cli:
            agy.run(self.root, inventory, batches, 10)
        self.assertEqual(len(batches)-1, cli.call_count)
        progress = agy.progress(self.root, inventory, batches)
        self.assertGreater(progress['remaining_not_fully_supported'], 0)
        self.assertEqual('first_pass_recorded_not_semantically_complete', agy.read_json(self.root/'run-state.json')['state'])


if __name__ == '__main__':
    unittest.main()
