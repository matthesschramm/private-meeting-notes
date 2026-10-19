#!/usr/bin/env python3
"""Writes test-data/retrieval/launch-plan.pdf from sources/launch-plan.txt: one PDF page per "Page N:" section,
as real text in the standard Helvetica font, so the app's PDF text extraction and page citations can be tested.
Also writes annual-report.pdf: 100 pages of generated operations reporting with one fact on page 87 that a test
question asks about, to check that a long PDF indexes and that its pages are found.
"""
import random
import textwrap
from pathlib import Path

FOLDER = Path(__file__).resolve().parent.parent / "test-data" / "retrieval"
LONG_PAGES = 100
FACT_PAGE = 87
FACT = ("The lease on the Geelong warehouse ends on 31 March 2027. The renewal option has to be exercised by "
        "30 September 2026, and the landlord has offered a five-year term at the current rent plus three percent.")


def escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def write_pdf(pages: list, path: Path) -> None:
    objects = ["<< /Type /Catalog /Pages 2 0 R >>", None, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    page_ids = []
    for lines in pages:
        body = "BT /F1 11 Tf 15 TL 60 780 Td " + " ".join(f"({escape(line)}) Tj T*" for line in lines) + " ET"
        objects.append(f"<< /Length {len(body.encode())} >>\nstream\n{body}\nendstream")
        content_id = len(objects)
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents {content_id} 0 R "
                       f"/Resources << /Font << /F1 3 0 R >> >> >>")
        page_ids.append(len(objects))
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(f'{i} 0 R' for i in page_ids)}] /Count {len(page_ids)} >>"
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n{obj}\nendobj\n".encode()
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    out += "".join(f"{o:010d} 00000 n \n" for o in offsets).encode()
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    path.write_bytes(bytes(out))


def report_pages() -> list:
    """Deterministic filler about teams, sites and figures; similar enough to the fact to make retrieval work for it."""
    rng = random.Random(87)
    teams = ["finance", "logistics", "customer support", "engineering", "sales", "facilities", "people and culture"]
    sites = ["Ballarat office", "Bendigo depot", "Docklands office", "Dandenong workshop", "Shepparton depot"]
    months = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
              "November", "December"]
    templates = [
        "In {month}, the {team} team at the {site} closed {n} of its {m} open tickets.",
        "The {site} reported {n} hours of planned maintenance and {m} hours of unplanned downtime in {month}.",
        "Spending by the {team} team in {month} was {n} thousand dollars against a budget of {m} thousand.",
        "The {team} team hired {n} people in {month}, and {m} roles remain open across the business.",
        "Energy use at the {site} fell by {n} percent compared with {month} last year.",
        "The {site} lease was reviewed in {month}; no change to the term of {n} years was recommended.",
        "Customer satisfaction for the {team} team was {n} out of 100 in {month}, up from {m}.",
    ]
    pages = []
    for number in range(1, LONG_PAGES + 1):
        paragraphs = [f"Operations report 2026, page {number}"]
        if number == FACT_PAGE:
            paragraphs.append(FACT)
        for _ in range(12):
            paragraphs.append(rng.choice(templates).format(
                month=rng.choice(months), team=rng.choice(teams), site=rng.choice(sites),
                n=rng.randint(2, 90), m=rng.randint(10, 120)))
        lines = []
        for paragraph in paragraphs:
            lines += textwrap.wrap(paragraph, 85) + [""]
        pages.append(lines)
    return pages


def main() -> None:
    write_pdf(report_pages(), FOLDER / "annual-report.pdf")
    print(f"Wrote annual-report.pdf with {LONG_PAGES} pages")
    text = (FOLDER / "sources" / "launch-plan.txt").read_text()
    sections = [s.strip() for s in text.split("\nPage ")]
    pages = []
    for i, section in enumerate(sections):
        section = section if i == 0 else "Page " + section
        lines = []
        for paragraph in section.split("\n"):
            lines += textwrap.wrap(paragraph, 85) or [""]
        pages.append(lines)
    # The title belongs on the first page with the overview.
    pages = [pages[0] + [""] + pages[1]] + pages[2:]
    write_pdf(pages, FOLDER / "launch-plan.pdf")
    print(f"Wrote launch-plan.pdf with {len(pages)} pages")


if __name__ == "__main__":
    main()
