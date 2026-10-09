import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zipfile

import build_kajweb_app as build


class BuildKajwebAppTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = self.root / "inventory.json"
        self.output = self.root / "output"
        self.sources = []
        for exam in build.PRIORITY:
            words = [("evidence", "n", "考研原义" if exam == "ky" else "其他原义")]
            if exam == "ky":
                words += [("résumé", "n", " 简历 "), ("a.", "abbr", "形容词"),
                          ("in spite of", "prep", "尽管"), ("escape", "v", "斜线\\制表\t换行\n原文")]
            if exam == "tem8":
                words += [("vs.", "abbr", "对抗"), ("evidence", "v", "证明")]
            rows = [{"headWord": word, "wordRank": i, "bookId": exam, "content": {"word": {
                "wordHead": word, "content": {"trans": [{"pos": pos, "tranCn": text}],
                                               "ukphone": "UK", "usphone": "US"}}}}
                    for i, (word, pos, text) in enumerate(words, 1)]
            path = self.root / f"{exam}.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(f"{exam}.json", "\n".join(json.dumps(row) for row in rows))
            self.sources.append({"group": "kajweb", "exam": exam, "book": exam, "file": path.name,
                "format": "kajweb-zip", "member": f"{exam}.json", "commit": build.COMMIT,
                "url": "https://example.invalid/fixture", "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "syllabus_verified": False, "redistribution_status": "unresolved"})
        self.write_manifest()

    def write_manifest(self):
        self.manifest.write_text(json.dumps({"schema_version": 1, "sources": self.sources}))

    def compile(self, output=None):
        return build.build(self.manifest, self.root, output or self.output)

    def text(self):
        return gzip.decompress((self.output / "assets/dictionary/kajweb.tsv.gz").read_bytes()).decode()

    def test_only_kajweb_keeps_every_special_headword_and_original_sense(self):
        self.sources.append({"group": "qwerty", "file": "does-not-exist"})
        self.write_manifest()
        manifest = self.compile()
        self.assertEqual(6, manifest["entry_count"])
        self.assertEqual(5, manifest["kaoyan_count"])
        self.assertEqual(0, manifest["alias_count"])
        self.assertEqual(5, manifest["source_count"])
        for word in ("résumé", "a.", "vs.", "in spite of"):
            self.assertIn(f"E\t{word}\t", self.text())
        evidence = next(line for line in self.text().splitlines() if line.startswith("E\tevidence\t"))
        self.assertIn("[n] 考研原义\\n[n] 其他原义\\n[v] 证明", evidence)
        self.assertEqual(1, evidence.count("其他原义"))
        self.assertIn("[n]  简历 ", self.text())
        self.assertIn("斜线\\\\制表\\t换行\\n原文", self.text())

    def test_database_preserves_all_source_entries_phones_and_status(self):
        manifest = self.compile()
        with sqlite3.connect(self.output / "kajweb.sqlite3") as db:
            self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
            self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
            self.assertEqual(manifest["source_entry_count"], db.execute("SELECT COUNT(*) FROM source_entries").fetchone()[0])
            self.assertEqual({("UK", "US", "unreviewed")}, set(db.execute(
                "SELECT ukphone,usphone,content_review_status FROM source_entries")))
        self.assertFalse(manifest["syllabus_verified"])
        self.assertEqual("unresolved", manifest["redistribution_status"])

    def test_repeat_builds_have_identical_bytes_and_manifest_hash(self):
        manifest = self.compile()
        second = self.root / "second"
        self.compile(second)
        first_files = sorted(path.relative_to(self.output) for path in self.output.rglob("*") if path.is_file())
        for path in first_files:
            self.assertEqual((self.output / path).read_bytes(), (second / path).read_bytes(), str(path))
        asset = (self.output / "assets/dictionary/kajweb.tsv.gz").read_bytes()
        self.assertEqual(manifest["asset_sha256"], hashlib.sha256(asset).hexdigest())
        self.assertEqual(255, asset[9])

    def test_existing_output_is_not_overwritten(self):
        self.output.mkdir()
        sentinel = self.output / "mine"
        sentinel.write_text("keep")
        with self.assertRaises(ValueError):
            self.compile()
        self.assertEqual("keep", sentinel.read_text())

    def test_hash_mismatch_leaves_no_partial_output(self):
        (self.root / "ky.zip").write_bytes(b"modified")
        with self.assertRaisesRegex(ValueError, "SHA256"):
            self.compile()
        self.assertFalse(self.output.exists())

    def test_other_revision_or_incomplete_domain_selection_rejected(self):
        self.sources[0]["commit"] = "wrong"
        self.write_manifest()
        with self.assertRaises(ValueError):
            self.compile()
        self.sources.pop()
        self.write_manifest()
        with self.assertRaises(ValueError):
            self.compile()

    def test_duplicate_domain_rejected(self):
        self.sources[-1] = self.sources[0]
        self.write_manifest()
        with self.assertRaises(ValueError):
            self.compile()

    def test_invalid_control_character_rejected(self):
        for value in ("bad\x00", "bad\r"):
            with self.assertRaises(ValueError):
                build.escape(value)


if __name__ == "__main__":
    unittest.main()
