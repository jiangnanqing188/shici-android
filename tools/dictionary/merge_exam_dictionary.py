"""Merge pinned preparation books into a local SQLite database, preserving source variants."""
import argparse
import itertools
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import zipfile

import audit_exam_sources as audit

SCHEMA = """
PRAGMA foreign_keys=ON;
CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE sources (
    id TEXT PRIMARY KEY, source_group TEXT NOT NULL, exam TEXT NOT NULL,
    book_title TEXT NOT NULL, filename TEXT NOT NULL, upstream_commit TEXT NOT NULL,
    url TEXT NOT NULL, sha256 TEXT NOT NULL,
    syllabus_verified INTEGER NOT NULL CHECK(syllabus_verified IN (0,1)),
    redistribution_status TEXT NOT NULL
);
CREATE TABLE words (
    id INTEGER PRIMARY KEY, headword TEXT NOT NULL UNIQUE,
    kind TEXT NOT NULL CHECK(kind IN ('single_word','phrase','other'))
);
CREATE TABLE word_exams (
    word_id INTEGER NOT NULL REFERENCES words(id), exam TEXT NOT NULL,
    PRIMARY KEY (word_id, exam)
);
CREATE TABLE source_entries (
    source_id TEXT NOT NULL REFERENCES sources(id), entry_index INTEGER NOT NULL,
    word_id INTEGER NOT NULL REFERENCES words(id), original_headword TEXT NOT NULL,
    original_rank INTEGER, ukphone TEXT NOT NULL, usphone TEXT NOT NULL,
    senses_json TEXT NOT NULL, content_review_status TEXT NOT NULL DEFAULT 'unreviewed',
    PRIMARY KEY (source_id, entry_index)
);
CREATE INDEX source_entries_word ON source_entries(word_id);
CREATE INDEX word_exams_exam ON word_exams(exam, word_id);
"""


def json_text(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def source_entries(source, root):
    # Reuse hash and structural gates; load originals separately to preserve exact text/POS.
    checked = audit.read_book(source, root)
    path = root / source["file"]
    if source["format"] == "qwerty-json":
        originals = json.loads(path.read_text(encoding="utf-8"))
    else:
        with zipfile.ZipFile(path) as archive:
            originals = [json.loads(line) for line in archive.read(source["member"])
                         .decode("utf-8-sig").splitlines() if line.strip()]
    records = []
    for index, (original, record) in enumerate(zip(originals, checked), 1):
        headword, _, ukphone, usphone = record
        if not headword:
            raise ValueError(f"empty headword: {source['file']} row {index}")
        if source["format"] == "qwerty-json":
            raw_head = original["name"]
            senses = [{"pos": None, "meaning": text} for text in original.get("trans", [])]
            rank = None
        else:
            raw_head = original["headWord"]
            content = original["content"]["word"]["content"]
            senses = [{"pos": item.get("pos"), "meaning": item.get("tranCn", "")}
                      for item in content.get("trans", [])]
            rank = original.get("wordRank")
        if rank is not None and (not isinstance(rank, int) or isinstance(rank, bool)):
            raise ValueError("invalid original rank")
        if any(sense["pos"] is not None and not isinstance(sense["pos"], str) for sense in senses):
            raise ValueError("invalid part of speech")
        records.append({"headword": headword, "original_headword": raw_head,
                        "entry_index": index, "original_rank": rank,
                        "ukphone": ukphone, "usphone": usphone, "senses": senses})
    return records


def validate_agents(sources, books, directory):
    results = []
    for exam in sorted({source["exam"] for source in sources}):
        report = json.loads((directory / f"{exam}.json").read_text(encoding="utf-8"))
        subset = [source for source in sources if source["exam"] == exam]
        hashes = {source["file"]: source["sha256"] for source in subset}
        sets = [{row["headword"] for row in books[f"{s['group']}:{s['exam']}"]} for s in subset]
        union = set().union(*sets)
        intersection = set.intersection(*sets)
        if (report["exam"] != exam or report["source_hashes"] != hashes
                or report["union_count"] != len(union)
                or report["intersection_count"] != len(intersection)
                or report["headwords"] != sorted(union)):
            raise ValueError(f"independent agent result mismatch: {exam}")
        results.append({"exam": exam, "status": "matched",
                        "report_sha256": audit.sha256(directory / f"{exam}.json")})
    return results


def merge(manifest_path, source_dir, output, agent_reports=None):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest["schema_version"] != 1 or not manifest["sources"]:
        raise ValueError("unsupported or empty inventory")
    sources = sorted(manifest["sources"], key=lambda s: (s["group"], s["exam"]))
    protected = {manifest_path.resolve(), *((source_dir / s["file"]).resolve() for s in sources)}
    if output.resolve() in protected:
        raise ValueError("output must not overwrite an input")
    if output.exists():
        raise ValueError("output already exists; select a new output path")
    books = {}
    for source in sources:
        label = f"{source['group']}:{source['exam']}"
        if label in books:
            raise ValueError(f"duplicate source id: {label}")
        books[label] = source_entries(source, source_dir)
    verified_agents = validate_agents(sources, books, agent_reports) if agent_reports else []
    heads = sorted({row["headword"] for rows in books.values() for row in rows})
    ids = {head: index for index, head in enumerate(heads, 1)}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".exam-merge-", suffix=".sqlite3",
                                     delete=False) as temporary:
        temporary_path = Path(temporary.name)
    db = sqlite3.connect(temporary_path)
    try:
        db.executescript(SCHEMA)
        with db:
            metadata = {
                "schema_version": 1, "inventory_sha256": audit.sha256(manifest_path),
                "source_inventory": manifest,
                "normalization": "NFC+lowercase+curly-apostrophe+whitespace; no lemmatization",
                "status": "local_candidate_not_content_approved",
                "content_review_status": "unreviewed",
                "syllabus_verified": False, "redistribution_status": "unresolved",
                "independent_agents": verified_agents,
            }
            db.executemany("INSERT INTO metadata VALUES (?,?)",
                           [(key, json_text(value)) for key, value in sorted(metadata.items())])
            db.executemany("INSERT INTO words VALUES (?,?,?)", [
                (ids[head], head, "single_word" if audit.WORD.fullmatch(head)
                 else "phrase" if " " in head else "other") for head in heads])
            for source in sources:
                label = f"{source['group']}:{source['exam']}"
                db.execute("INSERT INTO sources VALUES (?,?,?,?,?,?,?,?,?,?)", (
                    label, source["group"], source["exam"], source["book"], source["file"],
                    source["commit"], source["url"], source["sha256"],
                    int(source["syllabus_verified"]), source["redistribution_status"]))
                rows = books[label]
                db.executemany("INSERT OR IGNORE INTO word_exams VALUES (?,?)", sorted({
                    (ids[row["headword"]], source["exam"]) for row in rows}))
                db.executemany("""INSERT INTO source_entries
                    (source_id,entry_index,word_id,original_headword,original_rank,
                     ukphone,usphone,senses_json) VALUES (?,?,?,?,?,?,?,?)""", [
                    (label, row["entry_index"], ids[row["headword"]], row["original_headword"],
                     row["original_rank"], row["ukphone"], row["usphone"], json_text(row["senses"]))
                    for row in rows])
        integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
        foreign_key_errors = db.execute("PRAGMA foreign_key_check").fetchall()
        if integrity != "ok" or foreign_key_errors:
            raise ValueError("database integrity failed")
        counts = dict(db.execute("SELECT exam,COUNT(*) FROM word_exams GROUP BY exam ORDER BY exam"))
        exam_sets = {exam: {word_id for word_id, in db.execute(
            "SELECT word_id FROM word_exams WHERE exam=?", (exam,))} for exam in counts}
        report = {
            "status": "local_candidate_not_content_approved", "unique_headwords": len(heads),
            "word_kinds": dict(db.execute("SELECT kind,COUNT(*) FROM words GROUP BY kind ORDER BY kind")),
            "source_count": db.execute("SELECT COUNT(*) FROM sources").fetchone()[0],
            "source_entry_count": db.execute("SELECT COUNT(*) FROM source_entries").fetchone()[0],
            "domain_counts": counts,
            "domain_overlap": {f"{a}|{b}": len(exam_sets[a] & exam_sets[b])
                               for a, b in itertools.combinations(sorted(counts), 2)},
            "membership_count": db.execute("SELECT COUNT(*) FROM word_exams").fetchone()[0],
            "integrity_check": integrity, "foreign_key_errors": len(foreign_key_errors),
            "independent_agents": verified_agents,
        }
        if (report["source_entry_count"] != sum(len(rows) for rows in books.values())
                or db.execute("SELECT COUNT(*) FROM words").fetchone()[0] != len(heads)):
            raise ValueError("database row count mismatch")
        db.close()
        # Do not replace an existing user database, including one created while compiling.
        os.link(temporary_path, output)
        report["database_sha256"] = audit.sha256(output)
        report["database_bytes"] = output.stat().st_size
        return report
    finally:
        db.close()
        temporary_path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--agent-reports", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        protected = {args.manifest.resolve(), args.output.resolve(),
                     *((args.source_dir / s["file"]).resolve() for s in manifest["sources"]),
                     *((args.agent_reports / f"{s['exam']}.json").resolve() for s in manifest["sources"])}
        if args.report.resolve() in protected or args.report.exists():
            raise ValueError("report must be a new file distinct from all inputs/database")
        report = merge(args.manifest, args.source_dir, args.output, args.agent_reports)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("x", encoding="utf-8") as stream:
            stream.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    except (ValueError, KeyError, OSError, sqlite3.Error, zipfile.BadZipFile) as error:
        parser.exit(2, f"merge rejected: {error}\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
