#!/usr/bin/env python3
"""Offline, reproducible structural audit; never approves dictionary semantics.

Usage: python3 audit_ecdict.py --csv /path/ecdict.csv --output /path/audit.json
No dictionary assets are selected, converted, or written. Raw CSV field values
(including literal backslash escapes) are preserved in samples and evidence.
"""

import argparse
from collections import Counter
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile


EXPECTED_SHA256 = "1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf"
UPSTREAM_COMMIT = "bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b"
SOURCE_URL = f"https://github.com/skywind3000/ECDICT/blob/{UPSTREAM_COMMIT}/ecdict.csv"
CSV_FIELDS = (
    "word", "phonetic", "definition", "translation", "pos", "collins",
    "oxford", "tag", "bnc", "frq", "exchange", "detail", "audio",
)
SAMPLE_WORDS = (
    "abandon", "evidence", "study", "research", "approach", "account",
    "address", "data", "analysis", "criterion", "phenomenon", "children",
    "men", "women", "be", "lie", "lay", "see", "left", "sanction",
)
WORD_PATTERN = re.compile(r"[a-z]+(?:['-][a-z]+)*")
HAN_PATTERN = re.compile(r"[\u3400-\u9fff\uf900-\ufaff\U00020000-\U0002fa1f]")
# Types documented by the pinned upstream README. Unknown types are reported,
# not repaired, and not taken as proof that their linguistic content is wrong.
EXCHANGE_TYPES = frozenset(("p", "d", "i", "3", "r", "t", "s", "0", "1"))


def exchange_issues(value):
    """Check slash-separated type:value syntax, not linguistic correctness."""
    issues = []
    seen = set()
    for index, token in enumerate(value.split("/"), 1):
        code, separator, form = token.partition(":")
        reasons = []
        if not separator:
            reasons.append("missing_colon")
        else:
            if code not in EXCHANGE_TYPES:
                reasons.append("undocumented_type")
            if not form.strip():
                reasons.append("empty_value")
            if ":" in form:
                reasons.append("extra_colon")
            if code in seen:
                reasons.append("duplicate_type")
            seen.add(code)
        if reasons:
            issues.append({"tokenIndex": index, "token": token, "issues": reasons})
    return issues


def known_risks(record):
    """Reproduce only the explicitly requested review flags, without inference."""
    word = record["word"].lower()
    translation = record["translation"]
    exchange = record["exchange"]
    tokens = exchange.split("/")
    risks = []

    def flag(code, field, note, **evidence):
        risks.append({
            "code": code,
            "word": record["word"],
            "recordNumber": record["recordNumber"],
            "field": field,
            "value": record[field],
            "reviewStatus": "待对照",
            "note": note,
            **evidence,
        })

    if word == "approach" and "方法" not in translation:
        flag(
            "approach_method_keyword_absent", "translation",
            "原文未出现‘方法’关键词，待对照可靠词典；关键词缺失不等同于语义算法判定缺义。",
            checkedKeyword="方法",
        )
    if word == "data" and "DOS" in translation.upper() and "日期" in translation:
        flag(
            "data_dos_date_text", "translation",
            "原文同时出现 DOS 与‘日期’，疑似混入 DATE 命令说明，待对照。",
            matchedKeywords=["DOS", "日期"],
        )
    suspect_forms = {
        "children": {"s:childrens"},
        "men": {"s:mens", "3:mens"},
        "women": {"s:womens", "3:womens"},
    }
    matches = [token for token in tokens if token in suspect_forms.get(word, ())]
    if matches:
        flag(
            f"{word}_suspect_exchange", "exchange",
            "原始 exchange 包含指定可复现的可疑词形，待对照；不自动更正。",
            matchedTokens=matches,
        )
    if word == "lie" and "说谎" in translation:
        missing = [token for token in ("p:lied", "d:lied") if token not in tokens]
        if missing:
            flag(
                "lie_lied_forms_missing", "exchange",
                "释义包含‘说谎’，但该词条正向 exchange 映射未覆盖指定 lied 词形；不表示整库不存在 lied 或逆向 lemma，待对照。",
                missingTokens=missing, translation=translation,
            )
    return risks


def coverage_hints(record):
    """Forward-field coverage only; inverse lemmas elsewhere are not assessed."""
    if record["word"].lower() != "be":
        return []
    forms = set()
    for token in record["exchange"].split("/"):
        code, separator, value = token.partition(":")
        if separator and code in ("p", "d", "i", "3"):
            forms.update(form.strip() for form in value.split(","))
    missing = [form for form in ("am", "are", "were") if form not in forms]
    if not missing:
        return []
    return [{
        "code": "be_forward_forms_not_listed",
        "word": record["word"], "recordNumber": record["recordNumber"],
        "field": "exchange", "value": record["exchange"],
        "missingForms": missing, "hardGate": False,
        "note": "仅提示 be 正向 exchange 字段未列出这些形式；整库可能有逆向 lemma，不计为硬错或硬门禁。",
    }]


def audit_csv(csv_path, expect_sha256=EXPECTED_SHA256):
    """Validate and audit one immutable byte snapshot before writing anything."""
    expected = expect_sha256.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("expected SHA256 must be exactly 64 hexadecimal characters")
    source = Path(csv_path).read_bytes()
    digest = hashlib.sha256(source).hexdigest()
    if digest != expected:
        raise ValueError(f"SHA256 mismatch: expected {expected}, got {digest}")
    if b"\x00" in source:
        raise ValueError("CSV contains a NUL byte")
    reader = csv.reader(io.StringIO(source.decode("utf-8-sig"), newline=""), strict=True)
    if next(reader, None) != list(CSV_FIELDS):
        raise ValueError("CSV header does not match the complete ECDICT schema")

    samples = {word: [] for word in SAMPLE_WORDS}
    word_counts = Counter()
    lower_counts = Counter()
    anomalies = []
    risks = []
    hints = []
    total = empty = no_han = accepted = exchange_count = 0
    for number, values in enumerate(reader, 1):
        if len(values) != len(CSV_FIELDS):
            raise ValueError(
                f"CSV record {number} has {len(values)} fields; expected {len(CSV_FIELDS)}"
            )
        row = dict(zip(CSV_FIELDS, values))
        word, translation, exchange = row["word"], row["translation"], row["exchange"]
        lower = word.lower()
        total += 1
        word_counts[word] += 1
        lower_counts[lower] += 1
        empty += not translation.strip()
        no_han += bool(translation.strip()) and not HAN_PATTERN.search(translation)
        accepted += WORD_PATTERN.fullmatch(lower) is not None
        if exchange:
            exchange_count += 1
            issues = exchange_issues(exchange)
            if issues:
                anomalies.append({
                    "recordNumber": number, "word": word,
                    "field": "exchange", "value": exchange, "issues": issues,
                })
        if lower in samples:
            record = {
                "recordNumber": number, "word": word,
                "translation": translation, "exchange": exchange, "tag": row["tag"],
            }
            samples[lower].append(record)
            risks.extend(known_risks(record))
            hints.extend(coverage_hints(record))

    duplicate_groups = {}
    for word, count in sorted(word_counts.items()):
        if lower_counts[word.lower()] > 1:
            duplicate_groups.setdefault(word.lower(), []).append({"word": word, "count": count})
    missing_words = [word for word in SAMPLE_WORDS if not samples[word]]
    for word in missing_words:
        risks.append({
            "code": "missing_audit_sample", "word": word,
            "field": "word", "value": None, "reviewStatus": "待对照",
            "note": "固定审计样本在输入中缺失，无法对照；不以其他词替换。",
        })

    pinned = digest == EXPECTED_SHA256
    return {
        "schemaVersion": 1,
        "status": "requires_review",
        "scope": {
            "sampleType": "exploratory_targeted",
            "sampleDescription": "本轮探索性定向样本，不是真实海文独立验收样本。",
            "independentHaiwenAcceptanceSample": False,
            "accuracyComputed": False, "errorRateComputed": False,
            "note": "不计算准确率，不按风险数 / 20 估计或外推整库错误率；全库仅进行结构统计。",
        },
        "semanticReview": {
            "status": "not_performed_by_script",
            "note": "脚本只提取原文、做结构统计与定点风险检查。参考来源对照由主线程另行记录，不等同于人工正式验收；其他词条仅提取，未获语义批准；risk-clean 不自动 PASS。",
        },
        "source": {
            "name": "ECDICT",
            "upstreamCommit": UPSTREAM_COMMIT if pinned else None,
            "sourceUrl": SOURCE_URL if pinned else None,
            "csvSha256": digest, "expectedCsvSha256": expected,
            "byteCount": len(source), "matchesPinnedSnapshot": pinned,
            "recordNumberDefinition": "1-based CSV 数据记录序号，不包含表头；不是物理行号",
        },
        "coverage": {
            "requestedCount": len(SAMPLE_WORDS),
            "foundCount": len(SAMPLE_WORDS) - len(missing_words),
            "matchedRecordCount": sum(map(len, samples.values())),
            "missingWords": missing_words,
        },
        "samples": [
            {"requestedWord": word, "present": bool(samples[word]),
             "semanticApproval": "not_granted", "records": samples[word]}
            for word in SAMPLE_WORDS
        ],
        "structuralStatistics": {
            "totalRows": total,
            "emptyTranslationRows": empty,
            "nonemptyTranslationWithoutHanRows": no_han,
            "translationWithoutHanRows": empty + no_han,
            "wordFilter": {
                "pattern": WORD_PATTERN.pattern,
                "normalization": "word.lower()，不去除空白；只统计，不选择或删除词条",
                "matchingRows": accepted, "nonmatchingRows": total - accepted,
            },
            "duplicates": {
                "normalization": "word.lower()，不合并或删除原始样本",
                "normalizedGroupCount": len(duplicate_groups),
                "normalizedExtraRows": sum(count - 1 for count in lower_counts.values()),
                "caseVariantGroupCount": sum(len(group) > 1 for group in duplicate_groups.values()),
                "exactSpellingExtraRows": sum(count - 1 for count in word_counts.values()),
                "groups": [
                    {"normalizedWord": word, "variants": variants}
                    for word, variants in sorted(duplicate_groups.items())
                ],
            },
            "exchange": {
                "nonemptyRows": exchange_count,
                "documentedTypes": sorted(EXCHANGE_TYPES),
                "check": "type:value 项以 / 分隔；检查冒号、空值、未文档化类型与重复类型，不检查词形语义",
                "anomalyRows": len(anomalies),
                "anomalyTokens": sum(len(item["issues"]) for item in anomalies),
                "anomalies": anomalies,
            },
        },
        "risks": risks,
        "coverageHints": hints,
        "riskGate": {
            "status": "flagged" if risks else "no_known_flags",
            "riskCount": len(risks),
            "requireCleanExitCode": 2 if risks else 0,
            "note": "仅为固定风险与样本覆盖门禁；exit=0 表示审计成功，不表示词库内容可接入。",
        },
    }


def write_report(output_path, report):
    """Replace the requested report atomically, never leave partial JSON."""
    output = Path(output_path)
    text = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", newline="\n", dir=output.parent,
            prefix=f".{output.name}.", suffix=".tmp", delete=False,
        ) as handle:
            temporary = Path(handle.name)
            handle.write(text)
        os.replace(temporary, output)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", required=True, type=Path, help="explicit local ECDICT CSV input")
    parser.add_argument("--output", required=True, type=Path, help="explicit JSON report; parent must exist")
    parser.add_argument("--expect-sha256", default=EXPECTED_SHA256, help="expected input SHA256; pinned by default")
    parser.add_argument("--require-clean", action="store_true", help="exit 2 when known risk flags or missing samples are found")
    args = parser.parse_args(argv)
    try:
        if args.csv.resolve() == args.output.resolve():
            raise ValueError("JSON output must not overwrite the CSV input")
        report = audit_csv(args.csv, args.expect_sha256)
        write_report(args.output, report)
    except (OSError, UnicodeError, ValueError, csv.Error) as error:
        print(f"Audit failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps({
        "status": report["status"], "totalRows": report["structuralStatistics"]["totalRows"],
        "sampleCount": report["coverage"]["foundCount"],
        "riskCount": report["riskGate"]["riskCount"], "csvSha256": report["source"]["csvSha256"],
    }, ensure_ascii=False, sort_keys=True))
    return report["riskGate"]["requireCleanExitCode"] if args.require_clean else 0


if __name__ == "__main__":
    sys.exit(main())
