"""Read-only, offline inventory of pinned exam-preparation books, not a semantic grader."""
import argparse
import csv
import hashlib
import itertools
import json
from pathlib import Path
import re
import unicodedata
import zipfile

ECDICT_SHA256 = "1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf"
WORD = re.compile(r"[a-z]+(?:['-][a-z]+)*\Z")
HAN = re.compile(r"[\u3400-\u9fff]")


def normalize(headword):
    # Keep phrases, accents and punctuation; never guess a lemma or strip a suffix.
    return " ".join(unicodedata.normalize("NFC", headword).strip().lower()
                    .replace("’", "'").replace("‘", "'").split())


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_book(source, root):
    path = root / source["file"]
    if Path(source["file"]).name != source["file"]:
        raise ValueError("source file must be a basename")
    if sha256(path) != source["sha256"]:
        raise ValueError(f"SHA256 mismatch: {source['file']}")
    if source["format"] == "qwerty-json":
        rows = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(rows, list):
            raise ValueError("Qwerty book must be an array")
    elif source["format"] == "kajweb-zip":
        with zipfile.ZipFile(path) as archive:
            # Read a named member, never extract paths into the workspace.
            rows = [json.loads(line) for line in archive.read(source["member"])
                    .decode("utf-8-sig").splitlines() if line.strip()]
    else:
        raise ValueError("unsupported source format")
    records = []
    for row in rows:
        if source["format"] == "qwerty-json":
            head = row["name"]
            content = row
            translations = content.get("trans", [])
        else:
            head = row["headWord"]
            word = row["content"]["word"]
            if head != word["wordHead"]:
                raise ValueError(f"inconsistent headWord/wordHead: {head}")
            if row["bookId"] != source["member"].removesuffix(".json"):
                raise ValueError("unexpected bookId")
            content = word["content"]
            translations = [item.get("tranCn", "") for item in content.get("trans", [])]
        if not isinstance(head, str) or not isinstance(translations, list) or not all(
                isinstance(item, str) for item in translations):
            raise ValueError("invalid headword or translations")
        phones = [content.get(key, "") or "" for key in ("ukphone", "usphone")]
        if not all(isinstance(phone, str) for phone in phones):
            raise ValueError("invalid phonetic field")
        records.append((normalize(head), tuple(text.strip() for text in translations
                                              if text.strip()), *phones))
    return records


def audit(manifest, root, ecdict=None):
    if manifest["schema_version"] != 1:
        raise ValueError("unsupported inventory schema")
    books, groups, meanings = {}, {}, {}
    stats = []
    for source in manifest["sources"]:
        label = f"{source['group']}:{source['exam']}"
        if label in books:
            raise ValueError("duplicate group/exam")
        rows = read_book(source, root)
        heads = {row[0] for row in rows if row[0]}
        books[label] = heads
        groups.setdefault(source["group"], set()).update(heads)
        for head, trans, _, _ in rows:
            if head:
                meanings.setdefault(head, set()).add(trans)
        stats.append({
            "book": label, "rows": len(rows), "unique_headwords": len(heads),
            "empty_headword_rows": sum(not r[0] for r in rows),
            "duplicate_rows": sum(bool(r[0]) for r in rows) - len(heads),
            "empty_translation_rows": sum(not r[1] for r in rows),
            "no_chinese_translation_rows": sum(not HAN.search(" ".join(r[1])) for r in rows),
            "missing_ukphone_rows": sum(not r[2].strip() for r in rows),
            "missing_usphone_rows": sum(not r[3].strip() for r in rows),
            "missing_both_phones_rows": sum(not r[2].strip() and not r[3].strip() for r in rows),
        })
    union = set().union(*groups.values()) if groups else set()
    singles = {head for head in union if WORD.fullmatch(head)}
    phrases = {head for head in union if " " in head}
    other = union - singles - phrases
    result = {
        "status": "source_analysis_only_not_content_approved",
        "normalization": "NFC + lowercase + curly apostrophe + whitespace; no lemmatization",
        "books": stats,
        "groups": {group: {"unique_headwords": len(heads)} for group, heads in sorted(groups.items())},
        "union": {"unique_headwords": len(union), "ascii_single_words": len(singles),
                  "phrases": len(phrases), "other_headwords": len(other),
                  "other_examples": sorted(other)[:15]},
        "book_overlap": {f"{a}|{b}": len(books[a] & books[b])
                         for a, b in itertools.combinations(sorted(books), 2)},
        "group_overlap": {f"{a}|{b}": len(groups[a] & groups[b])
                          for a, b in itertools.combinations(sorted(groups), 2)},
        "headwords_with_multiple_translation_variants": sum(len(v) > 1 for v in meanings.values()),
        "variant_warning": "Different strings are review candidates, not proven semantic conflicts.",
        "syllabus_verified": False,
        "redistribution_status": "unresolved; no commercial dictionary content enters app assets",
    }
    if ecdict is not None:
        if sha256(ecdict) != ECDICT_SHA256:
            raise ValueError("ECDICT SHA256 mismatch")
        present, chinese = set(), set()
        with ecdict.open(encoding="utf-8-sig", newline="") as stream:
            for row in csv.DictReader(stream):
                head = normalize(row["word"])
                if head in union:
                    present.add(head)
                    if HAN.search(row["translation"]):
                        chinese.add(head)
        result["ecdict_exact_coverage"] = {
            "headwords_found": len(present), "headwords_with_chinese": len(chinese),
            "headwords_missing": len(union - present),
            "single_words_missing": len(singles - present),
            "scope": "Exact normalized spelling only; not semantic correctness or inflection coverage.",
        }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--ecdict", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        protected = {args.manifest.resolve(), *((args.source_dir / item["file"]).resolve()
                     for item in manifest["sources"])}
        if args.ecdict:
            protected.add(args.ecdict.resolve())
        if args.output.resolve() in protected:
            raise ValueError("output must not overwrite an input")
        result = audit(manifest, args.source_dir, args.ecdict)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        parser.exit(2, f"source audit rejected: {error}\n")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "union": result["union"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
