# Minimal Dictionary — Parser / Data Pipeline

Builds the offline dictionary database for the Minimal Dictionary Android app
from English Wiktionary data.

## Pipeline (current)

```
kaikki.org English extract (.jsonl.gz, ~470 MB)
        │
        ▼  kaikki_to_ir.py          (thin to the minimal IR)
dictionary_ir.jsonl (~600 MB, 1.47M entries)
        │
        ▼  build_db.py              (serialize into SQLite)
dictionary.db                       (shipped inside the Android app)
```

### 1. Source data

We do **not** parse the raw Wiktionary XML dump ourselves. Template/Lua
expansion of wikitext is a huge problem already solved by
[wiktextract](https://github.com/tatuylonen/wiktextract); its output for
English is published as JSONL at
[kaikki.org/dictionary/English](https://kaikki.org/dictionary/English/):

```
curl -O https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz
```

One JSON object per (word, part-of-speech). Definitions, IPA, etymology,
examples, inflected forms and cross-links are already structured and free of
wiki markup.

### 2. Thin to the intermediate representation

```
python3 kaikki_to_ir.py kaikki-english.jsonl.gz dictionary_ir.jsonl
```

Keeps only what the app renders, discarding translations, descendants,
hyponyms, categories, audio URLs, conjugation-table scaffolding, etc.
Per entry:

- `word`, `pos` (includes multi-word entries: idioms, phrases, proverbs)
- `senses` — a **nested tree**: each sense has `definition` and optional
  `tags`, `examples` (max 1 plain usage sentence; literary quotations are
  excluded), `subsenses`
- word linking:
  - `form_of` — this sense is a form of another lemma (`moduli` → `modulus`)
  - `links` — `[display_text, target_word]` pairs for terms inside a
    definition, so the app can make them tappable
- `pronunciations` — IPA with accent tags
- `etymology` — prose only (machine-generated "Etymology tree" blocks are
  stripped)
- `forms` — inflected/alternative forms with grammatical tags

The IR is a plain JSONL file so it can be inspected, diffed and re-serialized
without re-downloading or re-parsing anything.

### 3. Serialize to SQLite

```
python3 build_db.py dictionary_ir.jsonl dictionary.db
```

The IR keeps everything; this step is where "minimal" happens. Three size
reductions are applied while serializing (see `build_db.py` docstring):

1. entries whose every sense is just "form of X" (~36% of all entries,
   e.g. `ran` → "simple past of run") collapse into `redirects` rows
2. trivial in-definition links (single-word, target == displayed word) are
   dropped — the app makes **every** definition word tappable and resolves
   it through the normal lookup; kept are multi-word targets (a tap on one
   word can't reconstruct "absolute value") and inflected displays
   ("Yarns" → "yarn")
3. etymology is dropped (V1 decision — the IR still carries it, so a later
   version can restore it by just rebuilding the DB)
4. the forms list is dropped from entry JSON (the entry screen doesn't list
   inflections); forms exist only as `redirects` lookup rows
5. each entry's JSON blob is zlib-deflated (Android inflates natively with
   `java.util.zip.Inflater`)

Schema (v2):

- `words(id, word, word_lc)` — one row per distinct headword, exact case
  preserved; `word_lc` is indexed for case-insensitive and prefix search
- `entries(id, word_id, pos, data)` — `data` is the entry's IR as
  zlib-compressed JSON; one query renders a whole entry, new fields need
  no migration
- `redirects(form_lc, target, label)` — keyed by lowercase form only;
  `label` is the relationship ("simple past of run"); `''` means a plain
  inflection with no page of its own (`kicks the bucket` → `kick the
  bucket`)
- `meta` — schema version, blob compression, source, license, counts

App lookup flow:

1. exact: `SELECT id FROM words WHERE word_lc = lower(?)` → entries
   (inflate `data`)
2. also: `SELECT target, label FROM redirects WHERE form_lc = lower(?)` —
   render as "simple past of **run**", tap opens the target
3. search-as-you-type: `SELECT word FROM words WHERE word_lc GLOB ? || '*'
   ORDER BY length(word), word LIMIT 25`
   (for 1-character prefixes drop the `ORDER BY` — sorting ~100K matches by
   length costs >100 ms; plain index order is instant)
4. tapped definition word: repeat from step 1 with that word

## Licensing

Wiktionary text is dual-licensed **CC BY-SA 4.0** and **GFDL**. The app must
show attribution (the exact text is stored in the DB's `meta` table under
`license`).

## History

The first approach parsed the raw `enwiktionary.xml` dump (11 GB) directly:
stream the XML (`xml_to_json.py`), filter to namespace 0, filter to pages
with an `==English==` section, and slice out the English section
(`english_words_to_english_only.py`). That work is kept in the repo for
reference, but it stops at the hard part — expanding wikitext templates —
which is exactly what wiktextract/kaikki already does better.
