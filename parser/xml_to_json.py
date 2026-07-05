from lxml import etree
import json

INPUT_FILE = "enwiktionary.xml"
OUTPUT_FILE = "pages.jsonl"

NS = "{http://www.mediawiki.org/xml/export-0.11/}"

count = 0

with open(OUTPUT_FILE, "w", encoding="utf-8") as outfile:

    context = etree.iterparse(
        INPUT_FILE,
        events=("end",),
        tag=f"{NS}page"
    )

    for _, page in context:

        title = page.findtext(f"{NS}title")
        ns = page.findtext(f"{NS}ns")
        page_id = page.findtext(f"{NS}id")

        revision = page.find(f"{NS}revision")

        text = ""
        if revision is not None:
            text_elem = revision.find(f"{NS}text")
            if text_elem is not None and text_elem.text:
                text = text_elem.text

        record = {
            "page_id": int(page_id) if page_id else None,
            "title": title,
            "ns": int(ns) if ns else None,
            "text": text
        }

        outfile.write(
            json.dumps(record, ensure_ascii=False)
        )
        outfile.write("\n")

        count += 1

        if count % 10000 == 0:
            print(f"Processed {count:,} pages")

        page.clear()

print(f"\nFinished. Total pages: {count:,}")