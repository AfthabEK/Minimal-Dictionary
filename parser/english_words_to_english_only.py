import json
import re

INPUT_FILE = "english_words.jsonl"
OUTPUT_FILE = "english_only.jsonl"

LANGUAGE_HEADER = re.compile(r"^==[^=].*[^=]==$")

count = 0
written = 0

with open(INPUT_FILE, encoding="utf-8") as infile, \
     open(OUTPUT_FILE, "w", encoding="utf-8") as outfile:

    for line in infile:

        count += 1

        obj = json.loads(line)

        text = obj["text"]

        lines = text.splitlines()

        english_lines = []
        inside_english = False

        for current_line in lines:

            stripped = current_line.strip()

            if stripped == "==English==":
                inside_english = True
                english_lines.append(current_line)
                continue

            if inside_english and LANGUAGE_HEADER.match(stripped):
                break

            if inside_english:
                english_lines.append(current_line)

        if english_lines:

            out_obj = {
                "page_id": obj["page_id"],
                "title": obj["title"],
                "english_text": "\n".join(english_lines)
            }

            outfile.write(
                json.dumps(out_obj, ensure_ascii=False)
            )
            outfile.write("\n")

            written += 1

        if count % 100000 == 0:
            print(
                f"Processed {count:,}, "
                f"written {written:,}"
            )

print()
print(f"Processed: {count:,}")
print(f"Written:   {written:,}")