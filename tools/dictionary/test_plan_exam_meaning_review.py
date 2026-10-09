import json
from pathlib import Path
import tempfile
import unittest

import plan_exam_meaning_review as planner
import review_kajweb_meanings as review
import test_build_kajweb_app as fixtures


class PlanExamMeaningReviewTest(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.BuildKajwebAppTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.root, self.manifest = fixture.root, fixture.manifest
        self.previous = self.root / 'previous'
        review.prepare(self.manifest, self.root, self.previous)
        self.output = self.root / 'plan'

    def record(self, original, status='supported'):
        return {'headword': original['headword'], 'original_senses': original['senses'], 'status': status,
                'lookup_attempted': True, 'reason': '支持原义', 'issues': [], 'proposed_senses': None,
                'references': [{'url': 'https://dictionary.cambridge.org/dictionary/english/word',
                    'title': 'WORD', 'accessed_date': '2026-10-10', 'access': 'full', 'evidence_summary': '支持原词性与原义'}]}

    def write_record(self, word, status='supported', copies=1, conflicting=False):
        for file in sorted((self.previous / 'shards').glob('*.json')):
            for original in json.loads(file.read_text())['entries']:
                if original['headword'] != word:
                    continue
                record = self.record(original, status)
                records = [record] * copies
                if conflicting:
                    altered = dict(record, reason='不同判定说明')
                    records.append(altered)
                path = self.previous / 'results' / file.name.replace('.json', '.jsonl')
                path.write_text('\n'.join(json.dumps(r) for r in records))
                return
        self.fail('fixture word absent')

    def build(self, path=None):
        return planner.plan(self.manifest, self.root, self.previous, path or self.output, batch_size=2)

    def test_all_five_sources_cover_once_and_batches_are_bounded(self):
        result = self.build()
        self.assertEqual(6, result['source_headwords'])
        self.assertEqual(6, result['pending_headwords'])
        heads=[]
        for slot in result['slots']:
            for batch in slot['batches']:
                data=json.loads((self.output/'batches'/f"{batch['id']}.json").read_text())
                self.assertLessEqual(len(data['entries']),2)
                heads += [r['headword'] for r in data['entries']]
        self.assertEqual(6,len(heads)); self.assertEqual(6,len(set(heads)))
        self.assertEqual(set(planner.EXAMS),set(result['source_domain_counts']))

    def test_same_verified_word_is_excluded(self):
        self.write_record('résumé')
        result = self.build()
        self.assertEqual(1,result['excluded_complete_headwords'])
        self.assertEqual('résumé',result['excluded_words'][0]['headword'])

    def test_new_sense_on_verified_word_is_only_incrementally_reviewed(self):
        self.write_record('evidence')
        result = self.build()
        rows=[r for p in (self.output/'slots').glob('*.json') for r in json.loads(p.read_text())['entries']]
        evidence=next(r for r in rows if r['headword']=='evidence')
        self.assertEqual([{'pos':'n','meaning':'考研原义'}],evidence['covered_senses'])
        self.assertEqual([{'pos':'n','meaning':'其他原义'},{'pos':'v','meaning':'证明'}],evidence['pending_senses'])
        self.assertEqual(5,len(evidence['exams']))
        self.assertEqual(0,result['excluded_complete_headwords'])

    def test_unresolved_is_not_completed_even_when_lookup_was_attempted(self):
        self.write_record('résumé','unresolved')
        self.assertEqual(0,self.build()['excluded_complete_headwords'])

    def test_identical_retry_is_reused_but_conflicting_duplicate_is_quarantined(self):
        self.write_record('résumé',copies=2)
        self.assertEqual(1,self.build()['excluded_complete_headwords'])
        self.write_record('résumé',conflicting=True)
        result=self.build(self.root/'second')
        self.assertEqual(0,result['excluded_complete_headwords'])
        self.assertTrue(result['previous_review_warnings'])

    def test_candidates_are_not_applied_or_excluded(self):
        for file in (self.previous/'shards').glob('*.json'):
            for original in json.loads(file.read_text())['entries']:
                if original['headword']=='résumé':
                    record=self.record(original,'needs_revision')
                    record['issues']=[{'type':'wrong_meaning','explanation':'候选修订'}]
                    record['proposed_senses']=[{'pos':'n','meaning':'修后释义'}]
                    (self.previous/'results'/file.name.replace('.json','.jsonl')).write_text(json.dumps(record))
        result=self.build()
        self.assertEqual(0,result['excluded_complete_headwords'])
        self.assertFalse(result['candidate_revisions_applied'])

    def test_overlap_is_distributed_to_smaller_group(self):
        entries=[]
        for i in range(5): entries.append({'headword':f'a{i}','exams':['cet4'],'weight':1})
        for i in range(1): entries.append({'headword':f'b{i}','exams':['cet6'],'weight':1})
        for i in range(4): entries.append({'headword':f's{i}','exams':['cet4','cet6'],'weight':1})
        slots=planner.balance(entries,100)
        self.assertEqual({'cet4-1':5,'cet6-1':5},{s['id']:s['entry_count'] for s in slots})

    def test_large_group_splits_in_two_without_duplicate_words(self):
        rows=[{'headword':f'w{i}','exams':['tem8'],'weight':1} for i in range(9)]
        slots=planner.balance(rows,5)
        self.assertEqual(2,len(slots))
        self.assertLessEqual(abs(slots[0]['entry_count']-slots[1]['entry_count']),1)
        self.assertEqual(9,len({r['headword'] for s in slots for r in s['entries']}))

    def test_output_and_previous_assignment_are_protected(self):
        self.build()
        with self.assertRaises(ValueError): self.build()
        path=self.previous/'shards/shard-01.json'
        path.write_text('corrupt')
        with self.assertRaises(ValueError): self.build(self.root/'new')

    def test_repeat_plans_have_same_bytes(self):
        self.build()
        other=self.root/'repeat'
        self.build(other)
        for path in self.output.rglob('*'):
            if path.is_file(): self.assertEqual(path.read_bytes(),(other/path.relative_to(self.output)).read_bytes())


if __name__=='__main__':
    unittest.main()
