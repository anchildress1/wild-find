"""Day-3 S17 measurement: plant type counts across the species table and the West Georgia eligible targets.

Run from the repo root after make plant-types and make assets:
uv --project pipeline run python -I docs/results/day-3/plant_types.py > docs/results/day-3/plant_types.log

Eligible rule as toxic_block.py: iNat names resolve to rows by name or synonym and their counts sum; eligible at
>= 0.5% of the pull's plant sightings and >= 3, common name of 3 words or fewer, not toxic-flagged or hazard.
"""

import csv
import json
from collections import Counter
from pathlib import Path

from wild_find_pipeline.paths import GENERATED_ASSETS, PLANT_TYPES

DAY2 = Path(__file__).resolve().parent.parent / "day-2"
PLACE = "west-georgia-us"
SHARE = 0.005
MIN_SIGHTINGS = 3


def kind(entry: dict) -> str:
    """Where a type came from: usda, gbif (taxonomy), or none."""
    return entry["source"].split(" ")[0] if entry["source"] else "none"


def main() -> None:
    """Print per-type and per-source counts for the table and the eligible targets, then each target's call."""
    built = json.loads(PLANT_TYPES.read_text())
    types = built["species"]
    labels = json.loads((GENERATED_ASSETS / "species_labels.json").read_text())
    row_of = {alias: i for i, e in enumerate(labels) for alias in [e["scientific"], *e["synonyms"]]}
    rows = [r for r in csv.DictReader((DAY2 / "threshold_regions.csv").open()) if r["place"] == PLACE]
    floor = max(MIN_SIGHTINGS, SHARE * sum(int(r["count"]) for r in rows))
    by_row: dict[int, list[dict]] = {}
    for r in rows:
        if r["name"] in row_of:
            by_row.setdefault(row_of[r["name"]], []).append(r)
    count = {i: sum(int(r["count"]) for r in rs) for i, rs in by_row.items()}
    common = {i: max(rs, key=lambda r: int(r["count"]))["common"].strip() for i, rs in by_row.items()}
    ranked = sorted(by_row, key=lambda i: (-count[i], i))
    eligible = [
        i
        for i in ranked
        if count[i] >= floor
        and not (labels[i]["toxic"] or labels[i]["hazard"])
        and common[i]
        and len(common[i].split()) <= 3
    ]

    print(f"plant_types.json built {built['built']}; species_labels.json from make assets; place {PLACE}")
    print(f"rule: {json.dumps(built['rule'])}")
    shipped = Counter(str(e["type"]) for e in labels)
    if shipped != Counter(str(t["type"]) for t in types.values()):
        raise ValueError("species_labels.json types differ from plant_types.json; run make assets")
    print(f"\nwhole table: {len(labels)} rows")
    print(f"  by type: {dict(Counter(str(e['type']) for e in labels).most_common())}")
    print(f"  by source: {dict(Counter(kind(types[e['scientific']]) for e in labels).most_common())}")
    print(f"\nWest Georgia rows in the table (any count, incl. toxic): {len(ranked)}")
    print(f"  by type: {dict(Counter(str(labels[i]['type']) for i in ranked).most_common())}")
    print(f"\neligible targets: {len(eligible)}")
    print(f"  by type: {dict(Counter(str(labels[i]['type']) for i in eligible).most_common())}")
    print(f"  by source: {dict(Counter(kind(types[labels[i]['scientific']]) for i in eligible).most_common())}")
    print(f"\n{'count':>5}  {'scientific':<28} {'common':<26} {'type':<8} source")
    for i in eligible:
        name = labels[i]["scientific"]
        print(f"{count[i]:>5}  {name:<28} {common[i]:<26} {labels[i]['type']!s:<8} {types[name]['source']}")


if __name__ == "__main__":
    main()
