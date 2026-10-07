"""Day-2 probe: a fixed 25-sighting floor vs a share-of-sightings floor, across dense and sparse places.

Run from the repo root: uv --project pipeline run python -I docs/results/day-2/threshold_regions.py
Places: West Georgia (the app region), Atlanta, Tbilisi, Borjomi (rural Georgia), and the country of Georgia.
Every place uses October, research grade, plants. Writes threshold_regions.csv (one row per place and species).
"""

import csv
import json
import sys
import time
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from menu_sources import get  # noqa: E402
from wild_find_pipeline.paths import verified_artifact  # noqa: E402

OUT = Path(__file__).resolve().parent
# lat/lng places use the app's 75 km radius; the country uses its iNat place_id.
PLACES = {
    "west-georgia-us": {"lat": 34, "lng": -85, "radius": 75},
    "atlanta-us": {"lat": 33.75, "lng": -84.39, "radius": 75},
    "tbilisi-georgia": {"lat": 41.72, "lng": 44.79, "radius": 75},
    "borjomi-georgia": {"lat": 41.84, "lng": 43.38, "radius": 75},
    "country-georgia": {"place_id": 8857},
}
FIXED = 25
SHARES = (0.001, 0.002, 0.005, 0.01)


def species(where: dict) -> list[dict]:
    """Every research-grade plant species seen in any October at the place, with sighting counts."""
    rows = []
    for page in (1, 2, 3, 4, 5, 6):
        q = urllib.parse.urlencode({**where, "month": 10, "iconic_taxa": "Plantae", "quality_grade": "research",
                                    "per_page": 500, "page": page})  # fmt: skip
        data = get(f"https://api.inaturalist.org/v1/observations/species_counts?{q}")
        rows += [{"count": r["count"], "name": r["taxon"]["name"], "rank": r["taxon"]["rank"],
                  "common": r["taxon"].get("preferred_common_name") or ""} for r in data["results"]]  # fmt: skip
        if page * 500 >= data["total_results"]:
            break
        time.sleep(1)
    return [r for r in rows if r["rank"] == "species"]


def main() -> int:
    """Compare eligibility counts under the fixed floor and each share floor, per place."""
    table = {e["scientific"] for e in json.loads(verified_artifact("taxa_labels").read_text())}
    out = []
    for place, where in PLACES.items():
        rows = species(where)
        total = sum(r["count"] for r in rows)
        for r in rows:
            out.append({"place": place, **r, "share": round(r["count"] / total, 6), "in_table": r["name"] in table})
        cells = [f"{len(rows)} species, {total} sightings"]
        floors = [(f"{FIXED}+", FIXED)] + [(f"{s:.1%}", s * total) for s in SHARES]
        for label, floor in floors:
            ok = [r for r in rows if r["count"] >= floor]
            cells.append(f"{label} (>= {floor:.0f}): {len(ok)} eligible, {sum(r['name'] in table for r in ok)} in table")
        print(f"{place}: " + "; ".join(cells))
        time.sleep(1)
    with (OUT / "threshold_regions.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
