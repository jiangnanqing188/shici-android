"""Build the pinned ky/cet6 study-membership index as a deterministic local Android asset.

Read-only over the pinned source ZIPs; reuses the audit normalization (NFC + lowercase +
curly-apostrophe + whitespace, no lemmatization) so member keys match the runtime dictionary's
entry keys. Membership is per exam book, in the original book order, deduplicated by normalized
headword. Personal vocabulary is a runtime set and is not part of this generated index.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import zipfile

import audit_exam_sources as audit

COMMIT = "3992bcb94c800a2fd38a9fd6ff95b2353e755363"
SOURCE = "kajweb/dict · 有道来源备考词书"
FORMAT = "SHICI_STUDY_BOOKS"
FORMAT_VERSION = 1
# Fixed order and labels; only the two exam books selected for the first study version.
BOOKS = [("ky", "考研", "KaoYan_2.zip", "KaoYan_2.json"),
         ("cet6", "六级", "CET6_2.zip", "CET6_2.json")]


def escape(value):
    if "\r" in value or "\x00" in value:
        raise ValueError("unsupported control character in source field")
    return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


def select_sources(inventory_path):
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    if inventory["schema_version"] != 1:
        raise ValueError("unsupported inventory schema")
    by_exam = {}
    for source in inventory["sources"]:
        if source["group"] == "kajweb" and source["exam"] in {exam for exam, _, _, _ in BOOKS}:
            by_exam[source["exam"]] = source
    if set(by_exam) != {exam for exam, _, _, _ in BOOKS}:
        raise ValueError("both ky and cet6 kajweb sources are required")
    for exam, label, filename, member in BOOKS:
        source = by_exam[exam]
        if source["commit"] != COMMIT or source["format"] != "kajweb-zip":
            raise ValueError(f"unexpected revision/format for {exam}")
        if source["file"] != filename or source["member"] != member:
            raise ValueError(f"unexpected file/member for {exam}")
    return inventory, by_exam


def members(source, root):
    """Normalized unique member keys of one book, in original order, without lemmatization."""
    if not audit.WORD.pattern:  # defensive: audit module must expose its word form
        raise ValueError("audit module missing WORD pattern")
    seen = set()
    ordered = []
    for head, _, _, _ in audit.read_book(source, root):
        if not head:
            raise ValueError("empty headword in source book")
        if head != audit.normalize(head) or not audit.WORD.fullmatch(head):
            raise ValueError(f"member is not a single normalized word: {head!r}")
        if head not in seen:
            seen.add(head)
            ordered.append(head)
    if not ordered:
        raise ValueError("empty source book")
    return ordered


def build(inventory_path, source_dir, output_dir):
    if output_dir.exists():
        raise ValueError("output directory already exists; use a new path")
    inventory, by_exam = select_sources(inventory_path)
    books = []
    lines = ["\t".join((FORMAT, str(FORMAT_VERSION), COMMIT, escape(SOURCE)))]
    for exam, label, _, _ in BOOKS:
        source = by_exam[exam]
        ordered = members(source, source_dir)
        lines.append("\t".join(("BOOK", exam, str(len(ordered)))))
        for index, key in enumerate(ordered, 1):
            lines.append("\t".join(("WORD", str(index), escape(key))))
        books.append({"id": exam, "label": label, "exam": exam,
                      "file": source["file"], "member": source["member"],
                      "sha256": source["sha256"], "count": len(ordered)})
    payload = ("\n".join(lines) + "\n").encode("utf-8")
    compressed = gzip.compress(payload, compresslevel=9, mtime=0)
    # CPython's mtime=0 fast path can expose a platform OS byte; pin it for reproducibility.
    compressed = compressed[:9] + b"\xff" + compressed[10:]
    manifest = {
        "format": FORMAT, "format_version": FORMAT_VERSION, "source": SOURCE, "version": COMMIT,
        "asset": "dictionary/study-books.tsv.bin",
        "asset_sha256": hashlib.sha256(compressed).hexdigest(),
        "asset_bytes": len(compressed), "uncompressed_bytes": len(payload),
        "book_counts": {book["id"]: book["count"] for book in books},
        "books": books,
        "content_review_status": "unreviewed", "syllabus_verified": False,
        "redistribution_status": "unresolved", "local_candidate_only": True,
        "normalization": "NFC+lowercase+curly-apostrophe+whitespace; no lemmatization",
        "note": "仅考研/六级归属；个人生词为运行时动态集合。",
    }
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".study-books-", dir=output_dir.parent) as temporary:
        stage = Path(temporary)
        assets = stage / "dictionary"
        assets.mkdir(parents=True)
        (assets / "study-books.tsv.bin").write_bytes(compressed)
        (assets / "study-books-manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        (stage / "build-report.json").write_text(
            json.dumps({"manifest": manifest, "inventory_sha256": audit.sha256(inventory_path)},
                       ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        stage.rename(output_dir)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = build(args.inventory, args.source_dir, args.output_dir)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        parser.exit(2, f"study-books build rejected: {error}\n")
    print(json.dumps({key: manifest[key] for key in (
        "asset_sha256", "asset_bytes", "uncompressed_bytes", "book_counts")},
        ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
