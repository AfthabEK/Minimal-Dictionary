# Minimal Dictionary — Android App Plan

Handoff document for building the Android app. The data pipeline is **done**
(see `parser/README.md`); this file tells you everything the app side needs.

## Product

A fast, simple, completely offline English dictionary. Clean and
distraction-free. The reference mockup: dark screen, big headword ("Light"),
one IPA line under it (`/laɪt/`), then definitions grouped by part of speech
("noun", "verb", "adjective" as small gray headers), numbered definitions,
and at most one short italic example under each definition. No etymology,
no forms list, no clutter.

## Tech choices (already decided)

- Kotlin + Jetpack Compose (Empty Activity template), single module
- minSdk 26, Navigation Compose for screens
- No ORM — plain `android.database.sqlite.SQLiteDatabase`, read-only
- JSON parsing: `org.json` or kotlinx-serialization, either is fine
- The database is **downloaded on first run** (~276 MB SQLite, hosted as
  ~175 MB gzip on GitHub Releases; decompress with built-in
  `GZIPInputStream`). It must be excluded from Android auto-backup
  (`dataExtractionRules` — the file is far over the 25 MB backup quota).

## The database (`dictionary.db`, SQLite, schema v2)

Built by `parser/build_db.py` (its docstring is the authoritative schema
reference). Contents: 1,372,266 words, 942,649 entries, 761,231 redirects.

```sql
words    (id INTEGER PK, word TEXT UNIQUE, word_lc TEXT)   -- headwords, exact case ("Polish" ≠ "polish")
entries  (id INTEGER PK, word_id INTEGER, pos TEXT, data BLOB) -- data = zlib-compressed JSON
redirects(form_lc TEXT, target TEXT, label TEXT)            -- inflection/form lookup table
meta     (key TEXT PK, value TEXT)                          -- license attribution in key 'license'
```

`entries.data` inflates (java.util.zip.Inflater) to JSON:

```json
{
  "senses": [
    {
      "definition": "To move swiftly.",
      "tags": ["intransitive"],                 // optional; render as "(intransitive)"
      "examples": ["Run, and you might catch the train!"],  // optional, max 1
      "form_of": ["run"],                        // optional; sense is "form of X"
      "links": [["absolute value", "absolute value"]], // optional; multi-word tap targets
      "subsenses": [ { ...same shape... } ]      // optional; render indented
    }
  ],
  "pronunciations": [ {"ipa": "/ɹʌn/", "accents": ["General-American"]} ]  // optional
}
```

Display the FIRST pronunciation only. Map POS codes for display:
`adj`→adjective, `adv`→adverb, `intj`→interjection, `pron`→pronoun,
`prep_phrase`→prepositional phrase, `name`→proper noun; others as-is
(noun, verb, phrase, proverb, prefix, suffix, ...).

## The four queries (all verified < 1 ms)

```sql
-- 1. lookup: all entries for a word, case-insensitive
SELECT w.word, e.pos, e.data FROM words w JOIN entries e ON e.word_id = w.id
WHERE w.word_lc = lower(?);

-- 2. redirects for the same input (show alongside entries):
--    label is human text ("simple past of run"); label = '' means plain
--    inflection -> render "form of <target>". Tapping opens the target.
SELECT target, label FROM redirects WHERE form_lc = lower(?);

-- 3. search-as-you-type suggestions
SELECT word FROM words WHERE word_lc GLOB ? || '*'
ORDER BY length(word), word LIMIT 25;
-- IMPORTANT: for 1-char prefixes DROP the ORDER BY (sorting ~100K matches
-- costs >100 ms; bare index order is instant). Escape [ * ? in user input.

-- 4. tapped word in a definition: run queries 1+2 with that word.
```

A lookup "misses" only if both 1 and 2 return empty.

## Word linking (core feature)

Every word in a definition text is tappable: tapping runs queries 1+2 on the
tapped word (lowercased) and navigates to it. The stored `links` arrays only
cover cases a single-word tap can't reconstruct (multi-word targets like
"absolute value", display≠target like "Yarns"→"yarn") — underline/prioritize
those spans; for all other words just try the tapped token.

## Screens

1. **SearchScreen** — TextField + LazyColumn of suggestions (query 3).
2. **EntryScreen(word)** — mockup layout. Group entries by POS in the order
   returned. Redirect rows render as e.g. "simple past of **run**" (tap →
   EntryScreen("run")). Sense tags in gray italic parens before the
   definition. Subsenses indented.
3. **First-run DownloadScreen** — progress bar, downloads .gz, streams
   through GZIPInputStream into `context.getDatabasePath("dictionary.db")`,
   verifies by opening + reading `meta.word_count`, then enters the app.
   Build this LAST — during development use a small test DB (below).

## Test database for development

Full DB lives at `parser/dictionary.db` (not in git — rebuildable, see
parser/README.md). For fast dev iterations generate a small one:

```bash
cd parser
head -20000 dictionary_ir.jsonl > /tmp/ir_small.jsonl
python3 build_db.py /tmp/ir_small.jsonl app/src/main/assets/test_dictionary.db
```

(During development it's fine to copy the test DB from assets into place on
startup instead of downloading.)

## Build order

1. Project scaffold + data layer (open DB read-only, inflate blob, the 4
   queries) + crude results list — proves DB→screen.
2. EntryScreen styled to the mockup.
3. SearchScreen + navigation (incl. entry→entry taps).
4. Real first-run downloader + backup exclusion + license/attribution
   screen (attribution text is in the DB: `SELECT value FROM meta WHERE
   key='license'` — showing it is a hard requirement, content is CC BY-SA).
