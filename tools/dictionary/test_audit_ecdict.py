"""Run with: python3 -B -m unittest discover -s tools/dictionary -v."""

import contextlib
import csv
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import audit_ecdict as audit


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="ecdict-audit-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.csv_path = self.root / "input.csv"
        self.output_path = self.root / "audit.json"

    def row(self, word, translation="n. 测试", exchange="", tag=""):
        return {"word": word, "translation": translation, "exchange": exchange, "tag": tag}

    def write_csv(self, rows):
        with self.csv_path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=audit.CSV_FIELDS)
            writer.writeheader()
            for row in rows:
                writer.writerow({key: row.get(key, "") for key in audit.CSV_FIELDS})
        return hashlib.sha256(self.csv_path.read_bytes()).hexdigest()

    def clean_rows(self):
        return [
            self.row(word, "n. 方法" if word == "approach" else "n. 测试")
            for word in audit.SAMPLE_WORDS
        ]

    def risk_rows(self):
        rows = self.clean_rows()
        replacements = {
            "approach": self.row("approach", r"n. 接近, 入门\nvt. 接近"),
            "data": self.row("data", r"pl. 数据\n[计] DOS内部命令:显示系统日期"),
            "children": self.row("children", "pl. 孩子", "0:child/1:s/s:childrens"),
            "men": self.row("men", "pl. man的复数", "0:man/1:s/s:mens/3:mens"),
            "women": self.row("women", "pl. 女人", "0:woman/1:s/s:womens/3:womens"),
            "lie": self.row("lie", r"n. 谎言\nvi. 躺着, 说谎", "p:lay/i:lying/3:lies/s:lies/d:lain"),
        }
        return [replacements.get(row["word"], row) for row in rows]

    def run_audit(self, rows):
        return audit.audit_csv(self.csv_path, self.write_csv(rows))

    def run_cli(self, *extra, digest=None):
        args = ["--csv", str(self.csv_path), "--output", str(self.output_path)]
        if digest is not None:
            args += ["--expect-sha256", digest]
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return audit.main(args + list(extra))

    def test_default_hash_is_pinned_and_rejects_different_input(self):
        self.write_csv(self.clean_rows())
        self.assertEqual(audit.EXPECTED_SHA256, "1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf")
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            audit.audit_csv(self.csv_path)
        self.assertEqual(self.run_cli(), 1)
        self.assertFalse(self.output_path.exists())

    def test_bad_hash_does_not_overwrite_an_existing_report(self):
        self.write_csv(self.clean_rows())
        self.output_path.write_text("previous report", encoding="utf-8")
        self.assertEqual(self.run_cli(digest="0" * 64), 1)
        self.assertEqual(self.output_path.read_text(), "previous report")

    def test_malformed_hash_is_rejected(self):
        self.write_csv(self.clean_rows())
        with self.assertRaisesRegex(ValueError, "64 hexadecimal"):
            audit.audit_csv(self.csv_path, "not-a-hash")

    def test_original_values_and_literal_escapes_are_unchanged(self):
        original = self.row(
            "STUDY", "n. 原文\\n字面\\t制表\\r回车\\\\斜杠\n真换行\t真制表\r真回车,引号\"",
            "0:原样\\n/1:s", " Ky  cet4 ",
        )
        report = self.run_audit([original])
        sample = next(item for item in report["samples"] if item["requestedWord"] == "study")
        record = sample["records"][0]
        for key, value in original.items():
            self.assertEqual(record[key], value)
        audit.write_report(self.output_path, report)
        reread = json.loads(self.output_path.read_text(encoding="utf-8"))
        self.assertEqual(reread, report)
        self.assertEqual(sample["records"][0]["recordNumber"], 1)

    def test_sample_words_are_fixed_and_ordered_even_if_csv_is_reversed(self):
        expected = "abandon evidence study research approach account address data analysis criterion phenomenon children men women be lie lay see left sanction".split()
        self.assertEqual(list(audit.SAMPLE_WORDS), expected)
        report = self.run_audit(list(reversed(self.clean_rows())))
        self.assertEqual([item["requestedWord"] for item in report["samples"]], expected)
        self.assertEqual(report["coverage"], {
            "requestedCount": 20, "foundCount": 20, "matchedRecordCount": 20, "missingWords": [],
        })
        self.assertEqual(report["source"]["csvSha256"], hashlib.sha256(self.csv_path.read_bytes()).hexdigest())
        self.assertFalse(report["source"]["matchesPinnedSnapshot"])
        self.assertIsNone(report["source"]["upstreamCommit"])

    def test_all_six_known_risks_locate_exact_raw_fields(self):
        rows = self.risk_rows()
        report = self.run_audit(rows)
        self.assertEqual(report["riskGate"]["riskCount"], 6)
        expected_fields = {
            "approach": "translation", "data": "translation", "children": "exchange",
            "men": "exchange", "women": "exchange", "lie": "exchange",
        }
        self.assertEqual({risk["word"] for risk in report["risks"]}, set(expected_fields))
        for risk in report["risks"]:
            record = rows[risk["recordNumber"] - 1]
            self.assertEqual(risk["field"], expected_fields[risk["word"]])
            self.assertEqual(risk["value"], record[risk["field"]])
            self.assertEqual(risk["word"], record["word"])
            self.assertEqual(risk["reviewStatus"], "待对照")
        by_word = {risk["word"]: risk for risk in report["risks"]}
        self.assertEqual(by_word["men"]["matchedTokens"], ["s:mens", "3:mens"])
        self.assertEqual(by_word["women"]["matchedTokens"], ["s:womens", "3:womens"])
        self.assertEqual(by_word["lie"]["missingTokens"], ["p:lied", "d:lied"])
        self.assertIn("不等同于语义", by_word["approach"]["note"])
        self.assertEqual(report["status"], "requires_review")

    def test_keyword_checks_and_exchange_tokens_do_not_overmatch(self):
        cases = [
            (self.row("approach", "n. 方法"), False),
            (self.row("approach", "n. 途径"), True),  # keyword review, not a semantic verdict
            (self.row("data", "DOS 命令"), False),
            (self.row("data", "日期"), False),
            (self.row("data", "dos 日期"), True),
            (self.row("children", exchange="s:childrens-extra"), False),
            (self.row("men", exchange="s:mens-extra/0:mens"), False),
            (self.row("men", exchange="3:mens"), True),
            (self.row("women", exchange="3:womens"), True),
            (self.row("lie", "vi. 躺着", "p:lay/d:lain"), False),
            (self.row("lie", "vi. 说谎", "p:lied/d:lied"), False),
            (self.row("lie", "vi. 说谎", "p:lied"), True),
            (self.row("lie", "vi. 说谎", "d:lied"), True),
            (self.row("other", "DOS 日期, 说谎", "s:mens"), False),
        ]
        for row, has_risk in cases:
            with self.subTest(row=row):
                self.assertEqual(bool(audit.known_risks({**row, "recordNumber": 1})), has_risk)

    def test_full_library_statistics_do_not_filter_or_merge_records(self):
        rows = [
            self.row("Study", "", "0:study/1:s"),
            self.row("study", "  "), self.row("STUDY", "English only"),
            self.row("study", "n. 学习", "p:studied/d:studied"),
            self.row("well-known"), self.row("can't"), self.row("two words"),
            self.row("bad_1", exchange="p:/bad/f:form/p:two:parts/p:again"),
            self.row("中文", exchange="s:forms/"),
            self.row(" spaced "),
        ]
        report = self.run_audit(rows)
        stats = report["structuralStatistics"]
        self.assertEqual(stats["totalRows"], 10)
        self.assertEqual(stats["emptyTranslationRows"], 2)
        self.assertEqual(stats["nonemptyTranslationWithoutHanRows"], 1)
        self.assertEqual(stats["translationWithoutHanRows"], 3)
        self.assertEqual(stats["wordFilter"]["matchingRows"], 6)
        self.assertEqual(stats["wordFilter"]["nonmatchingRows"], 4)
        duplicate = stats["duplicates"]
        self.assertEqual(duplicate["normalizedGroupCount"], 1)
        self.assertEqual(duplicate["normalizedExtraRows"], 3)
        self.assertEqual(duplicate["caseVariantGroupCount"], 1)
        self.assertEqual(duplicate["exactSpellingExtraRows"], 1)
        self.assertEqual(stats["exchange"]["nonemptyRows"], 4)
        self.assertEqual(stats["exchange"]["anomalyRows"], 2)
        self.assertEqual(stats["exchange"]["anomalyTokens"], 6)
        sample = next(item for item in report["samples"] if item["requestedWord"] == "study")
        self.assertEqual([record["word"] for record in sample["records"]], ["Study", "study", "STUDY", "study"])
        self.assertEqual(stats["exchange"]["anomalies"][0]["value"], rows[7]["exchange"])
        self.assertEqual(stats["exchange"]["anomalies"][0]["recordNumber"], 8)

    def test_documented_lemma_types_and_combinations_are_not_anomalies(self):
        for value in ("0:child/1:s", "0:leave/1:dp/d:left/p:left", "p:forms/d:forms/i:forms/3:forms/r:forms/t:forms/s:forms"):
            with self.subTest(value=value):
                self.assertEqual(audit.exchange_issues(value), [])

    def test_missing_sample_is_not_replaced_and_blocks_clean_gate(self):
        rows = [row for row in self.clean_rows() if row["word"] != "sanction"]
        report = self.run_audit(rows)
        self.assertEqual(report["coverage"]["foundCount"], 19)
        self.assertEqual(report["coverage"]["missingWords"], ["sanction"])
        self.assertEqual(len(report["samples"]), 20)
        self.assertEqual(report["samples"][-1], {"requestedWord": "sanction", "present": False, "semanticApproval": "not_granted", "records": []})
        self.assertEqual(report["risks"][0]["code"], "missing_audit_sample")
        self.assertEqual(report["risks"][0]["field"], "word")
        self.assertIsNone(report["risks"][0]["value"])
        self.assertEqual(report["riskGate"]["requireCleanExitCode"], 2)

    def test_no_known_risks_still_requires_review(self):
        report = self.run_audit(self.clean_rows())
        self.assertEqual(report["risks"], [])
        self.assertEqual(report["riskGate"]["status"], "no_known_flags")
        self.assertEqual(report["status"], "requires_review")
        self.assertEqual(report["semanticReview"]["status"], "not_performed_by_script")
        self.assertIn("参考来源对照由主线程另行记录，不等同于人工正式验收", report["semanticReview"]["note"])
        self.assertEqual(report["riskGate"]["requireCleanExitCode"], 0)

    def test_exploratory_sample_is_not_accuracy_or_semantic_approval(self):
        report = self.run_audit(self.risk_rows())
        self.assertEqual(report["scope"]["sampleType"], "exploratory_targeted")
        self.assertFalse(report["scope"]["independentHaiwenAcceptanceSample"])
        self.assertFalse(report["scope"]["accuracyComputed"])
        self.assertFalse(report["scope"]["errorRateComputed"])
        self.assertNotIn("accuracy", report)
        self.assertNotIn("errorRate", report)
        self.assertTrue(all(sample["semanticApproval"] == "not_granted" for sample in report["samples"]))
        lie = next(risk for risk in report["risks"] if risk["word"] == "lie")
        self.assertIn("正向 exchange", lie["note"])
        self.assertIn("不表示整库不存在", lie["note"])

    def test_be_forward_coverage_hint_is_not_a_hard_error(self):
        rows = self.clean_rows()
        be = next(row for row in rows if row["word"] == "be")
        be["exchange"] = "p:was/3:is/d:been/i:being"
        report = self.run_audit(rows)
        self.assertEqual(report["risks"], [])
        self.assertEqual(report["riskGate"]["requireCleanExitCode"], 0)
        hint = report["coverageHints"][0]
        self.assertEqual(hint["word"], "be")
        self.assertEqual(hint["field"], "exchange")
        self.assertEqual(hint["value"], be["exchange"])
        self.assertEqual(hint["missingForms"], ["am", "are", "were"])
        self.assertFalse(hint["hardGate"])
        be["recordNumber"] = 1
        be["exchange"] = "p:was,were/3:is,am,are/d:been/i:being"
        self.assertEqual(audit.coverage_hints(be), [])

    def test_cli_success_and_require_clean_exit_codes(self):
        for rows, normal_exit, clean_exit in (
            (self.risk_rows(), 0, 2), (self.clean_rows(), 0, 0), ([], 0, 2),
        ):
            with self.subTest(sample_count=len(rows)):
                digest = self.write_csv(rows)
                self.assertEqual(self.run_cli(digest=digest), normal_exit)
                self.assertEqual(self.run_cli("--require-clean", digest=digest), clean_exit)
                self.assertEqual(json.loads(self.output_path.read_text())["status"], "requires_review")

    def test_process_exit_code_for_risk_gate(self):
        digest = self.write_csv(self.risk_rows())
        result = subprocess.run([
            sys.executable, "-B", str(Path(audit.__file__).resolve()),
            "--csv", str(self.csv_path), "--output", str(self.output_path),
            "--expect-sha256", digest, "--require-clean",
        ], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertEqual(json.loads(result.stdout)["riskCount"], 6)
        self.assertTrue(self.output_path.is_file())

    def test_report_is_byte_for_byte_reproducible_without_timestamps(self):
        digest = self.write_csv(self.risk_rows())
        original_csv = self.csv_path.read_bytes()
        self.assertEqual(self.run_cli(digest=digest), 0)
        first = self.output_path.read_bytes()
        self.assertEqual(self.run_cli(digest=digest), 0)
        self.assertEqual(self.output_path.read_bytes(), first)
        self.assertEqual(self.csv_path.read_bytes(), original_csv)
        self.assertFalse(list(self.root.glob(".*.tmp")))

    def test_rejects_csv_shape_encoding_and_incomplete_quoted_records(self):
        header = ",".join(audit.CSV_FIELDS) + "\n"
        inputs = [
            b"word,translation\nstudy,test\n",
            (header + "study,test\n").encode(),
            (header + ",".join(["study"] + [""] * 13) + "\n").encode(),
            (header + '"study,unterminated').encode(),
            header.encode() + b"\xff\n",
            header.encode() + b"\x00\n",
        ]
        for source in inputs:
            with self.subTest(source=source[:25]):
                self.csv_path.write_bytes(source)
                digest = hashlib.sha256(source).hexdigest()
                self.assertEqual(self.run_cli(digest=digest), 1)
                self.assertFalse(self.output_path.exists())

    def test_input_cannot_be_output(self):
        digest = self.write_csv(self.clean_rows())
        original = self.csv_path.read_bytes()
        self.output_path = self.csv_path
        self.assertEqual(self.run_cli(digest=digest), 1)
        self.assertEqual(self.csv_path.read_bytes(), original)

    def test_missing_parent_is_reported_without_partial_output(self):
        digest = self.write_csv(self.clean_rows())
        self.output_path = self.root / "missing" / "audit.json"
        self.assertEqual(self.run_cli(digest=digest), 1)
        self.assertFalse(self.output_path.exists())


if __name__ == "__main__":
    unittest.main()
