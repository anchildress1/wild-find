"""Day-5 target pass: candidate verify row-4 rules on cached embeddings, TUNE and TEST kept apart.

Run from the repo root after embed.py:
    uv run --project pipeline python -I docs/results/day-5/target_pass/rules.py reproduce > reproduce.log
    uv run --project pipeline python -I docs/results/day-5/target_pass/rules.py tune > tune.log
    uv run --project pipeline python -I docs/results/day-5/target_pass/rules.py test > test.log

Splits: TUNE = S50 calibration (30 targets, 20 grass and other plants) + the toxic-block photos (69 targets, 189
toxic: Day 2's 180 plus Day 3's 9). TEST = S51 holdout (30 targets, 15 toxic, 27 non-plants) + NEW (fetch.py).
`tune` scores every rule on TUNE only; `test` scores only FINALISTS (chosen from tune.log before any TEST number was
computed) and the baseline, once. `reproduce` checks the harness against the Oct 8 numbers.

Hunt: calibration.py's West Georgia October hunt (23 eligible targets, 282 local toxic or hazard blockers).
Verify per photo, as the phone runs one capture (a still stands in for every frame):
  row 1 (hazard): the best warning hazard row ranks in the top 5 of the whole table on ret60 or on full when TinyCLIP
    calls that crop a plant. `rule B` (shipped Oct 10, core HazardCheck): warning rows = floor hazards + local
    hazards. `floor` (Oct 8): the fixed 7 only, for reproduction
  row 2 (plant gate): TinyCLIP plant share of ret60 > 0.5
  row 4: the rule under test. Every rule computes a lead L and the top eligible row's genus g; it passes g when L >= tau
A target photo passes when its verdict passes and g is its own genus. A toxic photo is a toxic pass when row 4 alone
passes any target: gate and row 1 ignored, the strictest count. Toxic photos are the toxic sets plus any photo whose
species is a toxic or hazard table row. Headroom = tau minus the largest toxic L (positive = no pass).
Tau per rule: the smallest multiple of the rule's step above the largest TUNE toxic L (the 0.0477 -> 0.048 convention
that set today's margin), or a fixed value where the rule says so.
Writes <mode>.csv: one row per photo and rule (L, g, verdict, pass flags).
"""

import csv
import importlib.util
import itertools
import json
import math
import platform
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import numpy as np
import onnxruntime as ort
import PIL
from wild_find_pipeline.paths import MODEL_CACHE

OUT = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("embed", OUT / "embed.py")
emb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(emb)
cal = emb.cal
tb = cal.tb

GATE = 0.5
HAZARD_TOP = 5
SHIPPED = 0.048
TUNE_SETS = ("S50", "TB")
TEST_SETS = ("S51", "NEW")


@dataclass(frozen=True)
class Rule:
    """One row-4 candidate: which views, how they combine, the lead, the competitors, and the threshold."""

    name: str
    views: tuple[str, ...] = ("ret60",)
    weights: tuple[float, ...] = ()  # per view under fuse=mean; empty = equal
    fuse: str = "mean"  # mean: average scores; any: a view passing passes; all: every view passes the same genus
    lead: str = "gap"  # gap, rel (gap / pool score std), class (weak blockers discounted by `discount`)
    competitors: str = "eligible"  # eligible (all 23) or hunt (the hunt's 3 targets)
    discount: float = 0.0
    weak: str = ""  # blocker classes discounted under lead=class: "W1" animals-only + irrelevant, "W2" + stub
    drop: tuple[str, ...] = ()  # blockers removed from the pool (audited wrong-sense flags)
    tau: float | None = None  # fixed threshold; None = TUNE boundary
    step: float = 0.001
    extra: str = "0"  # extra BioCLIP passes per frame over today's 2 (reticle + full)
    note: str = ""


RULES = [
    Rule("R0 baseline", tau=SHIPPED, note="today: reticle, margin 0.048"),
    Rule("R0b baseline at TUNE boundary", note="today's rule, tau re-derived"),
    Rule(
        "R1 ret+full mean",
        ("ret60", "full"),
        extra="0",
        note="full frame already embedded for row 1",
    ),
    Rule("R2 ret+full any", ("ret60", "full"), fuse="any", extra="0"),
    Rule("R3 ret+full all", ("ret60", "full"), fuse="all", extra="0"),
    Rule("R4 ret flip mean", ("ret60", "ret60_flip"), extra="+1"),
    Rule(
        "R5 ret+full+flips mean",
        ("ret60", "full", "ret60_flip", "full_flip"),
        extra="+2",
    ),
    Rule("R6 multiscale 50-80", ("ret50", "ret60", "ret70", "ret80"), extra="+3"),
    Rule("R7 multiscale+full", ("ret50", "ret60", "ret70", "ret80", "full"), extra="+3"),
    Rule(
        "R8 3-frame streak (shipped AND)",
        ("ret60", "ret60_l", "ret60_r"),
        fuse="all",
        tau=SHIPPED,
        extra="0",
        note="proxy: today's 3-in-a-row on shaken frames",
    ),
    Rule("R9 3-frame mean", ("ret60", "ret60_l", "ret60_r"), extra="0 (always 3 frames)"),
    Rule(
        "R10 5-crop mean",
        ("ret60", "ret60_l", "ret60_r", "ret60_u", "ret60_d"),
        extra="0 (5 frames)",
    ),
    Rule(
        "R11 3-frame mean + full",
        ("ret60", "ret60_l", "ret60_r", "full"),
        extra="0 (always 3 frames)",
    ),
    Rule(
        "R12 3-frame+full+flip",
        ("ret60", "ret60_l", "ret60_r", "full", "ret60_flip"),
        extra="+1",
    ),
    Rule("R13 relative gap", lead="rel", step=0.01),
    Rule("R14 ret+full relative", ("ret60", "full"), lead="rel", step=0.01),
    Rule(
        "R15 hunt competitors",
        competitors="hunt",
        note="3 targets + blockers; rates averaged over hunts",
    ),
    Rule("R16 ret+full hunt", ("ret60", "full"), competitors="hunt"),
    Rule(
        "R17 drop wrong-sense flags",
        drop=(
            "Juglans nigra",
            "Berberis bealei",
            "Verbascum thapsus",
            "Myriophyllum aquaticum",
            "Vernonia noveboracensis",
            "Lespedeza capitata",
            "Cichorium intybus",
            "Rubus phoenicolasius",
        ),
        note="H11 audit unflag list; their photos still count as toxic",
    ),
]
for d in (0.01, 0.02, 0.03, 0.048):
    for weak in ("W1", "W2"):
        RULES.append(Rule(f"R18 weak {weak} -{d:g}", lead="class", weak=weak, discount=d))
        RULES.append(
            Rule(
                f"R19 ret+full weak {weak} -{d:g}",
                ("ret60", "full"),
                lead="class",
                weak=weak,
                discount=d,
            )
        )
RULES += [
    Rule(
        "R20 full only",
        ("full",),
        note="reticle only when TinyCLIP rejects the full frame",
    ),
    Rule("R21 ret+full 1:3", ("ret60", "full"), weights=(1, 3)),
    Rule("R22 ret+full 3:1", ("ret60", "full"), weights=(3, 1)),
    Rule("R23 ret+ret80+full", ("ret60", "ret80", "full"), extra="+1"),
    # Real headroom: the TUNE boundary plus 0.01 cosine, fixed after the first tune run.
    Rule("R0h baseline +0.01", tau=0.058, note="0.048 + 0.01"),
    Rule(
        "R1h ret+full mean +0.01",
        ("ret60", "full"),
        tau=0.034,
        note="R1 boundary 0.024 + 0.01",
    ),
    Rule(
        "R21h ret+full 1:3 +0.01",
        ("ret60", "full"),
        weights=(1, 3),
        tau=0.011,
        note="R21 boundary 0.001 + 0.01",
    ),
]
# Chosen from tune.log before `test` ran; see day-5.md.
FINALISTS: list[str] = [
    "R1 ret+full mean",
    "R1h ret+full mean +0.01",
    "R21h ret+full 1:3 +0.01",
]


def hunt() -> dict:
    """calibration.py's hunt plus local rows, floor rows, rule-B warning rows, and each blocker's audit class."""
    h = cal.hunt(emb.ASSETS)
    labels = json.loads((emb.ASSETS / "species_labels.json").read_text())
    rows = [r for r in csv.DictReader((cal.DAY2 / "threshold_regions.csv").open()) if r["place"] == tb.PLACE]
    local = np.zeros(len(labels), dtype=bool)
    local[list(tb.local_rows(rows, h["row_of"]))] = True
    floor = np.array([e["hazard_floor"] for e in labels])
    if floor.sum() != 7:
        raise ValueError(f"expected the fixed 7 floor hazards, got {floor.sum()}")
    audit = {r["species"]: r["reason_class"] for r in csv.DictReader((cal.OUT / "toxicity_audit.csv").open())}
    h |= {"local": local, "floor": floor, "rule_b": floor | (h["is_hazard"] & local),
          "cls": {i: "hazard" if h["is_hazard"][i] else audit.get(h["names"][i], "unaudited")
                  for i in h["blockers"]}}  # fmt: skip
    return h


def load(sets: tuple[str, ...], h: dict) -> list[dict]:
    """Photo records with metadata, views, scores against the pool, and row-1 and row-2 inputs."""
    pool = np.array(h["eligible"] + h["blockers"])
    out = []
    for name in sets:
        manifest, _ = emb.SETS[name]
        meta = list(csv.DictReader(manifest.open()))
        z = np.load(emb.CACHE / f"{name}.npz")
        if list(z["sha256"]) != [m["sha256"] for m in meta]:
            raise ValueError(f"{name} embeddings are stale; rerun embed.py")
        views = list(z["views"])
        for k, m in enumerate(meta):
            tb_kind = "toxic" if m.get("toxic") == "True" else "target"
            kind = tb_kind if name == "TB" else m["set"]
            row = h["row_of"].get(m["species"])
            e = z["emb"][k].astype(np.float64)
            full = h["table"] @ e[[views.index("ret60"), views.index("full")]].T  # (rows, 2) for row 1
            out.append({"set": name, "photo": m["photo"], "kind": kind, "species": m["species"], "row": row,
                        "genus": h["genus"][row] if row is not None else m["species"].split()[0],
                        "toxic": kind == "toxic" or (row is not None and bool(h["is_toxic"][row])),
                        "scores": {v: (h["table"][pool] @ e[j]) for j, v in enumerate(views)},
                        "whole": full, "share_ret": float(z["share_ret60"][k]),
                        "share_full": float(z["share_full"][k])})  # fmt: skip
    return out


def hazard_warn(p: dict, warn_rows: np.ndarray) -> bool:
    """Row 1: the best warning row ranks in the top 5 of the whole table on any crop the gate calls a plant."""
    rows = np.flatnonzero(warn_rows)
    for j, plant in ((0, p["share_ret"] > GATE), (1, p["share_full"] > GATE)):
        s = p["whole"][:, j]
        if plant and 1 + (s > s[rows].max()).sum() <= HAZARD_TOP:
            return True
    return False


class Scorer:
    """Turns a photo's pool scores into (L, genus) under a rule."""

    def __init__(self, h: dict) -> None:
        self.h = h
        self.n_e = len(h["eligible"])
        self.e_genus = [h["genus"][i] for i in h["eligible"]]
        self.b_names = [h["names"][i] for i in h["blockers"]]
        self.weak = {
            "W1": np.array([h["cls"][i] in ("animals-only", "irrelevant") for i in h["blockers"]]),
            "W2": np.array([h["cls"][i] in ("animals-only", "irrelevant", "stub") for i in h["blockers"]]),
        }
        genera = sorted(set(self.e_genus))
        by_genus = {g: [k for k in range(self.n_e) if self.e_genus[k] == g] for g in genera}
        # A hunt is 3 targets from 3 genera (core picks one per genus); every row of each genus is a combination.
        self.hunts = [list(c) for gs in itertools.combinations(genera, 3)
                      for c in itertools.product(*(by_genus[g] for g in gs))]  # fmt: skip

    def views(self, p: dict, rule: Rule) -> list[str]:
        """The rule's views; full-frame views drop out when TinyCLIP says the full frame isn't a plant (no embed)."""
        return [v for v in rule.views if not v.startswith("full") or p["share_full"] > GATE] or ["ret60"]

    def blocker_scores(self, s: np.ndarray, rule: Rule) -> np.ndarray:
        """Blocker scores after drops and weak-class discounts."""
        b = s[self.n_e :].copy()
        if rule.drop:
            b[[k for k, n in enumerate(self.b_names) if n in rule.drop]] = -np.inf
        if rule.lead == "class":
            b = b - rule.discount * self.weak[rule.weak]
        return b

    def lead(self, s: np.ndarray, rule: Rule, competitors: list[int] | None = None) -> tuple[float, str]:
        """L and the top eligible genus for one pool score vector; a tie for top-1 is no pass."""
        idx = competitors if competitors is not None else range(self.n_e)
        e = s[list(idx)]
        best = e.max()
        top = list(idx)[int(np.argmax(e))]
        if (e == best).sum() > 1:
            return -math.inf, self.e_genus[top]
        gap = best - self.blocker_scores(s, rule).max()
        if rule.lead == "rel":
            gap /= s[np.isfinite(s)].std()
        return float(gap), self.e_genus[top]

    def per_view(self, p: dict, rule: Rule, competitors: list[int] | None = None) -> tuple[float, str]:
        """(L, genus) after fusing the rule's views."""
        vs = self.views(p, rule)
        if rule.fuse == "mean":
            w = [rule.weights[rule.views.index(v)] for v in vs] if rule.weights and vs[0] in rule.views else None
            return self.lead(
                np.average([p["scores"][v] for v in vs], axis=0, weights=w),
                rule,
                competitors,
            )
        each = [self.lead(p["scores"][v], rule, competitors) for v in vs]
        if rule.fuse == "any":
            return max(each)
        if len({g for _, g in each}) > 1:
            return -math.inf, each[0][1]
        return min(each)[0], each[0][1]

    def judge(self, p: dict, rule: Rule) -> dict:
        """Row-4 outcome. For hunt competitors, rates over every hunt (own-target hunts for the own pass)."""
        if rule.competitors == "eligible":
            lead, g = self.per_view(p, rule)
            return {"L": lead, "g": g, "own": None, "wrong": None}
        own, wrong, worst = [], [], (-math.inf, "")
        for hunt in self.hunts:
            lead, g = self.per_view(p, rule, hunt)
            worst = max(worst, (lead, g))
            has_own = any(self.e_genus[k] == p["genus"] for k in hunt)
            if has_own:
                own.append((lead, g))
            wrong.append((lead, g))
        return {"L": worst[0], "g": worst[1], "own": own, "wrong": wrong}


def evaluate(photos: list[dict], rule: Rule, tau: float, scorer: Scorer, warn_key: str) -> list[dict]:
    """Per-photo row: L, genus, verdict, and own / wrong / toxic pass (fractions over hunts for hunt rules)."""
    rows = []
    for p in photos:
        j = scorer.judge(p, rule)
        warn, plant = p[warn_key], p["share_ret"] > GATE
        verdict_ok = plant and not warn
        if j["own"] is None:
            passes = j["L"] >= tau
            own = float(verdict_ok and passes and j["g"] == p["genus"])
            wrong = float(verdict_ok and passes and j["g"] != p["genus"])
        else:
            own = float(np.mean([lead >= tau and g == p["genus"] for lead, g in j["own"]])) if j["own"] else 0.0
            own *= verdict_ok
            wrong = verdict_ok * float(np.mean([lead >= tau and g != p["genus"] for lead, g in j["wrong"]]))
        rows.append({"rule": rule.name, "tau": f"{tau:.4f}", "set": p["set"], "photo": p["photo"], "kind": p["kind"],
                     "species": p["species"], "own_genus": p["genus"], "toxic": p["toxic"], "L": f"{j['L']:.6f}",
                     "top_genus": j["g"],
                     "hazard_warn": warn, "reticle_plant": plant, "pass_own": f"{own:.4f}",
                     "pass_wrong_genus": f"{wrong:.4f}", "toxic_pass_raw": p["toxic"] and j["L"] >= tau,
                     "toxic_pass_verdict": p["toxic"] and verdict_ok and j["L"] >= tau})  # fmt: skip
    return rows


def boundary(rows: list[dict], step: float) -> float:
    """Smallest multiple of `step` strictly above every toxic L."""
    worst = max(float(r["L"]) for r in rows if r["toxic"])
    return max(step, (math.floor(worst / step + 1e-9) + 1) * step)


def summary(rows: list[dict], tau: float) -> dict:
    """Aggregates for one rule over one split."""
    t = [r for r in rows if r["kind"] == "target"]
    x = [r for r in rows if r["toxic"]]
    np_ = [r for r in rows if r["kind"] == "non_plant"]
    other = [r for r in rows if r["kind"] in ("grass", "non_grass") and not r["toxic"]]
    toxic_l = [float(r["L"]) for r in x]
    worst = max(toxic_l) if toxic_l else -math.inf
    own = sum(float(r["pass_own"]) for r in t)
    return {"targets": len(t), "own": own, "own_by_set": {s: (sum(float(r["pass_own"]) for r in t if r["set"] == s),
            sum(r["set"] == s for r in t)) for s in sorted({r["set"] for r in t})},
            "toxic": len(x), "toxic_raw": sum(r["toxic_pass_raw"] for r in x),
            "toxic_verdict": sum(r["toxic_pass_verdict"] for r in x), "headroom": tau - worst,
            "worst_toxic": max(x, key=lambda r: float(r["L"]))["photo"] if x else "",
            "wrong": sum(float(r["pass_wrong_genus"]) for r in t),
            "non_plant": len(np_), "non_plant_pass": sum(float(r["pass_own"]) + float(r["pass_wrong_genus"])
                                                         for r in np_),
            "other": len(other), "other_pass": sum(float(r["pass_wrong_genus"]) for r in other)}  # fmt: skip


def header(mode: str, sets: tuple[str, ...]) -> None:
    """Run date, machine, versions, assets, and inputs."""
    print(f"# day-5 target pass, mode {mode}, run {datetime.now().astimezone().isoformat(timespec='minutes')}; "
          f"{platform.machine()} {platform.platform()}; python {sys.version.split()[0]}, onnxruntime "
          f"{ort.__version__}, numpy {np.__version__}, Pillow {PIL.__version__}")  # fmt: skip
    print(f"# BioCLIP pin {cal.pin('bioclip')['repo']}@{cal.pin('bioclip')['revision']}; TinyCLIP pin "
          f"{cal.pin('tinyclip')['repo']}@{cal.pin('tinyclip')['revision']}")  # fmt: skip
    for line in emb.ASSET_SHAS.read_text().splitlines():
        print(f"#   asset {line}")
    for s in sets:
        print(
            f"#   input {s}: {emb.SETS[s][0].relative_to(OUT.parents[3])} sha256 "
            f"{cal.file_sha256(emb.SETS[s][0])}; embeddings {emb.CACHE.relative_to(MODEL_CACHE.parent)}/{s}.npz"
        )
    print(
        f"#   hunt: {cal.DAY2.relative_to(OUT.parents[3])}/threshold_regions.csv, inat_species_oct.csv, "
        "day-3/toxicity_audit.csv"
    )


def fmt(n: float) -> str:
    """Integer counts print bare; hunt-averaged counts keep one decimal."""
    return f"{n:.0f}" if abs(n - round(n)) < 1e-9 else f"{n:.1f}"


def table(results: list[tuple[Rule, float, dict]], split: str) -> None:
    """One markdown row per rule."""
    print(f"\n| rule | tau | {split} target pass | by set | toxic raw / after gate+row1 | headroom | worst toxic | "
          "wrong-genus | non-plant | other safe plants | extra BioCLIP/frame |")  # fmt: skip
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for rule, tau, s in results:
        by = "; ".join(f"{k} {fmt(a)}/{b}" for k, (a, b) in s["own_by_set"].items())
        np_ = f"{fmt(s['non_plant_pass'])}/{s['non_plant']}" if s["non_plant"] else "-"
        other = f"{fmt(s['other_pass'])}/{s['other']}" if s["other"] else "-"
        print(f"| {rule.name} | {tau:.4f} | {fmt(s['own'])}/{s['targets']} ({s['own'] / s['targets']:.0%}) | {by} | "
              f"{s['toxic_raw']}/{s['toxic']} / {s['toxic_verdict']}/{s['toxic']} | {s['headroom']:+.4f} | "
              f"{s['worst_toxic']} | {fmt(s['wrong'])}/{s['targets']} ({s['wrong'] / s['targets']:.1%}) | {np_} | "
              f"{other} | {rule.extra} |")  # fmt: skip


def prepare(sets: tuple[str, ...]) -> tuple[dict, list[dict], Scorer]:
    """Hunt, photos with both row-1 variants, scorer."""
    h = hunt()
    photos = load(sets, h)
    for p in photos:
        p["warn_b"] = hazard_warn(p, h["rule_b"])
        p["warn_floor"] = hazard_warn(p, h["floor"])
    return h, photos, Scorer(h)


def write(path: Path, rows: list[dict]) -> None:
    """CSV with the first row's keys."""
    tb.write(path, rows)


def reproduce() -> int:
    """Oct 8 numbers with the Oct 8 row 1: S50 17/30, S51 16/30, TB 0/189 (33/69), S51 0/15 toxic, 0/57 false."""
    sets = ("S50", "TB", "S51")
    header("reproduce", sets)
    _, photos, scorer = prepare(sets)
    rule = RULES[0]
    rows = evaluate(photos, rule, SHIPPED, scorer, "warn_floor")
    by = {s: [r for r in rows if r["set"] == s] for s in sets}
    s50 = sum(float(r["pass_own"]) for r in by["S50"] if r["kind"] == "target")
    s51 = sum(float(r["pass_own"]) for r in by["S51"] if r["kind"] == "target")
    tb_pos = [r for r in by["TB"] if r["kind"] == "target"]
    tb_row4 = sum(float(r["L"]) >= SHIPPED and r["top_genus"] == p["genus"]
                  for r, p in zip(by["TB"], [p for p in photos if p["set"] == "TB"], strict=True)
                  if r["kind"] == "target")  # fmt: skip
    tox_tb = sum(r["toxic_pass_raw"] for r in by["TB"] if r["kind"] == "toxic")
    day2 = sum(r["toxic_pass_raw"] for r in by["TB"][:249] if r["kind"] == "toxic")
    s51_tox = sum(r["toxic_pass_verdict"] for r in by["S51"] if r["kind"] == "toxic")
    false = sum(float(r["pass_wrong_genus"]) for r in by["S51"] if r["kind"] == "target")
    false += sum(float(r["pass_own"]) + float(r["pass_wrong_genus"]) for r in by["S51"] if r["kind"] == "non_plant")
    print(f"S50 target pass {fmt(s50)}/30 (Oct 8: 17/30)")
    print(f"S51 target pass {fmt(s51)}/30 (Oct 8: 16/30)")
    print(f"TB row 4 only: target {tb_row4}/{len(tb_pos)} (Oct 8: 33/69); toxic {tox_tb}/189 (Oct 8: 0/189), "
          f"Day-2 180 {day2}/180 (Oct 7: 0/180)")  # fmt: skip
    print(f"S51 toxic pass {s51_tox}/15 (Oct 8: 0/15); false pass {fmt(false)}/57 (Oct 8: 0/57)")
    # Cross-check every per-photo field against the Oct 8 CSVs.
    mismatch = 0
    for name, csv_name in (("S50", "calibration.csv"), ("S51", "holdout.csv")):
        old = {r["photo"]: r for r in csv.DictReader((cal.OUT / csv_name).open())}
        for r in by[name]:
            o = old[r["photo"]]
            same = (abs(float(o["gap"]) - float(r["L"])) <= 5e-5 + 1e-9 or (o["top1_unique"] == "False")) and \
                (o["hazard_warn"] == str(r["hazard_warn"])) and (o["reticle_plant"] == str(r["reticle_plant"])) and \
                (o["top1_genus"] == r["top_genus"])  # fmt: skip
            if not same:
                mismatch += 1
                print(f"  MISMATCH {name} {r['photo']}: old gap {o['gap']} warn {o['hazard_warn']} plant "
                      f"{o['reticle_plant']} genus {o['top1_genus']}; new L {r['L']} warn {r['hazard_warn']} plant "
                      f"{r['reticle_plant']} genus {r['top_genus']}")  # fmt: skip
    old = {r["photo"]: r for r in csv.DictReader((cal.OUT / "toxic_block.csv").open()) if r["variant"] == "C-m0.048"}
    for r in by["TB"]:
        o = old[r["photo"]]
        if o["top1_toxic"] == "False" and abs(float(o["gap"]) - float(r["L"])) > 5e-6 + 1e-9:
            mismatch += 1
            print(f"  MISMATCH TB {r['photo']}: old gap {o['gap']} new L {r['L']}")
    print(f"per-photo mismatches against day-3 calibration.csv, holdout.csv, toxic_block.csv: {mismatch}")
    print("\nshipped row 1 (rule B, Oct 10) instead of the Oct 8 floor:")
    rows_b = evaluate(photos, rule, SHIPPED, scorer, "warn_b")
    for s in sets:
        t = [r for r in rows_b if r["set"] == s and r["kind"] == "target"]
        print(f"  {s} target pass {fmt(sum(float(r['pass_own']) for r in t))}/{len(t)}; warned by row 1 "
              f"{sum(r['hazard_warn'] for r in t)}")  # fmt: skip
    write(OUT / "reproduce.csv", rows + rows_b)
    return 0 if mismatch == 0 else 1


def run(mode: str) -> int:
    """Score rules on one split; TEST runs only the baseline and FINALISTS, with taus frozen from TUNE."""
    _, tune_photos, scorer = prepare(TUNE_SETS)
    taus = {}
    for rule in RULES:
        if rule.tau is not None:
            taus[rule.name] = rule.tau
        else:
            taus[rule.name] = boundary(evaluate(tune_photos, rule, 0.0, scorer, "warn_b"), rule.step)
    if mode == "tune":
        header(mode, TUNE_SETS)
        rules, photos = RULES, tune_photos
    else:
        if not FINALISTS:
            raise ValueError("pick FINALISTS from tune.log first")
        header(mode, TEST_SETS)
        rules = [r for r in RULES if r.name in ("R0 baseline", *FINALISTS)]
        _, photos, scorer = prepare(TEST_SETS)
    print("# row 1 = rule B (shipped Oct 10); taus frozen on TUNE; toxic raw = row 4 alone, the guardrail count")
    out, results = [], []
    for rule in rules:
        rows = evaluate(photos, rule, taus[rule.name], scorer, "warn_b")
        out += rows
        results.append((rule, taus[rule.name], summary(rows, taus[rule.name])))
    table(results, mode.upper())
    for rule, _, _ in results:
        if rule.note:
            print(f"- {rule.name}: {rule.note}")
    print("\nper-rule toxic L closest to tau (top 3) and lost targets:")
    for rule, tau, _ in results:
        rows = [r for r in out if r["rule"] == rule.name]
        near = sorted((r for r in rows if r["toxic"]), key=lambda r: -float(r["L"]))[:3]
        print(f"  {rule.name} tau {tau:.4f}: " + "; ".join(f"{r['photo']} {float(r['L']):.4f} ({r['top_genus']})"
                                                          for r in near))  # fmt: skip
    write(OUT / f"{mode}.csv", out)
    return 0


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "tune"
    sys.exit(reproduce() if mode == "reproduce" else run(mode))
