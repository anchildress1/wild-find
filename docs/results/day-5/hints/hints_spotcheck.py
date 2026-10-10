"""Draw the owner's S72 spot-check: 50 random picked Gemma hints from the tightened run, each beside its quote.

None of the 50 plants from the Oct 10 S56 draw (docs/results/day-4/hints_spotcheck.md) is drawn again. Reads the
picked hints from pipeline/data/hints.json, so it needs no `make assets`; the picks are what the app ships.

Run: uv run --project pipeline python -I docs/results/day-5/hints/hints_spotcheck.py
"""

import json
import random
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SEED = 72
SIZE = 50
OLD = ROOT / "docs/results/day-4/hints_spotcheck.md"


def main() -> None:
    """Write hints_spotcheck.md next to this script."""
    seen = set(re.findall(r"^## \d+\. \*(.+?)\*", OLD.read_text(), re.MULTILINE))
    built = json.loads((ROOT / "pipeline/data/hints.json").read_text())["species"]
    shipped = [
        (row, entry["article"], h)
        for row, entry in sorted(built.items())
        if row not in seen
        for h in entry["hints"]
        # USDA and trait hints are templated from ratings, with no sentence to grade against.
        if h["source"] == "model" and h.get("score") is not None
    ]
    picks = random.Random(SEED).sample(shipped, SIZE)
    lines = [
        "# 🌿 S72 hints spot-check",
        "",
        f"{SIZE} of {len(shipped)} picked Gemma hints, drawn with seed {SEED}, none from the S56 draw's plants.",
        "Grade one thing: does the quoted sentence say what the hint says?",
        "Put `y`, `n`, or `over` (the hint claims more than the sentence) after **Verdict:**.",
        "",
    ]
    for i, (name, article, h) in enumerate(picks, 1):
        lines += [
            f"## {i}. *{name}* ({h['aspect']}, {h['source']})",
            "",
            f"- **Hint:** {h['text']}",
            f"- **Quote:** {h['evidence']}",
            f"- **Article:** https://en.wikipedia.org/wiki/{article.replace(' ', '_')}",
            "- **Verdict:**",
            "",
        ]
    (Path(__file__).parent / "hints_spotcheck.md").write_text("\n".join(lines))


if __name__ == "__main__":
    main()
