"""A generic one-line description per species-table row: the plant type in kid words ("A tree."); no model, no network.

Writes the committed pipeline/data/descriptions.json, one entry per row of the committed plant_types.json. Specifics
(size, flower and fruit color, season) are hints now, not description: see hint_traits.py and hint_rank.py.
"""

import json
import re
import sys
from datetime import date

from wild_find_pipeline.paths import DESCRIPTIONS, PLANT_TYPES

# What a kid calls each plant type; "herb" means nothing to an 8-year-old.
NOUNS = {
    "tree": "tree",
    "shrub": "bush",
    "vine": "vine",
    "herb": "plant",
    "grass": "grass",
    "fern": "fern",
    "moss": "moss",
    "conifer": "evergreen",
    None: "plant",
}
# Copy rule for every kid-facing string; a generated sentence that hits one fails the build.
BANNED = ("safe", "harmless", "not poisonous", "okay to touch", "ok to touch")
# Model-written kid copy never tells a kid to put a plant in their mouth or hands.
ACTIONS = re.compile(r"\b(?:eat|eating|eaten|touch|touching|pick|picking|taste|tasting)\b", re.I)


def article(word: str) -> str:
    """'An' before a vowel sound, else 'A'."""
    return "An" if word[0] in "aeiou" else "A"


def describe(kind: str | None) -> dict:
    """The generic description for a plant type; raises when it hits a banned kid word."""
    noun = NOUNS[kind]
    text = f"{article(noun)} {noun}."
    if any(word in text.lower() for word in BANNED):
        raise ValueError(f"banned kid word in {text!r}")
    return {"description": text}


def main() -> int:
    """Write descriptions.json: one entry per plant-types row, plus the rule that made them."""
    types = json.loads(PLANT_TYPES.read_text())["species"]
    species = {row: describe(types[row]["type"]) for row in sorted(types)}
    DESCRIPTIONS.write_text(
        json.dumps(
            {
                "built": date.today().isoformat(),
                "rule": {"noun_by_type": {str(k): v for k, v in NOUNS.items()}, "banned": list(BANNED)},
                "species": species,
            },
            indent=1,
            ensure_ascii=False,
        )
        + "\n"
    )
    print(f"OK: {DESCRIPTIONS}: {len(species)} rows described")
    return 0


if __name__ == "__main__":
    sys.exit(main())
