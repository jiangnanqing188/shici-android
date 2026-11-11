#!/usr/bin/env python3
"""Build the complete pinned ECDICT as an independent read-only Android asset.

No reviewed overrides, suffix guessing, network access or learning database writes.
All CSV records and original fields are retained, including unindexable headwords.
"""
import argparse
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unicodedata

COMMIT = 'bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b'
CSV_SHA256 = '1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf'
SOURCE = 'ECDICT · skywind3000/ECDICT'
KINDS = {'p', 'd', 'i', '3', 'r', 't', 's'}


def normalize(text):
    value = ' '.join(unicodedata.normalize('NFC', text).lower().replace('’', "'").replace('‘', "'").split())
    if not value or len(value) > 256 or not any(c.isalpha() for c in value):
        return None
    if any(unicodedata.category(c).startswith('C') for c in value):
        return None
    return value


def exchange_parts(value):
    parts = {}
    for token in value.split('/'):
        kind, sep, forms = token.partition(':')
        if sep and kind in KINDS | {'0', '1'} and kind not in parts:
            parts[kind] = forms
    return parts


def build(csv_path, output, license_path):
    digest = hashlib.sha256(csv_path.read_bytes()).hexdigest()
    if digest != CSV_SHA256:
        raise ValueError('Expected the pinned unmodified ECDICT CSV')
    license_text = license_path.read_text(encoding='utf-8')
    if 'MIT' not in license_text or 'Permission is hereby granted' not in license_text:
        raise ValueError('The upstream MIT license is required')
    output.mkdir(parents=True, exist_ok=True)
    counts = Counter()
    # Keep the old usable asset until the complete replacement has been committed and checked.
    with tempfile.TemporaryDirectory(prefix='.ecdict-build-', dir=output) as tmp:
        path = Path(tmp) / 'ecdict.sqlite3'
        db = sqlite3.connect(path)
        db.executescript('''
            PRAGMA page_size=4096;
            PRAGMA user_version=1;
            CREATE TABLE records(id INTEGER PRIMARY KEY, key TEXT, word TEXT NOT NULL,
              phonetic TEXT NOT NULL, definition TEXT NOT NULL, translation TEXT NOT NULL,
              pos TEXT NOT NULL, exchange TEXT NOT NULL, extra_json TEXT NOT NULL);
            CREATE TABLE forms(form TEXT NOT NULL, lemma TEXT NOT NULL, kinds TEXT NOT NULL,
              PRIMARY KEY(form,lemma)) WITHOUT ROWID;
            CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID;
        ''')
        with csv_path.open(encoding='utf-8', newline='') as stream:
            for number, row in enumerate(csv.DictReader(stream), 1):
                key = normalize(row['word'])
                db.execute('INSERT INTO records VALUES (?,?,?,?,?,?,?,?,?)',
                           (number, key, row['word'], row['phonetic'], row['definition'],
                            row['translation'], row['pos'], row['exchange'],
                            json.dumps([row[k] for k in ('collins', 'oxford', 'tag', 'bnc', 'frq', 'detail', 'audio')], ensure_ascii=False, separators=(',', ':'))))
                counts['record_count'] += 1
                if key is None:
                    counts['unindexable_records'] += 1
                if not row['translation'].strip():
                    counts['empty_translation_records'] += 1
        db.execute('CREATE INDEX records_key ON records(key)')
        db.commit()
        entries = {key: exchange_parts(ex) for key, ex in
                   db.execute("SELECT key,exchange FROM records WHERE key IS NOT NULL AND exchange!='' ORDER BY id")}
        relations = {}
        for key, parts in entries.items():
            parent = normalize(parts.get('0', ''))
            # A derived row may repeat inflections (children -> childrens). Do not treat it as
            # another lemma. Self references are discarded; no transitive resolution is attempted.
            if parent == key:
                counts['self_relations_rejected'] += 1
            if parent and parent != key:
                base_parent = normalize(entries.get(parent, {}).get('0', ''))
                if base_parent and base_parent != parent:
                    counts['chained_or_cyclic_relations_rejected'] += 1
                elif db.execute('SELECT 1 FROM records WHERE key=? LIMIT 1', (parent,)).fetchone():
                    kinds = ''.join(sorted(set(parts.get('1', '')) & KINDS))
                    if kinds:
                        relations.setdefault((key, parent), set()).update(kinds)
                continue
            for kind in sorted(KINDS & parts.keys()):
                for raw in parts[kind].split(','):
                    form = normalize(raw)
                    if not form or form == key:
                        counts['invalid_or_self_forms_rejected'] += 1
                        continue
                    if ' ' in form or not any(c.isalpha() for c in form):
                        counts['invalid_or_self_forms_rejected'] += 1
                        continue
                    relations.setdefault((form, key), set()).add(kind)
        db.executemany('INSERT INTO forms VALUES (?,?,?)',
                       ((form, lemma, ''.join(sorted(kinds))) for (form, lemma), kinds in sorted(relations.items())))
        counts['entry_count'] = db.execute('SELECT count(DISTINCT key) FROM records').fetchone()[0]
        counts['indexed_records'] = counts['record_count'] - counts['unindexable_records']
        counts['alias_count'] = len(relations)
        if counts['record_count'] != 770611:
            raise ValueError('Pinned CSV record count mismatch')
        metadata = {'format': 'SHICI_ECDICT_SQLITE', 'format_version': 1, 'source': SOURCE,
                    'version': COMMIT, 'source_sha256': digest, **counts,
                    'content_review_status': 'unreviewed', 'syllabus_verified': False,
                    'extra_fields': ['collins', 'oxford', 'tag', 'bnc', 'frq', 'detail', 'audio'],
                    'forms_policy': 'one-hop documented exchange; no suffix guessing; derived forward edges rejected'}
        db.executemany('INSERT INTO metadata VALUES (?,?)', ((k, str(v)) for k, v in metadata.items()))
        db.commit()
        if db.execute('PRAGMA quick_check').fetchall() != [('ok',)]:
            raise ValueError('SQLite integrity check failed')
        db.close()
        metadata['asset_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        metadata['asset_bytes'] = path.stat().st_size
        path.replace(output / 'ecdict.sqlite3')
        (output / 'ecdict-manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        (output / 'ECDICT-LICENSE.txt').write_text(license_text, encoding='utf-8')
        print(json.dumps(metadata, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--csv', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--license', type=Path, required=True)
    args = parser.parse_args()
    build(args.csv, args.output, args.license)
