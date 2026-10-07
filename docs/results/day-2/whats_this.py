"""Day-2 probe: how often would a "What's this?" capture name the right plant, at species and at genus level?

Run from the repo root: uv --project pipeline run python -I docs/results/day-2/whats_this.py
Reads Day 1's committed species scores (BioCLIP Mobile vs the whole 4,272-row table, no local filter) for the
single-genus plant photo sets and checks the reticle crop's top-1 species. It also estimates the local filter:
the best of each crop's top 5 that West Georgia iNat lists in October (inat_species_oct.csv), or none when no
top-5 species is local. And a confidence rule: name the genus only when the top 3 species share it. Writes
whats_this.csv.
"""

import csv
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

OUT = Path(__file__).resolve().parent
SCORES = OUT.parent / "day-1" / "species_scores.csv"
# Day-1 photo subjects that are one genus; fern and moss span many genera and are left out.
GENUS = {
    "oak": "Quercus", "pine": "Pinus", "maple": "Acer", "sweetgum": "Liquidambar", "clover": "Trifolium",
    "dandelion": "Taraxacum", "magnolia": "Magnolia", "violet": "Viola", "honeysuckle": "Lonicera",
    "poison_ivy": "Toxicodendron", "poisonivy": "Toxicodendron", "poison_oak": "Toxicodendron",
    "poison_sumac": "Toxicodendron", "pokeweed": "Phytolacca", "horsenettle": "Solanum",
}  # fmt: skip


def main() -> int:
    """Genus and species hit rates of the reticle crop's top-1 species, per subject."""
    local = {r["name"] for r in csv.DictReader((OUT / "inat_species_oct.csv").open())}
    rows = []
    for row in csv.DictReader(SCORES.open()):
        subject = re.sub(r"_\d+\.\w+$", "", row["photo"])
        if row["set"] != "plant" or row["region"] != "reticle" or subject not in GENUS:
            continue
        top5 = [entry.rsplit(" ", 1)[0] for entry in row["top5"].split("; ")]
        local_top = next((name for name in top5 if name in local), "")
        agreed = len({name.split(" ")[0] for name in top5[:3]}) == 1
        rows.append({"subject": subject, "photo": row["photo"], "top1": row["top1"],
                     "genus_right": row["top1"].split()[0] == GENUS[subject], "local_top": local_top,
                     "local_genus_right": local_top.split(" ")[0] == GENUS[subject] if local_top else "",
                     "top3_agree": agreed})  # fmt: skip
    with (OUT / "whats_this.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    by = defaultdict(list)
    for r in rows:
        by[r["subject"]].append(r)
    right = sum(r["genus_right"] for r in rows)
    print(f"{len(rows)} reticle crops; top-1 in the right genus: {right} ({right / len(rows):.0%})")
    named = [r for r in rows if r["local_top"]]
    local_right = sum(r["local_genus_right"] is True for r in named)
    print(f"local estimate: {len(named)} crops have a local species in their top 5; its genus is right on "
          f"{local_right} ({local_right / len(named):.0%}); {len(rows) - len(named)} would say not sure")
    sure = [r for r in rows if r["top3_agree"]]
    sure_right = sum(r["genus_right"] for r in sure)
    print(f"top 3 agree on a genus: {len(sure)} of {len(rows)} crops; that genus is right on {sure_right} "
          f"({sure_right / len(sure):.0%}); the other {len(rows) - len(sure)} would say not sure")
    for subject, group in sorted(by.items()):
        hits = sum(r["genus_right"] for r in group)
        wrong = Counter(r["top1"] for r in group if not r["genus_right"]).most_common(3)
        print(f"  {subject}: {hits} of {len(group)}" + (f"; wrong picks {wrong}" if wrong else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
