import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import audit_exam_sources as audit


class ExamSourceAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = {"schema_version": 1, "sources": []}

    def qwerty(self, rows, exam="ky"):
        name = f"{exam}.json"
        (self.root / name).write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
        source = {"group": "qwerty", "exam": exam, "file": name, "format": "qwerty-json",
                  "sha256": audit.sha256(self.root / name)}
        self.manifest["sources"].append(source)
        return source

    def kajweb(self, words, exam="ky", book_id="book"):
        rows = [{"headWord": word, "bookId": book_id,
                 "content": {"word": {"wordHead": word, "content": {
                     "trans": [{"tranCn": "中文"}], "ukphone": "abc"}}}}
                for word in words]
        path = self.root / "book.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("book.json", "\n".join(json.dumps(r) for r in rows))
        source = {"group": "kajweb", "exam": exam, "file": path.name,
                  "format": "kajweb-zip", "member": "book.json", "sha256": audit.sha256(path)}
        self.manifest["sources"].append(source)
        return source

    def result(self):
        return audit.audit(self.manifest, self.root)

    def test_conservative_normalization_preserves_accents_and_forms(self):
        self.assertEqual(audit.normalize("  CHILDREN’S  "), "children's")
        self.assertEqual(audit.normalize(" cafe\u0301 "), "café")
        self.assertEqual(audit.normalize(" Take   off "), "take off")
        self.assertNotEqual(audit.normalize("children"), "child")

    def test_overlap_and_duplicates_not_summed_as_unique_words(self):
        self.qwerty([{"name": "Study", "trans": ["学习"]},
                     {"name": "study", "trans": ["研究"]},
                     {"name": "children", "trans": ["孩子"]}])
        self.kajweb(["study", "child"])
        result = self.result()
        self.assertEqual(result["union"]["unique_headwords"], 3)
        self.assertEqual(result["group_overlap"]["kajweb|qwerty"], 1)
        self.assertEqual(result["books"][0]["duplicate_rows"], 1)
        self.assertEqual(result["headwords_with_multiple_translation_variants"], 1)
        self.assertFalse(result["syllabus_verified"])
        self.assertIn("not_content_approved", result["status"])

    def test_phrases_and_abbreviations_not_silently_discarded(self):
        self.qwerty([{"name": name, "trans": ["中文"]} for name in
                     ("study", "a.", "café", "take off", "mother-in-law")])
        result = self.result()["union"]
        self.assertEqual(result["unique_headwords"], 5)
        self.assertEqual(result["ascii_single_words"], 2)
        self.assertEqual(result["phrases"], 1)
        self.assertEqual(result["other_headwords"], 2)

    def test_missing_fields_and_non_chinese_distinct(self):
        self.qwerty([{"name": "one", "trans": []},
                     {"name": "two", "trans": ["English only"], "ukphone": "a"},
                     {"name": "three", "trans": ["中文"], "usphone": "b"},
                     {"name": "", "trans": ["中文"]}])
        row = self.result()["books"][0]
        self.assertEqual(row["empty_translation_rows"], 1)
        self.assertEqual(row["no_chinese_translation_rows"], 2)
        self.assertEqual(row["missing_both_phones_rows"], 2)
        self.assertEqual(row["empty_headword_rows"], 1)
        self.assertEqual(row["duplicate_rows"], 0)

    def test_sha256_mismatch_rejected(self):
        source = self.qwerty([{"name": "one", "trans": ["中文"]}])
        (self.root / source["file"]).write_text("[]")
        with self.assertRaisesRegex(ValueError, "SHA256"):
            self.result()

    def test_invalid_translation_shape_rejected(self):
        self.qwerty([{"name": "one", "trans": [2]}])
        with self.assertRaises(ValueError):
            self.result()

    def test_invalid_qwerty_container_rejected(self):
        self.qwerty({"name": "one"})
        with self.assertRaises(ValueError):
            self.result()

    def test_zip_member_book_id_mismatch_rejected(self):
        self.kajweb(["one"], book_id="wrong-book")
        with self.assertRaisesRegex(ValueError, "bookId"):
            self.result()

    def test_unsupported_schema_and_duplicate_book_rejected(self):
        self.manifest["schema_version"] = 2
        with self.assertRaises(ValueError):
            self.result()
        self.manifest["schema_version"] = 1
        source = self.qwerty([])
        self.manifest["sources"].append(source)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.result()

    def test_ecdict_coverage_is_exact_only_and_pinned(self):
        self.qwerty([{"name": name, "trans": ["中文"]} for name in ("one", "ones", "two")])
        path = self.root / "ecdict.csv"
        path.write_text("word,translation\none,中文\ntwo,English\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "SHA256"):
            audit.audit(self.manifest, self.root, path)
        with patch.object(audit, "ECDICT_SHA256", audit.sha256(path)):
            result = audit.audit(self.manifest, self.root, path)["ecdict_exact_coverage"]
        self.assertEqual(result["headwords_found"], 2)
        self.assertEqual(result["headwords_with_chinese"], 1)
        self.assertEqual(result["headwords_missing"], 1)

    def test_bad_input_does_not_overwrite_existing_report(self):
        source = self.qwerty([])
        source["sha256"] = "0" * 64
        manifest = self.root / "manifest.json"
        manifest.write_text(json.dumps(self.manifest))
        output = self.root / "output.json"
        output.write_text("keep")
        result = subprocess.run([sys.executable, "-B", audit.__file__,
                                 "--manifest", str(manifest), "--source-dir", str(self.root),
                                 "--output", str(output)], capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(output.read_text(), "keep")

    def test_output_cannot_overwrite_inputs_and_repeats_identically(self):
        source = self.qwerty([{"name": "one", "trans": ["中文"]}])
        manifest = self.root / "manifest.json"
        manifest.write_text(json.dumps(self.manifest))
        args = [sys.executable, "-B", audit.__file__, "--manifest", str(manifest),
                "--source-dir", str(self.root), "--output"]
        before = (self.root / source["file"]).read_bytes()
        result = subprocess.run(args + [str(self.root / source["file"])], capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual((self.root / source["file"]).read_bytes(), before)
        first, second = self.root / "first.json", self.root / "second.json"
        for output in (first, second):
            result = subprocess.run(args + [str(output)], capture_output=True)
            self.assertEqual(result.returncode, 0)
        self.assertEqual(first.read_bytes(), second.read_bytes())


if __name__ == "__main__":
    unittest.main()
