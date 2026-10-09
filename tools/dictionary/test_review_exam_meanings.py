import json
import unittest

import review_exam_meanings as exam_review
import test_plan_exam_meaning_review as fixtures


class ReviewExamMeaningsTest(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.PlanExamMeaningReviewTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.fixture = fixture
        # Give a multi-book word covered and pending senses, plus one fully excluded word.
        fixture.write_record('evidence')
        fixture.write_record('résumé')
        self.root = fixture.output
        self.inventory = fixture.build()

    def rows(self):
        for slot in self.inventory['slots']:
            for batch in slot['batches']:
                data = json.loads((self.root / 'batches' / f"{batch['id']}.json").read_text())
                for entry in data['entries']:
                    yield batch['id'], entry

    def record(self, batch_id, entry, status='supported'):
        record = self.fixture.record({'headword': entry['headword'], 'senses': entry['pending_senses']}, status)
        record['batch_id'] = batch_id
        return record

    def write(self, batch_id, record):
        path = self.root / 'results' / f'{batch_id}.jsonl'
        with path.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + '\n')

    def test_empty_results_are_pending_not_completed(self):
        result = exam_review.summarize(self.root)
        self.assertEqual(5, result['pending_count'])
        self.assertEqual(1, result['excluded_complete_headwords'])
        self.assertFalse(any(b['first_pass_attempted'] for b in result['batch_progress']))
        with self.assertRaisesRegex(ValueError, 'no review record'):
            exam_review.summarize(self.root, require_complete=True)

    def test_all_incremental_results_validate_without_rechecking_covered_senses(self):
        for batch_id, entry in self.rows():
            self.write(batch_id, self.record(batch_id, entry))
        result = exam_review.summarize(self.root, True)
        self.assertEqual('all_batches', result['scope'])
        self.assertEqual({'supported': 5}, result['status_counts'])
        self.assertEqual(5, result['lookup_attempted_count'])
        self.assertTrue(all(b['fully_supported'] for b in result['batch_progress']))
        self.assertFalse(result['patches_applied'])

    def test_complete_unattempted_skeleton_is_not_reviewed(self):
        for batch_id, entry in self.rows():
            record = self.record(batch_id, entry, 'unresolved')
            record.update(lookup_attempted=False, references=[])
            self.write(batch_id, record)
        result = exam_review.summarize(self.root, True)
        self.assertEqual(0, result['pending_count'])
        self.assertEqual(0, result['lookup_attempted_count'])
        self.assertEqual(5, result['not_attempted_count'])
        self.assertFalse(any(b['first_pass_attempted'] for b in result['batch_progress']))

    def test_attempted_unresolved_is_not_fully_supported(self):
        for batch_id, entry in self.rows():
            self.write(batch_id, self.record(batch_id, entry, 'unresolved'))
        result = exam_review.summarize(self.root)
        self.assertTrue(all(b['first_pass_attempted'] for b in result['batch_progress']))
        self.assertFalse(any(b['fully_supported'] for b in result['batch_progress']))
        self.assertEqual(5, len(result['unresolved_headwords']))

    def test_supported_without_actual_lookup_and_evidence_rejected(self):
        batch_id, entry = next(self.rows())
        record = self.record(batch_id, entry)
        record.update(lookup_attempted=False, references=[])
        self.write(batch_id, record)
        with self.assertRaisesRegex(ValueError, 'actual lookup'):
            exam_review.summarize(self.root)

    def test_wrong_original_senses_including_already_covered_are_rejected(self):
        batch_id, entry = next((b, e) for b, e in self.rows() if e['covered_senses'])
        record = self.record(batch_id, entry)
        record['original_senses'] = entry['senses']
        self.write(batch_id, record)
        with self.assertRaisesRegex(ValueError, 'original differs'):
            exam_review.summarize(self.root)

    def test_duplicate_and_wrong_batch_results_rejected(self):
        batch_id, entry = next(self.rows())
        record = self.record(batch_id, entry)
        self.write(batch_id, record)
        self.write(batch_id, record)
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            exam_review.summarize(self.root)
        (self.root / 'results' / f'{batch_id}.jsonl').unlink()
        record['batch_id'] = 'another-batch'
        self.write(batch_id, record)
        with self.assertRaisesRegex(ValueError, 'wrong batch'):
            exam_review.summarize(self.root)

    def test_assignment_hash_and_inventory_counts_rejected(self):
        path = self.root / 'inventory.json'
        inventory = dict(self.inventory, pending_headwords=999)
        path.write_text(json.dumps(inventory))
        with self.assertRaisesRegex(ValueError, 'coverage'):
            exam_review.summarize(self.root)
        path.write_text(json.dumps(self.inventory))
        batch_id, _ = next(self.rows())
        (self.root / 'batches' / f'{batch_id}.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'hash changed'):
            exam_review.summarize(self.root)

    def test_selected_batch_can_be_complete_while_others_are_pending(self):
        batch_id, _ = next(self.rows())
        chosen = [(b, e) for b, e in self.rows() if b == batch_id]
        for b, entry in chosen:
            self.write(b, self.record(b, entry))
        result = exam_review.summarize(self.root, True, batch_id)
        self.assertEqual(batch_id, result['scope'])
        self.assertEqual(len(chosen), result['assigned_count'])
        self.assertEqual(0, result['pending_count'])
        with self.assertRaisesRegex(ValueError, 'no review record'):
            exam_review.summarize(self.root, True)

    def test_selected_batch_does_not_read_another_in_progress_result(self):
        batch_id, _ = next(self.rows())
        for b, entry in self.rows():
            if b == batch_id:
                self.write(b, self.record(b, entry))
        other = next(b for b, _ in self.rows() if b != batch_id)
        (self.root / 'results' / f'{other}.jsonl').write_text('{unfinished')
        result = exam_review.summarize(self.root, True, batch_id)
        self.assertEqual(0, result['pending_count'])

    def test_unknown_batch_scope_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'unknown batch'):
            exam_review.summarize(self.root, batch_id='typo')

    def test_revision_is_reported_as_candidate_never_applied(self):
        batch_id, entry = next(self.rows())
        record = self.record(batch_id, entry, 'needs_revision')
        record['proposed_senses'] = [{'pos': 'n', 'meaning': '待验收的候选'}]
        record['issues'] = [{'type': 'wrong_meaning', 'explanation': '测试候选'}]
        self.write(batch_id, record)
        result = exam_review.summarize(self.root)
        self.assertEqual(1, len(result['proposed_patches']))
        self.assertFalse(result['patches_applied'])


if __name__ == '__main__':
    unittest.main()
