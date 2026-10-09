import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import zipfile

import audit_exam_sources as audit
import merge_exam_dictionary as merge


class MergeExamDictionaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = {"schema_version": 1, "sources": []}
        self.manifest_path = self.root / "manifest.json"
        self.output = self.root / "merged.sqlite3"
        self.agents = self.root / "agents"
        self.agents.mkdir()

    def add_qwerty(self, exam, words):
        path = self.root / f"{exam}.json"
        path.write_text(json.dumps([{"name": word, "trans": ["  中文原文  "],
                                     "ukphone": "uk", "usphone": "us"} for word in words]),
                        encoding="utf-8")
        self.add_source("qwerty", exam, path, "qwerty-json")

    def add_kajweb(self, exam, words):
        path = self.root / f"{exam}.zip"
        rows = [{"headWord": word, "wordRank": i, "bookId": exam,
                 "content": {"word": {"wordHead": word, "content": {
                     "trans": [{"pos": "vt", "tranCn": " 源释义 "}],
                     "ukphone": "", "usphone": "us-2"}}}}
                for i, word in enumerate(words, 1)]
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(f"{exam}.json", "\n".join(json.dumps(row) for row in rows))
        source = self.add_source("kajweb", exam, path, "kajweb-zip")
        source["member"] = f"{exam}.json"

    def add_source(self, group, exam, path, kind):
        source = {"group": group, "exam": exam, "book": "测试词书", "file": path.name,
                  "format": kind, "sha256": audit.sha256(path), "commit": "a" * 40,
                  "url": "https://example.test/fixture", "syllabus_verified": False,
                  "redistribution_status": "unresolved"}
        self.manifest["sources"].append(source)
        return source

    def save_inputs(self):
        self.manifest_path.write_text(json.dumps(self.manifest))
        for exam in {s["exam"] for s in self.manifest["sources"]}:
            sources = [s for s in self.manifest["sources"] if s["exam"] == exam]
            sets = [set(r[0] for r in audit.read_book(source, self.root)) for source in sources]
            report = {"exam": exam, "source_hashes": {s["file"]: s["sha256"] for s in sources},
                      "headwords": sorted(set().union(*sets)),
                      "union_count": len(set().union(*sets)),
                      "intersection_count": len(set.intersection(*sets))}
            (self.agents / f"{exam}.json").write_text(json.dumps(report))

    def build(self, output=None):
        self.save_inputs()
        return merge.merge(self.manifest_path, self.root, output or self.output, self.agents)

    def connect(self):
        db = sqlite3.connect(self.output)
        self.addCleanup(db.close)
        return db

    def test_deduplicates_headwords_but_preserves_domains_and_every_source_row(self):
        self.add_qwerty("ky", ["Study", " study ", "studies"])
        self.add_kajweb("cet4", ["study", "child"])
        result = self.build()
        self.assertEqual(result["unique_headwords"], 3)
        self.assertEqual(result["source_entry_count"], 5)
        self.assertEqual(result["domain_counts"], {"cet4": 2, "ky": 2})
        self.assertEqual(result["domain_overlap"]["cet4|ky"], 1)
        db = self.connect()
        tags = db.execute("""SELECT exam FROM word_exams JOIN words ON words.id=word_id
                             WHERE headword='study' ORDER BY exam""").fetchall()
        self.assertEqual(tags, [("cet4",), ("ky",)])
        self.assertEqual(db.execute("PRAGMA integrity_check").fetchone()[0], "ok")
        self.assertEqual(db.execute("PRAGMA foreign_key_check").fetchall(), [])

    def test_exact_meaning_pos_and_phonetic_variants_not_overwritten(self):
        self.add_qwerty("ky", ["Study"])
        self.add_kajweb("ky", ["study"])
        self.build()
        db = self.connect()
        rows = db.execute("SELECT source_id,senses_json,ukphone,usphone FROM source_entries ORDER BY source_id").fetchall()
        self.assertEqual(json.loads(rows[0][1]), [{"pos": "vt", "meaning": " 源释义 "}])
        self.assertEqual(json.loads(rows[1][1]), [{"pos": None, "meaning": "  中文原文  "}])
        self.assertEqual(rows[0][2:], ("", "us-2"))
        self.assertEqual(rows[1][2:], ("uk", "us"))
        self.assertEqual(db.execute("SELECT original_headword FROM source_entries WHERE source_id='qwerty:ky'").fetchone()[0], "Study")
        self.assertEqual(db.execute("SELECT COUNT(*) FROM source_entries WHERE content_review_status='unreviewed'").fetchone()[0], 2)
        self.assertEqual(db.execute("SELECT value FROM metadata WHERE key='syllabus_verified'").fetchone()[0], "false")

    def test_phrases_abbreviations_accents_preserved_not_fake_lemmas(self):
        self.add_qwerty("ky", ["a.", "vs.", "Take off", "café", "children", "child"])
        result = self.build()
        self.assertEqual(result["unique_headwords"], 6)
        self.assertEqual(result["word_kinds"], {"other": 3, "phrase": 1, "single_word": 2})

    def test_failed_hash_does_not_create_database(self):
        self.add_qwerty("ky", ["study"])
        self.save_inputs()
        (self.root / "ky.json").write_text("[]")
        with self.assertRaisesRegex(ValueError, "SHA256"):
            merge.merge(self.manifest_path, self.root, self.output, self.agents)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob(".exam-merge-*")))

    def test_independent_agent_count_headwords_and_hash_must_match(self):
        self.add_qwerty("ky", ["study"])
        self.save_inputs()
        path = self.agents / "ky.json"
        good = json.loads(path.read_text())
        for key, bad in [("union_count", 18000), ("headwords", ["fake"]),
                         ("source_hashes", {"ky.json": "0" * 64})]:
            wrong = dict(good)
            wrong[key] = bad
            path.write_text(json.dumps(wrong))
            with self.assertRaisesRegex(ValueError, "agent result mismatch"):
                merge.merge(self.manifest_path, self.root, self.output, self.agents)
            self.assertFalse(self.output.exists())

    def test_missing_agent_report_fails_before_creation(self):
        self.add_qwerty("ky", ["study"])
        self.manifest_path.write_text(json.dumps(self.manifest))
        with self.assertRaises(FileNotFoundError):
            merge.merge(self.manifest_path, self.root, self.output, self.agents)
        self.assertFalse(self.output.exists())

    def test_existing_database_and_input_are_never_overwritten(self):
        self.add_qwerty("ky", ["study"])
        self.save_inputs()
        self.output.write_bytes(b"keep")
        with self.assertRaisesRegex(ValueError, "already exists"):
            merge.merge(self.manifest_path, self.root, self.output, self.agents)
        self.assertEqual(self.output.read_bytes(), b"keep")
        before = self.manifest_path.read_bytes()
        with self.assertRaisesRegex(ValueError, "overwrite an input"):
            merge.merge(self.manifest_path, self.root, self.manifest_path, self.agents)
        self.assertEqual(self.manifest_path.read_bytes(), before)

    def test_empty_headword_is_rejected_not_silently_dropped(self):
        self.add_qwerty("ky", [" "])
        with self.assertRaisesRegex(ValueError, "empty headword"):
            self.build()
        self.assertFalse(self.output.exists())

    def test_duplicate_source_id_rejected(self):
        self.add_qwerty("ky", ["study"])
        self.manifest["sources"].append(dict(self.manifest["sources"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate source"):
            self.build()

    def test_database_repeat_is_byte_identical(self):
        self.add_qwerty("ky", ["study", "child"])
        self.add_kajweb("cet4", ["study"])
        first = self.build()
        second_path = self.root / "repeat.sqlite3"
        second = self.build(second_path)
        self.assertEqual(first, second)
        self.assertEqual(self.output.read_bytes(), second_path.read_bytes())

    def test_cli_report_cannot_overwrite_input(self):
        self.add_qwerty("ky", ["study"])
        self.save_inputs()
        before = self.manifest_path.read_bytes()
        result = subprocess.run([sys.executable, "-B", merge.__file__, "--manifest", str(self.manifest_path),
                                 "--source-dir", str(self.root), "--agent-reports", str(self.agents),
                                 "--output", str(self.output), "--report", str(self.manifest_path)],
                                capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.output.exists())
        self.assertEqual(self.manifest_path.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
