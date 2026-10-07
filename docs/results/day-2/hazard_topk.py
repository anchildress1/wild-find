"""Day-2 probe: hazard catches and false alarms at each top-k cutoff, from Day 1's committed species scores.

Run from the repo root: uv --project pipeline run python -I docs/results/day-2/hazard_topk.py
A photo warns when either region (full frame or reticle crop) ranks a hazard species within the cutoff, as the app
does. Reads docs/results/day-1/species_scores.csv; writes hazard_topk.csv (one row per photo).
"""

import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

OUT = Path(__file__).resolve().parent
SCORES = OUT.parent / "day-1" / "species_scores.csv"
HAZARD_SUBJECTS = {"poison_ivy", "poisonivy", "poison_oak", "poison_sumac", "pokeweed", "horsenettle"}
CUTOFFS = (1, 2, 3, 5)


def main() -> int:
    """Best hazard rank per photo across both regions, then catch and false-alarm counts per cutoff."""
    best = defaultdict(lambda: 10**9)
    sets = {}
    for row in csv.DictReader(SCORES.open()):
        key = row["photo"]
        sets[key] = row["set"]
        best[key] = min(best[key], int(row["best_hazard_rank"]))
    rows = []
    for photo, rank in sorted(best.items()):
        subject = re.sub(r"_\d+\.\w+$", "", photo)
        hazard = sets[photo] == "plant" and subject in HAZARD_SUBJECTS
        rows.append({"set": sets[photo], "photo": photo, "hazard": hazard, "best_hazard_rank": rank})
    with (OUT / "hazard_topk.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    hazards = [r for r in rows if r["hazard"]]
    safe = [r for r in rows if not r["hazard"]]
    print(f"{len(hazards)} hazard photos, {len(safe)} other photos (plants, grass, mixed scenes, non-plants)")
    for k in CUTOFFS:
        caught = sum(r["best_hazard_rank"] <= k for r in hazards)
        alarms = [r["photo"] for r in safe if r["best_hazard_rank"] <= k]
        print(f"top {k}: caught {caught} of {len(hazards)}; false alarms {len(alarms)} of {len(safe)} {alarms}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
