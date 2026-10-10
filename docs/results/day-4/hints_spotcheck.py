"""Draw the owner's S56 spot-check: 50 random shipped Gemma hints, each beside the sentence it quotes.

Run: uv run --project pipeline python -I docs/results/day-4/hints_spotcheck.py
"""

import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SEED = 56
SIZE = 50


def main() -> None:
    """Write hints_spotcheck.md next to this script."""
    labels = json.loads((ROOT / "app/generated/assets/species_labels.json").read_text())
    rows = labels if isinstance(labels, list) else labels["species"]
    built = json.loads((ROOT / "pipeline/data/hints.json").read_text())["species"]
    shipped = []
    for row in rows:
        for hint in row.get("hints") or []:
            # Shipped hints carry only text, so the quote comes from the build file.
            source = next(h for h in built[row["scientific"]]["hints"] if h["text"] == hint["text"])
            # USDA and trait hints are templated from ratings, with no sentence to grade against.
            if source["source"] == "model":
                shipped.append((row["scientific"], built[row["scientific"]]["article"], source))
    picks = random.Random(SEED).sample(shipped, SIZE)
    lines = [
        "# 🌿 S56 hints spot-check",
        "",
        f"{SIZE} of {len(shipped)} shipped Gemma hints, drawn with seed {SEED}.",
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
