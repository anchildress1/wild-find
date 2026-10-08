"""Day-3 probe: how many iNat October species each place matches to a species-table row, exactly vs through GBIF aliases.

Run from the repo root after make synonyms: uv --project pipeline run python -I docs/results/day-3/name_match.py

Reads day-2 threshold_regions.csv (iNat October plant species per place), the species table rows and hazard flags
from species_labels.json, toxicity.json, and the committed synonyms.json. Playable uses the day-2 rule
(playable_species.py): 0.5% of the place's sightings with at least 3, not toxic-flagged, not a hazard. With aliases,
two iNat names that land on one row add their counts. Writes name_match.csv; prints the log, including every
West Georgia name that only matches through an alias.
"""

import csv
import json
import sys
from pathlib import Path

from wild_find_pipeline.paths import GENERATED_ASSETS, SYNONYMS, TOXICITY

OUT = Path(__file__).resolve().parent
DAY2 = OUT.parent / "day-2"
SHARE = 0.005
MIN_SIGHTINGS = 3


def playable(
    rows: list[dict],
    row_of: dict[str, str],
    hazard: dict[str, bool],
    toxic: dict[str, bool],
    floor,
):
    """Rows whose summed sightings clear the floor and that are neither hazards nor toxic-flagged."""
    counts: dict[str, int] = {}
    for r in rows:
        if r["name"] in row_of:
            counts[row_of[r["name"]]] = counts.get(row_of[r["name"]], 0) + int(
                r["count"]
            )
    return {
        row: n
        for row, n in counts.items()
        if n >= floor and not hazard[row] and not toxic.get(row, True)
    }


def main() -> int:
    """Per place: species, exact and alias matches, and playable targets before and after aliases."""
    labels = json.loads((GENERATED_ASSETS / "species_labels.json").read_text())
    hazard = {e["scientific"]: e["hazard"] for e in labels}
    toxic = {
        name: entry["toxic"]
        for name, entry in json.loads(TOXICITY.read_text())["species"].items()
    }
    built = json.loads(SYNONYMS.read_text())
    exact = {name: name for name in hazard}
    alias = exact | {a: row for row, names in built["species"].items() for a in names}
    by_place: dict[str, list[dict]] = {}
    for row in csv.DictReader((DAY2 / "threshold_regions.csv").open()):
        by_place.setdefault(row["place"], []).append(row)
    print(
        f"# synonyms.json built {built['built']}: {sum(map(len, built['species'].values()))} aliases, "
        f"{len(built['unmatched'])} rows unmatched, {built['dropped_ambiguous']} ambiguous and "
        f"{built['dropped_table_name']} table-name aliases dropped"
    )
    out, west = [], []
    for place, rows in by_place.items():
        total = sum(int(r["count"]) for r in rows)
        floor = max(MIN_SIGHTINGS, SHARE * total)
        hit_exact = [r for r in rows if r["name"] in exact]
        hit_alias = [r for r in rows if r["name"] in alias]
        before = playable(rows, exact, hazard, toxic, floor)
        after = playable(rows, alias, hazard, toxic, floor)
        shared = len(hit_alias) - len({alias[r["name"]] for r in hit_alias})
        out.append({"place": place, "species": len(rows), "sightings": total, "exact": len(hit_exact),
                    "exact_or_alias": len(hit_alias), "names_sharing_a_row": shared, "share_floor": round(floor, 1),
                    "playable_before": len(before), "playable_after": len(after)})  # fmt: skip
        print(
            f"{place}: {len(rows)} species; exact {len(hit_exact)}, exact or alias {len(hit_alias)} "
            f"({shared} names share a row with another name); playable at 0.5%/3 (floor {floor:.1f}): "
            f"{len(before)} -> {len(after)}; new targets {sorted(set(after) - set(before))}"
        )
        if place == "west-georgia-us":
            west = [r for r in hit_alias if r["name"] not in exact]
    with (OUT / "name_match.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)
    print(
        f"\nwest-georgia-us: {len(west)} names matched only through an alias (count, iNat name -> table row):"
    )
    for r in sorted(west, key=lambda r: -int(r["count"])):
        print(f"  {r['count']:>4}  {r['name']} -> {alias[r['name']]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
