"""Partition pinned kaoyan senses and validate evidence-bearing AI review results."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import tempfile
from urllib.parse import urlparse

import audit_exam_sources as audit
import merge_exam_dictionary as merger

COMMIT = "3992bcb94c800a2fd38a9fd6ff95b2353e755363"
REFERENCE_HOSTS = {"www.oxfordlearnersdictionaries.com", "oxfordlearnersdictionaries.com",
                   "www.oxfordlearnersdictionaries.co.uk", "oxfordlearnersdictionaries.co.uk",
                   "dictionary.cambridge.org", "www.merriam-webster.com", "www.collinsdictionary.com",
                   "api.collinsdictionary.com", "www.dictionary.com"}
STATUSES = {"supported", "needs_revision", "unresolved"}


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def prepare(manifest_path, source_dir, output_dir):
    if output_dir.exists():
        raise ValueError("review directory exists; do not overwrite a running review")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    sources = [s for s in manifest["sources"] if s["group"] == "kajweb" and s["exam"] == "ky"]
    if manifest["schema_version"] != 1 or len(sources) != 1 or sources[0]["commit"] != COMMIT:
        raise ValueError("exactly one pinned kajweb kaoyan source required")
    source = sources[0]
    rows = merger.source_entries(source, source_dir)
    if len({r["headword"] for r in rows}) != len(rows):
        raise ValueError("duplicate kaoyan headword requires separate source-sense grouping")
    records = sorted(rows, key=lambda r: r["headword"])
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".meaning-review-", dir=output_dir.parent) as temporary:
        stage = Path(temporary)
        (stage / "shards").mkdir()
        (stage / "results").mkdir()
        inventory = {"schema_version": 1, "source": source, "entry_count": len(records),
                     "model": "gpt-6-luna", "reasoning_effort": "medium", "shards": []}
        for index in range(10):
            shard_id = f"{index + 1:02d}"
            entries = records[index::10]
            data = {"schema_version": 1, "shard_id": shard_id, "source": source, "entries": entries}
            payload = json_bytes(data)
            filename = f"shard-{shard_id}.json"
            (stage / "shards" / filename).write_bytes(payload)
            inventory["shards"].append({"id": shard_id, "file": filename, "entry_count": len(entries),
                                        "sha256": hashlib.sha256(payload).hexdigest()})
        (stage / "inventory.json").write_bytes(json_bytes(inventory))
        stage.rename(output_dir)
    return inventory


def validate_review(record, original):
    if record["headword"] != original["headword"] or record["original_senses"] != original["senses"]:
        raise ValueError("review original differs from assigned source")
    if record["status"] not in STATUSES or not isinstance(record["reason"], str) or not record["reason"].strip():
        raise ValueError("invalid status or empty reason")
    if not isinstance(record["lookup_attempted"], bool):
        raise ValueError("lookup_attempted must be boolean")
    references = record["references"]
    if not isinstance(references, list):
        raise ValueError("references must be a list")
    for reference in references:
        url = urlparse(reference["url"])
        if url.scheme != "https" or url.hostname not in REFERENCE_HOSTS or not url.path.strip("/"):
            raise ValueError("reference must be an observed HTTPS dictionary entry")
        if reference["access"] not in {"full", "snippet"}:
            raise ValueError("reference access must describe actual available content")
        if not all(isinstance(reference[key], str) and reference[key].strip()
                   for key in ("title", "accessed_date", "evidence_summary")):
            raise ValueError("reference evidence is incomplete")
    proposed = record.get("proposed_senses")
    if record["status"] != "unresolved" and (not record["lookup_attempted"] or not references):
        raise ValueError("supported/revision status requires actual lookup and evidence")
    if record["status"] == "needs_revision":
        if not isinstance(proposed, list) or not proposed or proposed == original["senses"]:
            raise ValueError("revision requires a changed proposed sense list")
        if any(not isinstance(s["meaning"], str) or not s["meaning"].strip()
               or (s["pos"] is not None and not isinstance(s["pos"], str)) for s in proposed):
            raise ValueError("invalid proposed sense")
        if not record.get("issues"):
            raise ValueError("revision must explain the issue")
    elif proposed is not None:
        raise ValueError("only needs_revision can propose changed senses")


def summarize(root, require_complete=False):
    inventory = json.loads((root / "inventory.json").read_text(encoding="utf-8"))
    counts, issues, reviewed, pending, patches = Counter(), [], [], [], []
    assigned_heads = set()
    for shard in inventory["shards"]:
        path = root / "shards" / shard["file"]
        if audit.sha256(path) != shard["sha256"]:
            raise ValueError("assignment shard hash changed")
        assignment = json.loads(path.read_text(encoding="utf-8"))
        originals = {r["headword"]: r for r in assignment["entries"]}
        if len(originals) != shard["entry_count"] or assigned_heads.intersection(originals):
            raise ValueError("duplicate/miscounted assignments")
        assigned_heads.update(originals)
        result_path = root / "results" / f"shard-{shard['id']}.jsonl"
        seen = set()
        if result_path.exists():
            for line_number, line in enumerate(result_path.read_text(encoding="utf-8").splitlines(), 1):
                if not line.strip():
                    continue
                record = json.loads(line)
                head = record["headword"]
                if head not in originals or head in seen:
                    raise ValueError(f"unknown/duplicate review: shard {shard['id']} row {line_number}")
                validate_review(record, originals[head])
                seen.add(head)
                counts[record["status"]] += 1
                reviewed.append(record)
                if record["status"] == "needs_revision":
                    patches.append(record)
                if record["status"] != "supported":
                    issues.append({"headword": head, "status": record["status"], "reason": record["reason"]})
        pending.extend(sorted(set(originals) - seen))
    if len(assigned_heads) != inventory["entry_count"]:
        raise ValueError("assignment does not cover inventory")
    if require_complete and pending:
        raise ValueError(f"{len(pending)} assigned words have no review record")
    return {"schema_version": 1, "source": inventory["source"], "assigned_count": len(assigned_heads),
            "review_record_count": len(reviewed), "pending_count": len(pending), "status_counts": dict(counts),
            "lookup_attempted_count": sum(r["lookup_attempted"] for r in reviewed),
            "not_attempted_count": sum(not r["lookup_attempted"] for r in reviewed),
            "external_evidence_count": sum(bool(r["references"]) for r in reviewed),
            "pending_headwords": sorted(pending), "issues": sorted(issues, key=lambda r: r["headword"]),
            "proposed_patches": sorted(patches, key=lambda r: r["headword"]),
            "status": "ai_review_not_professional_certification", "patches_applied": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    p = subparsers.add_parser("prepare")
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--source-dir", type=Path, required=True)
    p.add_argument("--output-dir", type=Path, required=True)
    p = subparsers.add_parser("summarize")
    p.add_argument("--review-dir", type=Path, required=True)
    p.add_argument("--require-complete", action="store_true")
    p.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.manifest, args.source_dir, args.output_dir)
            print(json.dumps({"entry_count": result["entry_count"], "shards": result["shards"]}, indent=2))
        else:
            result = summarize(args.review_dir, args.require_complete)
            with args.output.open("xb") as stream:
                stream.write(json_bytes(result))
            print(json.dumps({key: result[key] for key in ("assigned_count", "review_record_count", "lookup_attempted_count", "external_evidence_count",
                         "not_attempted_count", "pending_count", "status_counts")}, indent=2))
    except (ValueError, KeyError, OSError) as error:
        parser.exit(2, f"review rejected: {error}\n")


if __name__ == "__main__":
    main()
