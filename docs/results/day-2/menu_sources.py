"""Day-2 menu-source probes: iNat October species for the region, Wikidata toxicity claims, Wikipedia toxicity text.

Run from the repo root: uv --project pipeline run python -I docs/results/day-2/menu_sources.py
Writes inat_species_oct.csv, wikidata_toxicity.csv, wikipedia_toxicity.csv beside this file.
"""

import csv
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

OUT = Path(__file__).resolve().parent
UA = {"User-Agent": "wild-find-build/0.1 (github.com/anchildress1/wild-find)"}
# Wikidata properties that could carry toxicity on a taxon item.
WIKIDATA_PROPS = ["P1552", "P2789", "P366"]
TOXIC = re.compile(r"[^.]*\b(toxic\w*|poison\w*|giftig\w*|toxique|vénéneu\w*)\b[^.]*\.", re.I)
# Known toxic, known non-toxic, and fruiting species from the October top 40, plus European checks.
WIKIPEDIA_CASES = [
    ("en", "Nandina domestica"), ("en", "Euonymus americanus"), ("en", "Ampelopsis glandulosa"),
    ("en", "Callicarpa americana"), ("en", "Diospyros virginiana"), ("en", "Asimina triloba"),
    ("en", "Liquidambar styraciflua"), ("en", "Quercus nigra"), ("en", "Polystichum acrostichoides"),
    ("en", "Conoclinium coelestinum"), ("en", "Hedera helix"), ("en", "Ligustrum sinense"),
    ("en", "Sambucus nigra"), ("en", "Phytolacca americana"), ("de", "Taxus baccata"),
    ("de", "Aronstab"), ("fr", "Taxus baccata"),
]  # fmt: skip


def get(url: str) -> dict:
    """GET JSON with the named User-Agent; waits out 429s per Retry-After, up to 5 tries."""
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if attempt == 4 or e.code not in (429, 503):
                raise
            time.sleep(int(e.headers.get("Retry-After") or 10))
    raise AssertionError


def inat_species() -> list[dict]:
    """Every research-grade plant species seen within 75 km of 34,-85 in any October."""
    rows = []
    for page in (1, 2, 3):
        q = urllib.parse.urlencode(
            {"lat": 34, "lng": -85, "radius": 75, "month": 10, "iconic_taxa": "Plantae",
             "quality_grade": "research", "per_page": 500, "page": page}
        )  # fmt: skip
        data = get(f"https://api.inaturalist.org/v1/observations/species_counts?{q}")
        for r in data["results"]:
            t = r["taxon"]
            rows.append({"count": r["count"], "taxon_id": t["id"], "rank": t["rank"], "name": t["name"],
                         "common": t.get("preferred_common_name") or "", "wikipedia_url": t.get("wikipedia_url") or ""})  # fmt: skip
        if page * 500 >= data["total_results"]:
            break
        time.sleep(1)
    return rows


def wikidata(names: list[str]) -> list[dict]:
    """Toxicity-capable Wikidata claims for each scientific name."""
    rows = []
    for name in names:
        q = urllib.parse.urlencode({"action": "wbsearchentities", "search": name, "language": "en",
                                    "format": "json", "limit": 1})  # fmt: skip
        hits = get(f"https://www.wikidata.org/w/api.php?{q}")["search"]
        qid = hits[0]["id"] if hits else ""
        claims = {}
        if qid:
            entity = get(f"https://www.wikidata.org/wiki/Special:EntityData/{qid}.json")["entities"][qid]
            for p in WIKIDATA_PROPS:
                claims[p] = [c["mainsnak"].get("datavalue", {}).get("value", {}).get("id") for c in entity["claims"].get(p, [])]
        rows.append({"name": name, "qid": qid, **{p: " ".join(filter(None, claims.get(p, []))) for p in WIKIDATA_PROPS}})
        time.sleep(1)
    return rows


def wikipedia() -> list[dict]:
    """Full article length and the first sentence that names toxicity, per case."""
    rows = []
    for wiki, title in WIKIPEDIA_CASES:
        q = urllib.parse.urlencode({"action": "query", "prop": "extracts", "explaintext": 1, "titles": title,
                                    "redirects": 1, "format": "json"})  # fmt: skip
        page = next(iter(get(f"https://{wiki}.wikipedia.org/w/api.php?{q}")["query"]["pages"].values()))
        text = page.get("extract", "")
        hits = [m.group(0).strip().replace("\n", " ") for m in TOXIC.finditer(text)]
        rows.append({"wiki": wiki, "title": title, "chars": len(text), "keyword_hits": len(hits),
                     "first_hit": hits[0][:300] if hits else ""})  # fmt: skip
        time.sleep(1)
    return rows


def write(name: str, rows: list[dict]) -> None:
    """Write rows as CSV beside this script."""
    with (OUT / name).open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    print(f"wrote {name}: {len(rows)} rows")


def main() -> int:
    """Run all three probes."""
    species = inat_species()
    write("inat_species_oct.csv", species)
    names = [s for _, s in WIKIPEDIA_CASES if " " in s] + ["Atropa belladonna", "Conium maculatum"]
    write("wikidata_toxicity.csv", wikidata(sorted(set(names))))
    write("wikipedia_toxicity.csv", wikipedia())
    return 0


if __name__ == "__main__":
    sys.exit(main())
