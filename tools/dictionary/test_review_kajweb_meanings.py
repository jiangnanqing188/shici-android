import json
from pathlib import Path
import unittest

import review_kajweb_meanings as review
import test_build_kajweb_app as fixtures


class ReviewKajwebMeaningsTest(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.BuildKajwebAppTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.root, self.manifest, self.output = fixture.root, fixture.manifest, fixture.output

    def test_partition_is_disjoint_complete_deterministic_and_source_preserving(self):
        first = review.prepare(self.manifest, self.root, self.output)
        second_dir = self.root / "second"
        review.prepare(self.manifest, self.root, second_dir)
        self.assertEqual(5, first["entry_count"])
        self.assertEqual(10, len(first["shards"]))
        entries = []
        for shard in first["shards"]:
            path = self.output / "shards" / shard["file"]
            self.assertEqual(path.read_bytes(), (second_dir / "shards" / shard["file"]).read_bytes())
            entries.extend(json.loads(path.read_text())["entries"])
        self.assertEqual(5, len({r["headword"] for r in entries}))
        self.assertEqual(5, len(entries))
        self.assertIn(" 简历 ", [r["senses"][0]["meaning"] for r in entries])
        with self.assertRaises(ValueError):
            review.prepare(self.manifest, self.root, self.output)

    def original(self):
        return {"headword": "word", "senses": [{"pos": "n", "meaning": "原义"}]}

    def record(self, status="supported"):
        return {"headword": "word", "original_senses": self.original()["senses"], "status": status,
            "lookup_attempted": True, "reason": "外部词典支持此义", "issues": [], "proposed_senses": None,
            "references": [{"url": "https://dictionary.cambridge.org/dictionary/english/word",
                "title": "WORD", "accessed_date": "2026-10-09", "access": "full", "evidence_summary": "原义对应名词用法"}]}

    def test_supported_without_evidence_or_lookup_is_rejected(self):
        for field, value in (("references", []), ("lookup_attempted", False)):
            record = self.record()
            record[field] = value
            with self.assertRaises(ValueError):
                review.validate_review(record, self.original())

    def test_source_changes_and_invented_status_are_rejected(self):
        for field, value in (("original_senses", [{"pos": "n", "meaning": "篡改"}]), ("status", "auto_correct")):
            record = self.record()
            record[field] = value
            with self.assertRaises(ValueError):
                review.validate_review(record, self.original())

    def test_revision_requires_changed_senses_and_issue(self):
        record = self.record("needs_revision")
        with self.assertRaises(ValueError):
            review.validate_review(record, self.original())
        record["proposed_senses"] = [{"pos": "n", "meaning": "修后义"}]
        with self.assertRaises(ValueError):
            review.validate_review(record, self.original())
        record["issues"] = [{"type": "wrong_meaning", "explanation": "原义错误"}]
        review.validate_review(record, self.original())

    def test_unresolved_without_lookup_is_honest_and_cannot_patch(self):
        record = self.record("unresolved")
        record["lookup_attempted"] = False
        record["references"] = []
        record["reason"] = "未取得外部证据"
        review.validate_review(record, self.original())
        record["proposed_senses"] = [{"pos": "n", "meaning": "擅自改动"}]
        with self.assertRaises(ValueError):
            review.validate_review(record, self.original())

    def test_bad_reference_host_or_no_entry_and_title_only_evidence_rejected(self):
        for url in ("http://dictionary.cambridge.org/word", "https://example.com/word", "https://dictionary.cambridge.org/"):
            record = self.record()
            record["references"][0]["url"] = url
            with self.assertRaises(ValueError):
                review.validate_review(record, self.original())
        record = self.record()
        record["references"][0]["evidence_summary"] = ""
        with self.assertRaises(ValueError):
            review.validate_review(record, self.original())

    def test_summary_counts_unattempted_separately_and_detects_duplicates(self):
        review.prepare(self.manifest, self.root, self.output)
        inventory = json.loads((self.output / "inventory.json").read_text())
        for shard in inventory["shards"]:
            originals = json.loads((self.output / "shards" / shard["file"]).read_text())["entries"]
            records = []
            for original in originals:
                record = self.record("unresolved")
                record.update(headword=original["headword"], original_senses=original["senses"],
                              references=[], lookup_attempted=False, reason="尚未取得外部证据")
                records.append(record)
            (self.output / "results" / f"shard-{shard['id']}.jsonl").write_text(
                "\n".join(json.dumps(r) for r in records))
        result = review.summarize(self.output, require_complete=True)
        self.assertEqual(5, result["review_record_count"])
        self.assertEqual(5, result["not_attempted_count"])
        self.assertEqual(0, result["lookup_attempted_count"])
        self.assertEqual(0, result["external_evidence_count"])
        path = self.output / "results/shard-01.jsonl"
        path.write_text(path.read_text() + "\n" + path.read_text())
        with self.assertRaisesRegex(ValueError, "duplicate"):
            review.summarize(self.output)

    def test_missing_reviews_and_assignment_tampering_detected(self):
        review.prepare(self.manifest, self.root, self.output)
        result = review.summarize(self.output)
        self.assertEqual(5, result["pending_count"])
        with self.assertRaisesRegex(ValueError, "no review record"):
            review.summarize(self.output, require_complete=True)
        path = self.output / "shards/shard-01.json"
        path.write_text("changed")
        with self.assertRaisesRegex(ValueError, "hash changed"):
            review.summarize(self.output)


if __name__ == "__main__":
    unittest.main()
