"""Day-2 probe: the shipped verify question, "is this the target?", scored against a few target labels, not the table.

Run from the repo root: uv --project pipeline run python -I docs/results/day-2/target_pass.py
Reads Day 1's committed BioCLIP scores for the 6 group labels (grass, oak, fern, clover, pine, dandelion). For each
photo of one of those groups, the reticle crop passes when its own label is top-1 among the 6, as verify's target
top-1 rule does with a hunt's targets. Compare whats_this.log, which asks the same photos for a top-1 over the
whole 4,272-species table. Writes target_pass.csv.
"""

import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

OUT = Path(__file__).resolve().parent
SCORES = OUT.parent / "day-1" / "bioclip_scores.csv"
GROUPS = ("grass", "oak", "fern", "clover", "pine", "dandelion")


def main() -> int:
    """Per photo: is its own group label top-1 among the 6 group labels on the reticle crop?"""
    scores = defaultdict(dict)
    sets = {}
    for row in csv.DictReader(SCORES.open()):
        if row["region"] == "reticle" and row["kind"] == "plant":
            scores[row["photo"]][row["label"]] = float(row["score"])
            sets[row["photo"]] = row["set"]
    rows = []
    for photo, by_label in sorted(scores.items()):
        subject = "grass" if sets[photo] == "grass" else re.sub(r"_\d+\.\w+$", "", photo)
        if sets[photo] not in ("plant", "grass") or subject not in GROUPS:
            continue
        top = max(by_label, key=by_label.get)
        rows.append({"photo": photo, "group": subject, "top1": top, "pass": top == subject})
    with (OUT / "target_pass.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    passed = sum(r["pass"] for r in rows)
    print(f"{len(rows)} reticle crops of the 6 groups; own label top-1 among 6: {passed} ({passed / len(rows):.0%})")
    for group in GROUPS:
        g = [r for r in rows if r["group"] == group]
        print(f"  {group}: {sum(r['pass'] for r in g)} of {len(g)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
