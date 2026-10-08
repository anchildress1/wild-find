"""Day-2 probe: a deterministic toxicity flag over the full English Wikipedia text of every 25+ October species.

Run from the repo root after menu_sources.py: uv --project pipeline run python -I docs/results/day-2/toxicity_flag.py
Writes toxicity_flag.csv beside this file.
"""

import csv
import re
import sys
import time
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from menu_sources import OUT, get  # noqa: E402

MIN_SIGHTINGS = 25
# Other plants' names that contain the keywords; removed before matching so a mention isn't a claim.
NAMES = re.compile(r"\bpoison[- ](ivy|oak|sumac|hemlock|ivies|oaks)\b", re.I)
TOXIC = re.compile(r"\b(toxic\w*|toxin\w*|poison\w*)\b", re.I)
SENTENCE = re.compile(r"[^.!?\n]+[.!?]?")


def flag(text: str) -> list[str]:
    """Every sentence that claims toxicity once other plants' poison names are removed."""
    return [s.strip() for s in SENTENCE.findall(NAMES.sub("", text)) if TOXIC.search(s)]


def article(url: str) -> str | None:
    """Full plain text of the English article at url, or None when there is none."""
    if not url:
        return None
    title = urllib.parse.unquote(url.rsplit("/", 1)[1])
    q = urllib.parse.urlencode({"action": "query", "prop": "extracts", "explaintext": 1, "titles": title,
                                "redirects": 1, "format": "json"})  # fmt: skip
    page = next(iter(get(f"https://en.wikipedia.org/w/api.php?{q}")["query"]["pages"].values()))
    return page.get("extract")


def main() -> int:
    """Flag every species with MIN_SIGHTINGS or more October sightings."""
    species = [r for r in csv.DictReader((OUT / "inat_species_oct.csv").open()) if int(r["count"]) >= MIN_SIGHTINGS]
    rows = []
    for s in species:
        text = article(s["wikipedia_url"])
        hits = flag(text) if text else []
        rows.append({"count": s["count"], "name": s["name"], "common": s["common"],
                     "chars": len(text) if text else 0, "toxic": bool(hits), "hits": len(hits),
                     "first_hit": hits[0][:300] if hits else ""})  # fmt: skip
        time.sleep(1)
    with (OUT / "toxicity_flag.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    toxic = sum(r["toxic"] for r in rows)
    missing = sum(r["chars"] == 0 for r in rows)
    short = sum(0 < r["chars"] < 1500 for r in rows)
    print(f"{len(rows)} species: {toxic} flagged toxic, {missing} no article, {short} under 1,500 chars")
    return 0


if __name__ == "__main__":
    sys.exit(main())
