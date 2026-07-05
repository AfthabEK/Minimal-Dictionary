"""
build_db.py

Serialize the Minimal Dictionary IR into the SQLite database shipped
(as a first-run download) with the Android app.

Input : dictionary_ir.jsonl   (produced by kaikki_to_ir.py)
Output: dictionary.db         (SQLite)

The IR keeps everything; this step is where "minimal" happens. Three
size reductions are applied while serializing:

1. Entries whose every sense is just "form of X" (e.g. ran -> "simple past
   of run") are collapsed into `redirects` rows instead of full JSON
   entries. ~36% of all entries are such pure redirects.
2. Trivial in-definition links (target word == displayed word) are
   dropped: the app makes every definition word tappable and resolves it
   through the normal lookup, so storing those pairs adds nothing. Links
   whose target differs from the display text (multi-word targets like
   "absolute value", inflected displays like "Yarns" -> "yarn") are kept.
3. Etymology is dropped entirely (V1 decision; the IR still carries it,
   so a future version can re-add it by rebuilding the DB).
4. The forms list is dropped from entry JSON (the entry screen does not
   list inflections); forms exist only as `redirects` lookup rows.
5. Each entry's JSON blob is zlib-compressed (Android inflates natively
   with java.util.zip.Inflater).

Schema
------
words     one row per distinct headword string, exact case preserved
          ("Polish" and "polish" differ). word_lc is the lowercase twin
          used for case-insensitive lookup and prefix search. A word may
          have entries, redirects, or both.

entries   one row per (word, part-of-speech). `data` is the zlib-deflated
          JSON of everything the entry screen renders: senses tree
          (definitions, examples, tags, links, form_of), pronunciations,
          etymology, forms. New JSON fields need no migration.

redirects form_lc -> target headword. Keyed by lowercase form only (the
          app queries lowercase and displays the target, so the original
          case would be dead weight). `label` is the human-readable
          relationship ("simple past of run"); '' means a plain inflection
          harvested from the lemma's forms list ("kicks the bucket" ->
          "kick the bucket") and the app renders "form of <target>".

meta      key/value: schema version, source, license attribution, counts.

App lookup flow
---------------
1. SELECT id FROM words WHERE word_lc = lower(:q)
   -> SELECT pos, data FROM entries WHERE word_id = :id   (inflate data)
2. also SELECT target, label FROM redirects WHERE form_lc = lower(:q)
   (show alongside/instead: "simple past of run" -> tap opens "run")
3. suggestions: SELECT word FROM words WHERE word_lc GLOB :prefix || '*'
   LIMIT 25   (add ORDER BY length(word), word only for prefixes >= 2
   chars; sorting ~100K single-letter matches costs >100 ms)
4. tapped definition word: repeat from step 1 with that word.
"""

import json
import sqlite3
import sys
import time
import zlib

SCHEMA = """
CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE words (
    id      INTEGER PRIMARY KEY,
    word    TEXT NOT NULL UNIQUE,
    word_lc TEXT NOT NULL
);

CREATE TABLE entries (
    id      INTEGER PRIMARY KEY,
    word_id INTEGER NOT NULL REFERENCES words(id),
    pos     TEXT NOT NULL,
    data    BLOB NOT NULL
);

CREATE TABLE redirects (
    form_lc TEXT NOT NULL,
    target  TEXT NOT NULL,
    label   TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (form_lc, target, label)
) WITHOUT ROWID;
"""

INDEXES = """
CREATE INDEX idx_words_lc     ON words(word_lc);
CREATE INDEX idx_entries_word ON entries(word_id);
"""

ATTRIBUTION = (
    "Definitions from Wiktionary (en.wiktionary.org), parsed by "
    "wiktextract/kaikki.org. Text is dual-licensed under CC BY-SA 4.0 "
    "and GFDL; see https://en.wiktionary.org/wiki/Wiktionary:Copyrights"
)


def is_pure_redirect(senses):
    """True if every sense is a bare form-of pointer with no sub-senses."""
    return all("form_of" in s and not s.get("subsenses") for s in senses)


def strip_trivial_links(senses):
    """Remove links whose target is exactly the displayed text (or its
    lowercase); the app resolves those by tapping the word itself.
    Multi-word targets are always kept — a tap on one word cannot
    reconstruct "absolute value"."""
    for s in senses:
        links = [
            [d, t] for d, t in s.get("links", [])
            if " " in t or (t != d and t != d.lower())
        ]
        if links:
            s["links"] = links
        else:
            s.pop("links", None)
        strip_trivial_links(s.get("subsenses", []))


def build(ir_path, db_path):
    started = time.time()
    db = sqlite3.connect(db_path)
    db.executescript("""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous  = OFF;
        PRAGMA cache_size   = -200000;   -- ~200 MB page cache for the build
    """)
    db.executescript(SCHEMA)

    word_ids = {}          # exact headword -> words.id
    entry_count = 0
    collapsed = 0

    def word_id_for(word):
        wid = word_ids.get(word)
        if wid is None:
            wid = len(word_ids) + 1
            word_ids[word] = wid
            db.execute(
                "INSERT INTO words (id, word, word_lc) VALUES (?, ?, ?)",
                (wid, word, word.lower()),
            )
        return wid

    # Staging for plain-inflection redirects harvested from lemmas' forms
    # lists; resolved at the end (only forms with no page of their own).
    db.execute("CREATE TABLE form_stage (form TEXT NOT NULL, target TEXT NOT NULL)")

    with open(ir_path, encoding="utf-8") as infile:
        for line in infile:
            rec = json.loads(line)
            word, pos = rec.pop("word"), rec.pop("pos")
            senses = rec["senses"]
            word_id = word_id_for(word)

            # Reduction 1: pure form-of entries become redirect rows.
            if is_pure_redirect(senses):
                for s in senses:
                    for target in s["form_of"]:
                        db.execute(
                            "INSERT OR IGNORE INTO redirects "
                            "(form_lc, target, label) VALUES (?, ?, ?)",
                            (word.lower(), target, s["definition"]),
                        )
                collapsed += 1
                continue

            # Reduction 2: drop links the app can resolve by itself.
            strip_trivial_links(senses)

            # Reduction 3: no etymology in V1.
            rec.pop("etymology", None)

            # Reduction 4: forms live ONLY in the redirects lookup table;
            # the entry screen does not list inflections, so the JSON copy
            # is dead weight. Harvest for redirects, then drop.
            for f in rec.pop("forms", []):
                if f["form"] != word:
                    db.execute(
                        "INSERT INTO form_stage (form, target) VALUES (?, ?)",
                        (f["form"], word),
                    )

            # Reduction 5: zlib-deflate the JSON blob.
            blob = zlib.compress(
                json.dumps(rec, ensure_ascii=False,
                           separators=(",", ":")).encode("utf-8"), 9)
            db.execute(
                "INSERT INTO entries (word_id, pos, data) VALUES (?, ?, ?)",
                (word_id, pos, blob),
            )
            entry_count += 1

            if (entry_count + collapsed) % 100000 == 0:
                print(f"  {entry_count + collapsed:,} records...",
                      file=sys.stderr)

    print("Resolving plain-inflection redirects...", file=sys.stderr)
    db.execute("""
        INSERT OR IGNORE INTO redirects (form_lc, target, label)
        SELECT DISTINCT lower(form), target, ''
        FROM form_stage
        WHERE form NOT IN (SELECT word FROM words)
    """)
    db.execute("DROP TABLE form_stage")
    redirect_rows = db.execute("SELECT count(*) FROM redirects").fetchone()[0]

    print("Creating indexes...", file=sys.stderr)
    db.executescript(INDEXES)

    meta = {
        "schema_version": "2",
        "blob_compression": "zlib",
        "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": "kaikki.org English extract (wiktextract) of en.wiktionary.org",
        "license": ATTRIBUTION,
        "word_count": str(len(word_ids)),
        "entry_count": str(entry_count),
        "collapsed_form_entries": str(collapsed),
        "redirect_count": str(redirect_rows),
    }
    db.executemany("INSERT INTO meta (key, value) VALUES (?, ?)", meta.items())

    db.commit()
    print("ANALYZE + VACUUM...", file=sys.stderr)
    db.execute("ANALYZE")
    db.execute("VACUUM")
    db.close()

    mins = (time.time() - started) / 60
    print(f"Done in {mins:.1f} min: {len(word_ids):,} words, "
          f"{entry_count:,} entries ({collapsed:,} collapsed to redirects), "
          f"{redirect_rows:,} redirect rows", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python build_db.py <dictionary_ir.jsonl> <dictionary.db>",
              file=sys.stderr)
        sys.exit(1)
    build(sys.argv[1], sys.argv[2])
