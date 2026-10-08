"""Day-2 probe: how many hunt targets each place would really get, under a flat 25+ floor vs 0.5% with at least 3.

Run from the repo root after make assets: uv --project pipeline run python -I docs/results/day-2/playable_species.py

Reads the committed threshold_regions.csv (iNat October plant species per place), the species table, and the
committed toxicity flags. A species is playable when it clears the floor, matches a species-table name exactly, and
isn't toxic-flagged or a hazard. Also counts how many of each place's species names match the table at all, since
anything that misses can't be a target until names are matched through synonyms (PRD hole 22).
Writes playable_species.csv.
"""

import csv
import json
import sys
from pathlib import Path

from wild_find_pipeline.paths import GENERATED_ASSETS, TOXICITY

OUT = Path(__file__).resolve().parent
SHARE = 0.005
MIN_SIGHTINGS = 3
FIXED = 25


def main() -> int:
    """Per place: species, exact table matches, and playable targets under each floor."""
    labels = {e["scientific"]: e for e in json.loads((GENERATED_ASSETS / "species_labels.json").read_text())}
    toxic = {name: entry["toxic"] for name, entry in json.loads(TOXICITY.read_text())["species"].items()}
    by_place: dict[str, list[dict]] = {}
    for row in csv.DictReader((OUT / "threshold_regions.csv").open()):
        by_place.setdefault(row["place"], []).append(row)
    out = []
    for place, rows in by_place.items():
        total = sum(int(r["count"]) for r in rows)
        floor = max(MIN_SIGHTINGS, SHARE * total)
        matched = [r for r in rows if r["name"] in labels]
        safe = [r for r in matched if not labels[r["name"]]["hazard"] and not toxic.get(r["name"], True)]
        share = [r for r in safe if int(r["count"]) >= floor]
        fixed = [r for r in safe if int(r["count"]) >= FIXED]
        out.append({"place": place, "species": len(rows), "sightings": total, "in_table": len(matched),
                    "share_floor": round(floor, 1), "playable_share": len(share), "playable_25": len(fixed)})  # fmt: skip
        print(
            f"{place}: {len(rows)} species, {total} sightings; {len(matched)} match a table name exactly; "
            f"playable at 0.5% with at least {MIN_SIGHTINGS} (floor {floor:.1f}): {len(share)}; "
            f"playable at {FIXED}+: {len(fixed)}"
        )
    with (OUT / "playable_species.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)
    local = [line for line in (OUT.parents[2] / "app/src/debug/assets/local_species.txt").read_text().splitlines()
             if line and not line.startswith("#")]  # fmt: skip
    hits = sum(name in labels for name in local)
    print(f"gate harness local list (West Georgia, October): {hits} of {len(local)} names match the species table")
    return 0


if __name__ == "__main__":
    sys.exit(main())
