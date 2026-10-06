"""Derive every Day-1 summary table from the raw CSVs written by run_experiments.py.

uv run --project pipeline python docs/results/day-1/summarize.py > docs/results/day-1/summary.txt
"""

import csv
from collections import defaultdict
from pathlib import Path

OUT = Path(__file__).parent
HAZARD_GROUPS = ("poison_ivy", "poison_oak", "poison_sumac", "pokeweed", "horsenettle")
TARGETS_NO_GRASS = ("oak", "fern", "clover", "pine", "dandelion")


def group_of(photo: str) -> str:
    """Photo group from its file name: `oak_123.jpg` → oak, `poison_ivy_9.jpg` → poison_ivy."""
    stem = photo.rsplit(".", 1)[0]
    parts = stem.split("_")
    return "_".join(parts[:-1]) if parts[-1].isdigit() else stem


def load_bioclip() -> dict[tuple[str, str, str], dict[str, tuple[str, float]]]:
    """(set, photo, region) → {label: (kind, score)}."""
    data: dict = defaultdict(dict)
    with (OUT / "bioclip_scores.csv").open() as fh:
        for r in csv.DictReader(fh):
            data[(r["set"], r["photo"], r["region"])][r["label"]] = (r["kind"], float(r["score"]))
    return data


def top(scores: dict[str, tuple[str, float]], labels) -> tuple[str, str, float]:
    """Top-1 (label, kind, score) among `labels`."""
    best = max(labels, key=lambda label: scores[label][1])
    return best, scores[best][0], scores[best][1]


def section(title: str) -> None:
    """Print a section header."""
    print(f"\n## {title}\n")


def label_format() -> None:
    """Top-3 per prompt format and model on the white oak photo."""
    section("Label format (white oak, iNat 211670015)")
    rows = defaultdict(list)
    cosine = None
    with (OUT / "label_format.csv").open() as fh:
        for r in csv.DictReader(fh):
            rows[(r["format"], r["model"])].append((r["label"], float(r["score"])))
            cosine = r["student_teacher_image_cosine"]
    for (fmt, model), scores in rows.items():
        ranked = sorted(scores, key=lambda x: -x[1])[:3]
        print(f"{fmt:17s} {model:8s} top-1 {ranked[0][0]:13s} | " + ", ".join(f"{a} {b:.3f}" for a, b in ranked))
    print(f"mobile vs teacher image cosine: {cosine}")


def bioclip_nonplants(data) -> None:
    """BioCLIP top-1 on non-plants with the original label set (5 targets, 5 hazards, 6 scenes)."""
    section("BioCLIP Mobile on non-plants (labels: 5 targets, 5 hazards, 6 scenes)")
    for region in ("reticle", "full"):
        hits = []
        for (s, photo, reg), scores in data.items():
            if s != "non-plant" or reg != region:
                continue
            labels = [lbl for lbl, (kind, _) in scores.items() if kind != "plant" or lbl in TARGETS_NO_GRASS]
            label, kind, score = top(scores, labels)
            if kind == "plant":
                hits.append(f"{photo}={label} {score:.3f}")
        print(f"{region}: {len(hits)}/22 non-plants top-1 is a plant target: {', '.join(sorted(hits))}")


def tinyclip_gate() -> None:
    """Plant-gate pass rates per model, region, and photo group."""
    section("TinyCLIP plant gate (plant share > 0.5)")
    share: dict = defaultdict(float)
    with (OUT / "tinyclip_scores.csv").open() as fh:
        for r in csv.DictReader(fh):
            if r["kind"] == "plant":
                share[(r["model"], r["set"], r["photo"], r["region"])] += float(r["probability"])
    models = sorted({k[0] for k in share})
    for model in models:
        for region in ("full", "reticle"):
            by_set: dict = defaultdict(lambda: [0, 0])
            by_group: dict = defaultdict(lambda: [0, 0])
            for (m, s, photo, reg), value in share.items():
                if m != model or reg != region:
                    continue
                by_set[s][0] += value > 0.5
                by_set[s][1] += 1
                if s == "plant":
                    by_group[group_of(photo)][0] += value > 0.5
                    by_group[group_of(photo)][1] += 1
            sets = ", ".join(f"{s} {p}/{n}" for s, (p, n) in sorted(by_set.items()))
            print(f"{model.split('/')[-1]} [{region}] passes as plant: {sets}")
            low = [f"{g} {p}/{n}" for g, (p, n) in sorted(by_group.items()) if p < n]
            print(f"    plant groups not fully kept: {', '.join(low) or 'none'}")


def hazard_warnings(data) -> None:
    """Hazard top-1 rates per set and group, labels: 6 targets incl. grass + 5 hazards, no scenes."""
    section("Hazard warnings (labels: grass, oak, fern, clover, pine, dandelion + 5 hazards; no scenes)")
    gaps: dict = defaultdict(list)
    for (s, photo, _region), scores in data.items():
        labels = [lbl for lbl, (kind, _) in scores.items() if kind != "scene"]
        hazards = [lbl for lbl in labels if scores[lbl][0] == "hazard"]
        plants = [lbl for lbl in labels if scores[lbl][0] == "plant"]
        gap = max(scores[h][1] for h in hazards) - max(scores[p][1] for p in plants)
        key = group_of(photo) if s == "plant" else s
        gaps[(key, photo)].append(gap)
    worst = {k: max(v) for k, v in gaps.items()}  # worse of the two regions
    groups = sorted({k[0] for k in worst})
    print("group                 n   hazard top-1   >0.02   >0.05   >0.10   median gap   min gap")
    for g in groups:
        vals = sorted(v for (k, _), v in worst.items() if k == g)
        n = len(vals)
        cnt = [sum(v > m for v in vals) for m in (0.0, 0.02, 0.05, 0.10)]
        print(
            f"{g:20s} {n:3d}   {cnt[0]:5d}          {cnt[1]:5d}   {cnt[2]:5d}   {cnt[3]:5d}   "
            f"{vals[n // 2]:+.3f}       {vals[0]:+.3f}"
        )


def tutorial_rules(data) -> None:
    """Grass rank on the reticle crop for the tutorial rule."""
    section("Tutorial rule: grass rank on the reticle crop (labels: 6 targets + 5 hazards)")
    by_set: dict = defaultdict(lambda: [0, 0, 0])
    for (s, photo, region), scores in data.items():
        if region != "reticle":
            continue
        labels = [lbl for lbl, (kind, _) in scores.items() if kind != "scene"]
        rank = sorted(labels, key=lambda lbl: -scores[lbl][1]).index("grass")
        key = s if s != "plant" else ("hazard plants" if group_of(photo) in HAZARD_GROUPS else "non-grass plants")
        by_set[key][0] += rank == 0
        by_set[key][1] += rank < 3
        by_set[key][2] += 1
    for key, (top1, top3, n) in sorted(by_set.items()):
        print(f"{key:17s} n={n:3d}  grass top-1: {top1:3d}  grass in top 3: {top3:3d}")


def main() -> None:
    """Print every summary."""
    data = load_bioclip()
    label_format()
    bioclip_nonplants(data)
    tinyclip_gate()
    hazard_warnings(data)
    tutorial_rules(data)


if __name__ == "__main__":
    main()
