"""Day-2 spot check: how many of a region's common October plants are in BioCLIP Mobile's 4,271-species table?

Run from the repo root after `make assets` (or make fetch of the taxa pins):

    uv run --project pipeline python -I docs/results/day-2/table_coverage.py

Regions: West Georgia, US (the app's region) and Tbilisi, Georgia (the country). Writes table_coverage.csv.
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
REGIONS = {"west-georgia-us": (34, -85), "tbilisi-georgia": (41.72, 44.79)}
MIN_SIGHTINGS = 25


def species(lat: float, lng: float) -> list[dict]:
    """Every research-grade plant species seen within 75 km in any October, with sighting counts."""
    rows = []
    for page in (1, 2, 3):
        q = urllib.parse.urlencode({"lat": lat, "lng": lng, "radius": 75, "month": 10, "iconic_taxa": "Plantae",
                                    "quality_grade": "research", "per_page": 500, "page": page})  # fmt: skip
        data = get(f"https://api.inaturalist.org/v1/observations/species_counts?{q}")
        rows += [{"count": r["count"], "name": r["taxon"]["name"], "rank": r["taxon"]["rank"],
                  "common": r["taxon"].get("preferred_common_name") or ""} for r in data["results"]]  # fmt: skip
        if page * 500 >= data["total_results"]:
            break
        time.sleep(1)
    return rows


def main() -> int:
    """Count table membership per region, overall and among species with MIN_SIGHTINGS or more."""
    table = {e["scientific"] for e in json.loads(verified_artifact("taxa_labels").read_text())}
    genera = {name.split()[0] for name in table}
    out = []
    for region, (lat, lng) in REGIONS.items():
        rows = [r for r in species(lat, lng) if r["rank"] == "species"]
        for r in rows:
            out.append({"region": region, **r, "in_table": r["name"] in table,
                        "genus_in_table": r["name"].split()[0] in genera})  # fmt: skip
        common = [r for r in rows if r["count"] >= MIN_SIGHTINGS]
        for label, subset in (("all", rows), (f"{MIN_SIGHTINGS}+", common)):
            hit = sum(r["name"] in table for r in subset)
            genus = sum(r["name"].split()[0] in genera for r in subset)
            print(f"{region} {label}: {len(subset)} species, {hit} in table, {genus} with genus in table")
        top = sorted(rows, key=lambda r: -r["count"])[:20]
        print("  top 20: " + ", ".join(f"{r['common'] or r['name']}{'' if r['name'] in table else ' [MISSING]'}" for r in top))
        time.sleep(1)
    with (OUT / "table_coverage.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
