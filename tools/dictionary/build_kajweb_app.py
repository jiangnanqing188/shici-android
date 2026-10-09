"""Build a pinned kajweb-only audit database and deterministic local Android assets."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import zipfile

import merge_exam_dictionary as merger

COMMIT = "3992bcb94c800a2fd38a9fd6ff95b2353e755363"
SOURCE = "kajweb/dict · 有道来源备考词书"
PRIORITY = ("ky", "cet4", "cet6", "tem4", "tem8")


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
                    encoding="utf-8")


def escape(value):
    if "\r" in value or "\x00" in value:
        raise ValueError("unsupported control character in source field")
    return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


def select_inventory(path):
    inventory = json.loads(path.read_text(encoding="utf-8"))
    if inventory["schema_version"] != 1:
        raise ValueError("unsupported inventory schema")
    sources = [s for s in inventory["sources"] if s["group"] == "kajweb"]
    if len(sources) != len(PRIORITY) or {s["exam"] for s in sources} != set(PRIORITY):
        raise ValueError("exactly five kajweb exam books are required")
    if any(s["commit"] != COMMIT or s["format"] != "kajweb-zip" for s in sources):
        raise ValueError("unexpected kajweb revision or format")
    return {"schema_version": 1, "sources": sorted(sources, key=lambda s: PRIORITY.index(s["exam"]))}


def export_rows(database):
    """Keep every headword and unique original POS/meaning pair, ky first; never infer aliases."""
    senses_by_word = {}
    with sqlite3.connect(database) as db:
        for exam in PRIORITY:
            records = db.execute("""SELECT w.headword,e.senses_json FROM source_entries e
                JOIN words w ON w.id=e.word_id JOIN sources s ON s.id=e.source_id
                WHERE s.exam=? ORDER BY e.entry_index""", (exam,))
            for headword, senses_json in records:
                senses = json.loads(senses_json)
                if not senses or any(not sense["meaning"].strip() for sense in senses):
                    raise ValueError(f"missing source meaning: {headword}")
                pairs = senses_by_word.setdefault(headword, [])
                for sense in senses:
                    pair = (sense["pos"] or "", sense["meaning"])
                    if pair not in pairs:
                        pairs.append(pair)
        expected = {head for head, in db.execute("SELECT headword FROM words")}
    if set(senses_by_word) != expected:
        raise ValueError("export does not cover all database headwords")
    lines = ["\t".join(("SHICI_KAJWEB", "1", COMMIT, escape(SOURCE)))]
    for head, pairs in sorted(senses_by_word.items()):
        positions = list(dict.fromkeys(pos for pos, _ in pairs if pos))
        meaning = "\n".join(f"[{pos}] {text}" if pos else text for pos, text in pairs)
        lines.append("\t".join(("E", escape(head), escape(" / ".join(positions)), escape(meaning))))
    return ("\n".join(lines) + "\n").encode("utf-8")


def build(manifest_path, source_dir, output_dir):
    if output_dir.exists():
        raise ValueError("output directory already exists; use a new path")
    inventory = select_inventory(manifest_path)
    # Validate original hashes/structure before creating output; never modify source books.
    for source in inventory["sources"]:
        merger.source_entries(source, source_dir)
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".kajweb-build-", dir=output_dir.parent) as temporary:
        stage = Path(temporary)
        selected = stage / "source-inventory.json"
        write_json(selected, inventory)
        database = stage / "kajweb.sqlite3"
        report = merger.merge(selected, source_dir, database)
        payload = export_rows(database)
        compressed = gzip.compress(payload, compresslevel=9, mtime=0)
        # CPython's mtime=0 fast path can expose a platform OS byte; pin it for reproducibility.
        compressed = compressed[:9] + b"\xff" + compressed[10:]
        assets = stage / "assets" / "dictionary"
        assets.mkdir(parents=True)
        (assets / "kajweb.tsv.gz").write_bytes(compressed)
        manifest = {
            "format": "SHICI_KAJWEB", "format_version": 1, "source": SOURCE, "version": COMMIT,
            "asset_sha256": hashlib.sha256(compressed).hexdigest(),
            "entry_count": report["unique_headwords"], "alias_count": 0,
            "kaoyan_count": report["domain_counts"]["ky"], "source_count": report["source_count"],
            "source_entry_count": report["source_entry_count"], "domain_counts": report["domain_counts"],
            "source_inventory": inventory, "source_priority": list(PRIORITY),
            "content_review_status": "unreviewed", "syllabus_verified": False,
            "redistribution_status": "unresolved", "local_candidate_only": True,
            "normalization": "NFC+lowercase+curly-apostrophe+whitespace; no lemmatization",
            "compressed_bytes": len(compressed), "uncompressed_bytes": len(payload),
        }
        write_json(assets / "kajweb-manifest.json", manifest)
        write_json(stage / "build-report.json", {**report, "android_asset": manifest})
        # All files are complete before making the candidate available. Existing outputs are refused.
        stage.rename(output_dir)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = build(args.manifest, args.source_dir, args.output_dir)
    except (ValueError, KeyError, OSError, sqlite3.Error, zipfile.BadZipFile) as error:
        parser.exit(2, f"build rejected: {error}\n")
    print(json.dumps({key: manifest[key] for key in ("source", "version", "entry_count", "kaoyan_count",
                     "source_count", "source_entry_count", "asset_sha256", "compressed_bytes")},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
