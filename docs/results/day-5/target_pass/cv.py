"""Day-5 target pass: how well each rule's TUNE threshold holds on toxic species it never saw, inside TUNE only.

Run from the repo root after rules.py tune: uv run --project pipeline python -I docs/results/day-5/target_pass/cv.py

Reads tune.csv (no TEST photo). For each rule with eligible competitors, REPEATS times: split TUNE's toxic photos into
FOLDS groups by species, set tau from 4 groups exactly as rules.py does (smallest multiple of the rule's step above
the worst toxic L), and count the held-out group's toxic photos at or above it. Reports the mean leaked photos per
full pass over the folds, the share of passes with any leak, and the median TUNE target pass at the fold taus.
"""

import csv
import importlib.util
import math
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

OUT = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("rules", OUT / "rules.py")
rules = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rules)

FOLDS = 5
REPEATS = 500
SEED = 20261010


def main() -> int:
    """Grouped CV over toxic species per rule."""
    by_rule = defaultdict(list)
    for r in csv.DictReader((OUT / "tune.csv").open()):
        by_rule[r["rule"]].append(r)
    rng = np.random.default_rng(SEED)
    print(f"# grouped {FOLDS}-fold CV over TUNE toxic species, {REPEATS} repeats, seed {SEED}; input tune.csv sha256 "
          f"{rules.cal.file_sha256(OUT / 'tune.csv')}")  # fmt: skip
    print(
        "| rule | TUNE tau | mean leaked toxic photos per pass | passes with a leak | median target pass at fold taus |"
    )
    print("| --- | --- | --- | --- | --- |")
    for rule in rules.RULES:
        if rule.competitors != "eligible" or rule.tau is not None or rule.name not in by_rule:
            continue
        rows = by_rule[rule.name]
        tox = [r for r in rows if r["toxic"] == "True"]
        species = sorted({r["species"] for r in tox})
        tox_l = np.array([float(r["L"]) for r in tox])
        tox_sp = np.array([species.index(r["species"]) for r in tox])
        tgt = [r for r in rows if r["kind"] == "target"]
        tgt_l = np.array([float(r["L"]) for r in tgt])
        tgt_ok = np.array([r["reticle_plant"] == "True" and r["hazard_warn"] == "False"
                           and r["top_genus"] == r["own_genus"] for r in tgt])  # fmt: skip
        leaks, any_leak, passes = [], 0, []
        for _ in range(REPEATS):
            fold = rng.permutation(len(species)) % FOLDS
            leaked = 0
            for f in range(FOLDS):
                held = fold[tox_sp] == f
                worst = tox_l[~held].max()
                tau = max(rule.step, (math.floor(worst / rule.step + 1e-9) + 1) * rule.step)
                leaked += int((tox_l[held] >= tau).sum())
                passes.append(int((tgt_ok & (tgt_l >= tau)).sum()))
            leaks.append(leaked)
            any_leak += leaked > 0
        tau = rows[0]["tau"]
        print(f"| {rule.name} | {tau} | {np.mean(leaks):.2f} | {any_leak / REPEATS:.0%} | "
              f"{int(np.median(passes))}/{len(tgt)} |")  # fmt: skip
    return 0


if __name__ == "__main__":
    sys.exit(main())
