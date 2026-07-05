"""
kaikki_to_ir.py

Thin the Kaikki English Wiktionary extract down to the Minimal Dictionary
intermediate representation (IR).

Input : Kaikki English JSONL  (one word+POS object per line)
        https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz
Output: minimal IR JSONL       (one word+POS object per line)

We keep ONLY what V1 needs:
  word, pos, pronunciation (IPA), etymology, senses (definitions + examples),
  inflected/alternative forms, and word-linking data (form_of + in-definition links).

Everything else in the Kaikki record (translations, descendants, hyponyms,
categories, audio files, wikipedia, conjugation tables, ...) is discarded.
Those can be re-introduced in a later version without changing this design.
"""

import gzip
import json
import sys

# A minimal dictionary wants one short usage sentence per definition.
# Literary quotations (dated book/newspaper citations) are excluded
# entirely; among plain examples the shortest-reasonable one wins.
MAX_EXAMPLES_PER_SENSE = 1
EXAMPLE_LENGTH_SOFT_CAP = 220

# Form entries in Kaikki carry noisy bookkeeping rows from conjugation tables.
# We drop any form whose tags mark it as table scaffolding rather than a real word.
_FORM_NOISE_TAGS = {"table-tags", "inflection-template"}
_FORM_NOISE_WORDS = {"no-table-tags", "glossary"}


def clean_forms(raw_forms):
    """Keep only real inflected/alternative word-forms, de-duplicated."""
    seen = set()
    out = []
    for f in raw_forms or []:
        form = f.get("form")
        if not form or form in _FORM_NOISE_WORDS:
            continue
        tags = f.get("tags") or []
        if _FORM_NOISE_TAGS.intersection(tags):
            continue
        key = (form, tuple(tags))
        if key in seen:
            continue
        seen.add(key)
        out.append({"form": form, "tags": tags})
    return out


def clean_ipa(sounds):
    """Extract distinct IPA strings, preserving accent tags where present."""
    out = []
    seen = set()
    for s in sounds or []:
        ipa = s.get("ipa")
        if not ipa or ipa in seen:
            continue
        seen.add(ipa)
        entry = {"ipa": ipa}
        if s.get("tags"):
            entry["accents"] = s["tags"]
        out.append(entry)
    return out


def clean_etymology(text, word):
    """Drop Kaikki's machine-generated 'Etymology tree' block, keep the prose.

    For some entries etymology_text is prefixed with an ancestry tree, one node
    per line, terminating at the entry's own 'English <word>' node; the readable
    sentence(s) follow that node. For most entries the text is already clean
    prose and is returned unchanged.
    """
    if not text:
        return None
    lines = text.split("\n")
    if lines[0].strip() == "Etymology tree":
        anchor = f"English {word}"
        cut = None
        for i, ln in enumerate(lines):
            if ln.strip() == anchor:
                cut = i  # last matching node is where the tree ends
        if cut is not None:
            prose = "\n".join(lines[cut + 1:]).strip()
            return prose or None
        # Fallback: tree present but anchor not found -> keep the last real line,
        # which is almost always the actual explanatory sentence.
        nonempty = [ln.strip() for ln in lines[1:] if ln.strip()]
        return nonempty[-1] if nonempty else None
    return text.strip() or None


def pick_examples(raw_examples):
    """Choose up to MAX_EXAMPLES_PER_SENSE plain usage sentences.

    Quotations (literary citations) are excluded entirely; among the rest,
    short sentences are preferred over long ones.
    """
    candidates = [
        ex for ex in raw_examples or []
        if ex.get("text") and ex.get("type") != "quotation"
    ]
    candidates.sort(key=lambda ex: len(ex["text"]) > EXAMPLE_LENGTH_SOFT_CAP)
    return [ex["text"] for ex in candidates[:MAX_EXAMPLES_PER_SENSE]]


def sense_details(sense):
    """Display fields for ONE sense node (its own definition is set elsewhere)."""
    d = {}
    if sense.get("tags"):
        d["tags"] = sense["tags"]

    examples = pick_examples(sense.get("examples"))
    if examples:
        d["examples"] = examples

    # Word-linking (a): whole sense is a form of another lemma (moduli -> modulus)
    form_of = [f["word"] for f in (sense.get("form_of") or []) if f.get("word")]
    if form_of:
        d["form_of"] = form_of

    # Word-linking (b): terms inside the definition that link to other entries.
    # Kaikki gives [display_text, target#Section]; keep [display, target_word].
    links = []
    for pair in sense.get("links") or []:
        if len(pair) >= 2 and pair[0] and pair[1]:
            links.append([pair[0], pair[1].split("#", 1)[0]])
    if links:
        d["links"] = links
    return d


def build_senses(raw_senses):
    """Turn Kaikki's flat sense list into a real nested tree.

    Kaikki encodes hierarchy in `glosses`: ["parent", "child"] means this sense
    is a sub-sense whose parent gloss is "parent". We rebuild that tree so each
    node holds only its OWN definition text and a `subsenses` list, instead of
    repeating the parent's words in the child.
    """
    nodes = {}   # path (tuple of glosses) -> node
    roots = []
    for sense in raw_senses:
        glosses = sense.get("glosses")
        if not glosses:
            continue
        path = tuple(glosses)
        # Make sure every ancestor node on this path exists.
        for depth in range(1, len(path) + 1):
            sub = path[:depth]
            if sub not in nodes:
                node = {"definition": sub[-1]}
                nodes[sub] = node
                parent = sub[:-1]
                if parent:
                    nodes[parent].setdefault("subsenses", []).append(node)
                else:
                    roots.append(node)
        # Attach this sense's own details (examples/links/tags) to its leaf node.
        nodes[path].update(sense_details(sense))

    # Kaikki repeats the parent's in-definition links on every child (the
    # child's gloss chain includes the parent text). Drop from each child any
    # link already present on an ancestor, so links appear where they belong.
    def prune_links(node, inherited):
        own = [l for l in node.get("links", []) if tuple(l) not in inherited]
        if own:
            node["links"] = own
        else:
            node.pop("links", None)
        child_inherited = inherited | {tuple(l) for l in own}
        for child in node.get("subsenses", []):
            prune_links(child, child_inherited)

    for root in roots:
        prune_links(root, set())
    return roots


def convert(record):
    """Kaikki word+POS record -> minimal IR record (or None if empty)."""
    word = record.get("word")
    pos = record.get("pos")
    if not word or not pos:
        return None

    senses = build_senses(record.get("senses") or [])
    if not senses:
        return None  # e.g. bare form pages with nothing renderable

    ir = {"word": word, "pos": pos, "senses": senses}

    ipa = clean_ipa(record.get("sounds"))
    if ipa:
        ir["pronunciations"] = ipa
    etymology = clean_etymology(record.get("etymology_text"), word)
    if etymology:
        ir["etymology"] = etymology
    forms = clean_forms(record.get("forms"))
    if forms:
        ir["forms"] = forms

    return ir


def open_maybe_gzip(path):
    """Open a .jsonl or .jsonl.gz transparently for text reading."""
    if path.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8")
    return open(path, encoding="utf-8")


def main(in_path, out_path):
    read = written = 0
    with open_maybe_gzip(in_path) as infile, \
         open(out_path, "w", encoding="utf-8") as outfile:
        for line in infile:
            line = line.strip()
            if not line:
                continue
            read += 1
            ir = convert(json.loads(line))
            if ir is None:
                continue
            outfile.write(json.dumps(ir, ensure_ascii=False))
            outfile.write("\n")
            written += 1
            if read % 100000 == 0:
                print(f"Read {read:,}, written {written:,}", file=sys.stderr)
    print(f"Done. Read {read:,}, written {written:,}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python kaikki_to_ir.py <kaikki.jsonl> <out_ir.jsonl>",
              file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2])
