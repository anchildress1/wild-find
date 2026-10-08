"""Day-3 measurement: how many species-table rows and local targets get a USDA-trait description, and what they say.

Run from the repo root after make descriptions and make assets:
uv --project pipeline run python -I docs/results/day-3/descriptions.py > docs/results/day-3/descriptions.log

Places: West Georgia (34, -85), the day-2 pull in ../day-2/threshold_regions.csv; and 37, -82 (southwest Virginia), the
October pull the test phone cached on Oct 8 (inat_37_-82_october.json, copied off the phone with adb run-as).
Eligible rule as plant_types.py: iNat names resolve to rows by name or synonym and their counts sum; eligible at
>= 0.5% of the pull's plant sightings and >= 3, common name of 3 words or fewer, not toxic-flagged or hazard.
"""

import csv
import json
from collections import Counter
from pathlib import Path

from wild_find_pipeline.paths import DESCRIPTIONS, GENERATED_ASSETS

HERE = Path(__file__).resolve().parent
SHARE = 0.005
MIN_SIGHTINGS = 3


def eligible(labels: list[dict], pull: list[tuple[str, str, int]]) -> list[tuple[int, str, int]]:
    """(row, common name, count) for the pull's eligible targets, most sighted first."""
    row_of = {alias: i for i, e in enumerate(labels) for alias in [e["scientific"], *e["synonyms"]]}
    floor = max(MIN_SIGHTINGS, SHARE * sum(count for _, _, count in pull))
    by_row: dict[int, list[tuple[str, str, int]]] = {}
    for sighting in pull:
        if sighting[0] in row_of:
            by_row.setdefault(row_of[sighting[0]], []).append(sighting)
    out = []
    for i, found in by_row.items():
        count = sum(c for _, _, c in found)
        common = (max(found, key=lambda s: s[2])[1] or "").strip()
        playable = not (labels[i]["toxic"] or labels[i]["hazard"])
        if count >= floor and playable and common and len(common.split()) <= 3:
            out.append((i, common, count))
    return sorted(out, key=lambda t: (-t[2], t[0]))


def report(place: str, labels: list[dict], targets: list[tuple[int, str, int]]) -> None:
    """Coverage and every target's sentence for one place."""
    described = sum(1 for i, _, _ in targets if labels[i]["description"])
    print(f"\n{place}: {described} of {len(targets)} eligible targets described")
    print(f"{'count':>5}  {'common':<26} {'type':<8} description")
    for i, common, count in targets:
        print(f"{count:>5}  {common:<26} {labels[i]['type']!s:<8} {labels[i]['description'] or '-'}")


def main() -> None:
    """Print table-wide coverage, per-type coverage, and both places' targets with their sentences."""
    built = json.loads(DESCRIPTIONS.read_text())
    labels = json.loads((GENERATED_ASSETS / "species_labels.json").read_text())
    if [e["description"] for e in labels] != [built["species"][e["scientific"]]["description"] for e in labels]:
        raise ValueError("species_labels.json descriptions differ from descriptions.json; run make assets")
    print(f"descriptions.json built {built['built']}; species_labels.json from make assets")
    print(f"rule: {json.dumps(built['rule']['features'])}; {built['rule']['order']}")
    total = Counter(str(e["type"]) for e in labels)
    have = Counter(str(e["type"]) for e in labels if e["description"])
    print(f"\nwhole table: {sum(have.values())} of {len(labels)} rows described")
    print("  by type (described/rows): " + ", ".join(f"{t} {have[t]}/{n}" for t, n in total.most_common()))
    print(f"  distinct sentences: {len({e['description'] for e in labels if e['description']})}")

    georgia = [
        (r["name"], r["common"], int(r["count"]))
        for r in csv.DictReader((HERE.parent / "day-2" / "threshold_regions.csv").open())
        if r["place"] == "west-georgia-us"
    ]
    report("West Georgia (34, -85), day-2 pull", labels, eligible(labels, georgia))
    cached = json.loads((HERE / "inat_37_-82_october.json").read_text())
    virginia = [(s["scientific"], s["common"], s["count"]) for s in cached["sightings"]]
    report(f"37, -82, phone cache ({cached['locale']}, month {cached['month']})", labels, eligible(labels, virginia))


if __name__ == "__main__":
    main()
