import xml.etree.ElementTree as ET

INPUT_FILE = "enwiktionary.xml"
OUTPUT_FILE = "sample_10_pages.xml"

root = ET.Element("sample")

count = 0

context = ET.iterparse(INPUT_FILE, events=("end",))

for event, elem in context:
    if not elem.tag.endswith("page"):
        continue

    ns_elem = elem.find("./{*}ns")

    if ns_elem is None or ns_elem.text != "0":
        elem.clear()
        continue

    text_elem = elem.find(".//{*}text")

    if text_elem is None:
        elem.clear()
        continue

    text = text_elem.text or ""

    if "==English==" not in text:
        elem.clear()
        continue

    root.append(elem)

    count += 1

    if count >= 100:
        break

tree = ET.ElementTree(root)
tree.write(
    OUTPUT_FILE,
    encoding="utf-8",
    xml_declaration=True
)

print(f"Wrote {count} pages to {OUTPUT_FILE}")