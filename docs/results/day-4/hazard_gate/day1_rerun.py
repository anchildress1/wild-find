"""S55 ship gate, Day-1 set: the Day-1 hazard rule rerun with the hazard flags from the generated species_labels.json.

Run from the repo root after make assets:
    uv --project pipeline run python -I docs/results/day-4/hazard_gate/day1_rerun.py

docs/results/day-1/run_experiments.py hardcodes the old 7-species list and stays as Day 1 ran it. This script reuses
its cached photos (.models/day1-photos, manifest docs/results/day-1-photos.tsv, SHA-256 checked) and scores every
photo against the bundled 4,272-row species table under each rule below; the old flags are every Toxicodendron,
Phytolacca americana, and Solanum carolinense (labels.is_hazard). No teacher and no transformers run: the table is the
bundled species_table.npy, the photo embeddings come from the pinned BioCLIP Mobile.

Two gates per photo, each warning when a hazard row ranks in the top k (1 + rows scoring above the best hazard, so ties
warn, as core HazardCheck) on a region the gate calls a plant:
  day1: Day 1's own gate, the TinyCLIP 8M share > 0.5 read from docs/results/day-1/tinyclip_scores.csv (its full
    region is the Hugging Face processor's resize and crop); reproduces summary.txt's 48/52 and 1/253
  app: the bundled plant_gate.onnx on the same 224 crops the phone cuts (calibration.Verifier)
Rules (hazard rows a warning may name): fixed 7 (the old list); rule A, every generated hazard row (ships today); rule
B, the fixed 7 plus generated hazard rows the cached West Georgia October pull names at any count (calibration.hunt);
A triage and B triage, the same over the list with contact_hazards_review.json's triage applied.
Writes day1.csv (one row per photo) and prints the summary; gate.py runs it into gate.log.
"""

import csv
import hashlib
import importlib.util
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image
from wild_find_pipeline.labels import is_hazard
from wild_find_pipeline.paths import CONTACT_REVIEW, MODEL_CACHE
from wild_find_pipeline.reference import image_input, square_fixture

OUT = Path(__file__).resolve().parent
RESULTS = OUT.parents[1]
DAY1 = RESULTS / "day-1"
MANIFEST = RESULTS / "day-1-photos.tsv"
PHOTOS = MODEL_CACHE / "day1-photos"
_spec = importlib.util.spec_from_file_location("calibration", RESULTS / "day-3" / "calibration.py")
cal = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cal)

DAY1_GATE = "wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M"
HAZARD_GROUPS = ("poison_ivy", "poisonivy", "poison_oak", "poison_sumac", "pokeweed", "horsenettle")
TOP_KS = (1, 2, 3, 4, 5)
SHIPPED_K = 5
NONE = 10**9


RULES = {"old": "fixed 7", "new": "rule A", "local": "rule B", "tri": "A triage", "tri_local": "B triage"}


def triaged(labels: list[dict]) -> np.ndarray:
    """The generated `hazard` column with the review file's triage applied: `approve` rows listed, `reject` rows not."""
    review = json.loads(CONTACT_REVIEW.read_text())
    unknown = [name for name in review if name not in {e["scientific"] for e in labels}]
    if unknown:
        raise ValueError(f"review rows not in the species table: {unknown}")
    verdict = {name: row["triage"] for name, row in review.items()}
    return np.array([verdict.get(e["scientific"], "approve" if e["hazard"] else "reject") == "approve" for e in labels])


def flag_sets(labels: list[dict], local: np.ndarray | None = None) -> dict[str, np.ndarray]:
    """Hazard rows each rule ranks: `old` the fixed 7, `new` (rule A) the generated `hazard` column, `local` (rule B)
    the fixed 7 plus generated hazards seen in the region's pull, when `local` (a per-row mask) is given; `tri` and
    `tri_local` are rules A and B over the triaged list. The fixed 7 always stay listed."""
    old = np.array([is_hazard(e["scientific"]) for e in labels])
    new = np.array([e["hazard"] for e in labels])
    tri = old | triaged(labels)
    out = {"old": old, "new": new, "tri": tri}
    return out if local is None else out | {"local": old | (new & local), "tri_local": old | (tri & local)}


def best_hazard(scores: np.ndarray, flags: np.ndarray) -> tuple[int, int]:
    """(1-based rank, row) of the best hazard row; rows tied with it rank below it, as core HazardCheck."""
    rows = np.flatnonzero(flags)
    row = int(rows[np.argmax(scores[rows])])
    return int(1 + (scores > scores[row]).sum()), row


def warn_rank(ranks: list[tuple[bool, int]]) -> int:
    """Best hazard rank over the regions the gate passed; NONE when no region passed."""
    return min((rank for plant, rank in ranks if plant), default=NONE)


def group_of(photo: str) -> str:
    """Photo group from its file name, as day-1/summarize.py: `poison_ivy_9.jpg` → poison_ivy."""
    parts = photo.rsplit(".", 1)[0].split("_")
    return "_".join(parts[:-1]) if parts[-1].isdigit() else "_".join(parts)


def rate(n: int, d: int) -> str:
    """`n/d (p%)`."""
    return f"{n}/{d} ({n / d:.1%})" if d else "0/0"


def app_regions(v, path: Path, flags: dict[str, np.ndarray], names: list[str]) -> dict:
    """The phone's crops, plant gate, and BioCLIP on one photo; best hazard rank and species per region and flag set."""
    img = Image.open(path).convert("RGB")
    out = {}
    for region, crop in (("reticle", cal.tb.reticle(img)), ("full", square_fixture(img))):
        x = image_input(crop)
        share = v.share(x)
        scores = v.h["table"] @ v.embed(x)
        out[f"{region}_share"] = f"{share:.4f}"
        out[f"{region}_plant"] = share > cal.GATE_THRESHOLD
        for name, f in flags.items():
            rank, row = best_hazard(scores, f)
            out[f"{region}_rank_{name}"] = rank
            out[f"{region}_hazard_{name}"] = names[row]
    for name in flags:
        out[f"rank_{name}"] = warn_rank([(out[f"{r}_plant"], out[f"{r}_rank_{name}"]) for r in ("reticle", "full")])
    return out


def sweep(rows: list[dict], kind: str, column: str) -> dict[int, int]:
    """Photos of `kind` whose `column` rank is within each k of TOP_KS."""
    return {k: sum(1 for r in rows if r["kind"] == kind and r[column] <= k) for k in TOP_KS}


def drivers(rows: list[dict], gate: str = "app", rule: str = "new") -> str:
    """Hazard species behind each safe photo's warning at SHIPPED_K, most frequent first (both regions counted once)."""
    pre = "day1_" if gate == "day1" else ""
    count: dict = defaultdict(int)
    for r in rows:
        if r["kind"] == "safe" and r[f"{pre}rank_{rule}"] <= SHIPPED_K:
            plant = {reg: (float(r[f"{pre}{reg}_share"]) > cal.GATE_THRESHOLD) for reg in ("reticle", "full")}
            hit = {r[f"{reg}_hazard_{rule}"] for reg in plant if plant[reg] and r[f"{reg}_rank_{rule}"] <= SHIPPED_K}
            for name in hit:
                count[name] += 1
    return ", ".join(f"{n} {c}" for n, c in sorted(count.items(), key=lambda kv: (-kv[1], kv[0]))) or "none"


def day1_shares() -> dict[tuple[str, str], float]:
    """(photo, region) → Day 1's TinyCLIP 8M plant share."""
    share: dict = defaultdict(float)
    with (DAY1 / "tinyclip_scores.csv").open() as fh:
        for r in csv.DictReader(fh):
            if r["model"] == DAY1_GATE and r["kind"] == "plant":
                share[(r["photo"], r["region"])] += float(r["probability"])
    return share


def run(v, local: np.ndarray) -> list[dict]:
    """Score every Day-1 photo under both gates and every rule, rule B with the `local` row mask; checks the old flags
    against Day 1's own ranks."""
    labels = json.loads((cal.ASSETS / "species_labels.json").read_text())
    names = [e["scientific"] for e in labels]
    flags = flag_sets(labels, local)
    shares = day1_shares()
    day1_rank = {(r["photo"], r["region"]): int(r["best_hazard_rank"])
                 for r in csv.DictReader((DAY1 / "species_scores.csv").open())}  # fmt: skip
    out = []
    for m in csv.DictReader(MANIFEST.open(), delimiter="\t"):
        if m["set"] in ("fixture", "label-format"):
            continue
        path = PHOTOS / m["file"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != m["sha256"]:
            raise ValueError(f"{path} does not match its manifest SHA-256")
        group = group_of(m["file"]) if m["set"] == "plant" else m["set"]
        row = {"set": m["set"], "photo": m["file"], "group": group,
               "kind": "hazard" if group in HAZARD_GROUPS else "safe"}  # fmt: skip
        row |= app_regions(v, path, flags, names)
        for region in ("reticle", "full"):
            row[f"day1_{region}_share"] = f"{shares[(m['file'], region)]:.4f}"
            row[f"day1_{region}_rank_recorded"] = day1_rank[(m["file"], region)]
        for name in flags:
            row[f"day1_rank_{name}"] = warn_rank([(shares[(m["file"], r)] > cal.GATE_THRESHOLD,
                                                   row[f"{r}_rank_{name}"]) for r in ("reticle", "full")])  # fmt: skip
        out.append(row)
    return out


def summarize(rows: list[dict]) -> None:
    """Before/after catch and false-warning rates per gate and k, the reproduction check, and every changed photo."""
    n_h = sum(r["kind"] == "hazard" for r in rows)
    n_s = sum(r["kind"] == "safe" for r in rows)
    drift = [f"{r['photo']} {reg} {r[f'{reg}_rank_old']} vs {r[f'day1_{reg}_rank_recorded']}"
             for r in rows for reg in ("reticle", "full")
             if r[f"{reg}_rank_old"] != r[f"day1_{reg}_rank_recorded"]]  # fmt: skip
    print(f"Day-1 set: {n_h} hazard photos, {n_s} safe photos (fixture and label-format excluded, as Day 1)")
    print(f"old-flag ranks equal to day-1/species_scores.csv best_hazard_rank: {2 * len(rows) - len(drift)}/"
          f"{2 * len(rows)} photo-regions{'; differ: ' + ', '.join(drift) if drift else ''}")  # fmt: skip
    print("gate   rule     k   hazards caught      safe photos warned")
    for gate in ("day1", "app"):
        for name, label in RULES.items():
            col = f"day1_rank_{name}" if gate == "day1" else f"rank_{name}"
            caught, warned = sweep(rows, "hazard", col), sweep(rows, "safe", col)
            for k in TOP_KS:
                print(f"{gate:5s}  {label:7s}  {k}   {rate(caught[k], n_h):18s}  {rate(warned[k], n_s)}")
    for gate in ("day1", "app"):
        pre = "day1_" if gate == "day1" else ""
        print(f"\n[{gate} gate] at k={SHIPPED_K}; hazard species warning on safe photos: rule A "
              f"{drivers(rows, gate)}; rule B {drivers(rows, gate, 'local')}")  # fmt: skip
        for r in rows:
            warns = {name: r[f"{pre}rank_{name}"] <= SHIPPED_K for name in RULES}
            if (r["kind"] == "safe" and any(warns.values())) or (r["kind"] == "hazard" and not all(warns.values())):
                which = ", ".join(RULES[n] for n, w in warns.items() if w) or "no rule"
                print(f"  {r['kind']} warned by {which}: {r['photo']} reticle/full ranks "
                      + "; ".join(f"{RULES[n]} {r[f'reticle_rank_{n}']}/{r[f'full_rank_{n}']} "
                                  f"({r[f'reticle_hazard_{n}']} / {r[f'full_hazard_{n}']})" for n in RULES)
                      + f"; plant {gate} {r[f'{pre}reticle_share']}/{r[f'{pre}full_share']}")  # fmt: skip


def west_georgia(h: dict) -> np.ndarray:
    """Row mask of every species-table row the cached West Georgia October pull names, at any count (app blockers)."""
    mask = np.zeros(len(h["names"]), dtype=bool)
    mask[list(h["count"])] = True
    return mask


def main() -> int:
    """Rerun the Day-1 set; write day1.csv."""
    cal.snapshot_assets()
    h = cal.hunt(cal.ASSETS)
    v = cal.Verifier(cal.ASSETS, h)
    rows = run(v, west_georgia(h))
    cal.write(OUT / "day1.csv", rows)
    summarize(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
