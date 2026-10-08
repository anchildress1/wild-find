"""Day-3 probe for PRD hole 20: how many playable targets other US regions get, with the app's own rules.

Run from the repo root: uv --project pipeline run python -I docs/results/day-3/us_coverage.py

Pulls iNat species_counts for each region exactly as the app does (whole-degree center, 75 km, current month across
all years, research-grade plants, English names, at most 3 pages of 500), matches names to species-table rows
through each row's GBIF synonyms in species_labels.json, and applies R2: 0.5% of the pull's sightings and at least 3,
not toxic or a hazard, a common name of 3 words or fewer. Also counts genera, since a hunt needs 3. Writes
us_coverage.csv; prints the log.
"""

import csv
import json
import math
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from wild_find_pipeline.paths import GENERATED_ASSETS

OUT = Path(__file__).resolve().parent
MONTH = 10
RADIUS_KM = 75
SHARE = 0.005
MIN_SIGHTINGS = 3
# Whole-degree region centers, as RegionKey rounds them.
REGIONS = {
    "west-georgia": (34, -85),
    "seattle": (48, -122),
    "phoenix": (33, -112),
    "boston": (42, -71),
    "miami": (26, -80),
    "denver": (40, -105),
    "chicago": (42, -88),
}
AGENT = "wild-find/0.1 (+https://github.com/anchildress1/wild-find)"


def pull(lat: int, lng: int) -> list[dict]:
    """Every species the app's query returns, as (scientific, common, count)."""
    out, pages = [], 1
    page = 1
    while page <= pages:
        query = urllib.parse.urlencode(
            {
                "lat": lat,
                "lng": lng,
                "radius": RADIUS_KM,
                "month": MONTH,
                "iconic_taxa": "Plantae",
                "quality_grade": "research",
                "locale": "en",
                "per_page": 500,
                "page": page,
            }
        )
        request = urllib.request.Request(
            f"https://api.inaturalist.org/v1/observations/species_counts?{query}", headers={"User-Agent": AGENT}
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            body = json.load(response)
        if page == 1:
            pages = max(1, min(3, math.ceil(body["total_results"] / 500)))
        out += [{"name": r["taxon"]["name"], "common": r["taxon"].get("preferred_common_name") or "",
                 "count": r["count"]} for r in body["results"]]  # fmt: skip
        page += 1
        time.sleep(1)
    return out


def main() -> int:
    """Per region: species, sightings, table matches, eligible species and genera, blockers."""
    labels = json.loads((GENERATED_ASSETS / "species_labels.json").read_text())
    row_of = {}
    for row, e in enumerate(labels):
        for name in [e["scientific"], *e.get("synonyms", [])]:
            row_of[name] = row
    rows = []
    for place, (lat, lng) in REGIONS.items():
        species = pull(lat, lng)
        total = sum(s["count"] for s in species)
        floor = max(MIN_SIGHTINGS, SHARE * total)
        counts, common = {}, {}
        for s in species:
            row = row_of.get(s["name"])
            if row is None:
                continue
            counts[row] = counts.get(row, 0) + s["count"]
            if s["count"] >= common.get(row, ("", -1))[1]:
                common[row] = (s["common"], s["count"])
        eligible = [r for r, n in counts.items() if n >= floor and not labels[r]["toxic"] and not labels[r]["hazard"]
                    and common[r][0] and len(common[r][0].split()) <= 3]  # fmt: skip
        genera = {labels[r]["genus"] for r in eligible}
        blockers = [r for r in counts if labels[r]["toxic"] or labels[r]["hazard"]]
        matched = sum(1 for s in species if s["name"] in row_of)
        rows.append({"place": place, "lat": lat, "lng": lng, "species": len(species), "sightings": total,
                     "matched": matched, "floor": round(floor, 1), "eligible": len(eligible),
                     "eligible_genera": len(genera), "blockers": len(blockers),
                     "targets": "; ".join(sorted(common[r][0] for r in eligible))})  # fmt: skip
        r = rows[-1]
        print(
            f"{place} ({lat}, {lng}): {r['species']} species, {total} sightings, {matched} match a row; floor "
            f"{floor:.1f}; {r['eligible']} eligible in {r['eligible_genera']} genera; {r['blockers']} blockers"
        )
    with (OUT / "us_coverage.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
