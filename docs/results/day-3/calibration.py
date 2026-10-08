"""S50 calibration and PRD hole 3: the shipped per-capture verify on fresh CC0 still photos, laptop side.

Run from the repo root after make assets: uv --project pipeline run python -I docs/results/day-3/calibration.py

Verify path per photo, as core FrameVerifier/VerifyStreak/TargetGoal/TutorialGoal run it on a capture frame:
  crops: full frame = center square of the shorter edge; reticle = center square of int(0.6 * shorter edge); each Pillow
    bicubic to 224 (core Crops, Day 1)
  TinyCLIP plant gate (plant_gate.onnx + plant_gate.json): softmax over logit_scale x cosine, plant share > 0.5, on both
  BioCLIP Mobile fp32 on each region the gate calls a plant
  row 1: a hazard row ranks in the top 5 of the whole species table (1 + rows scoring above the best hazard) on any
    plant region; row 2: reticle not a plant; row 3 (focus) cannot be simulated on a still, so every photo counts as
    focused; row 4: TargetGoal, pool = West Georgia October eligible + every local toxic or hazard blocker (names
    matched through species_labels.json synonyms, as toxic_block.py), top-1 among eligible shares the target's genus
    and leads the best blocker by >= 0.048, a tie is no pass. A still repeats the same frame, so 3 frames in a row
    change nothing.
  tutorial (hole 3): reticle plant gate and Poaceae in the top 3 of the 11 labels.npy rows; no hazard row (R3).

Photos: iNaturalist research-grade observations with a CC0 photo, one photo per observation, taxon name exact, never
an observation, photo, or SHA-256 named anywhere in docs/results (Day 1, Day 2, Day 3 toxic-block sets). Downloaded at
about 1 request per second into .models/calibration-photos (gitignored); calibration.photos.csv records species,
observation, URL, license, and SHA-256, and later runs reuse it and check each SHA-256. Bundled assets are copied once
into .models/calibration-assets and checked against their SHA-256 so a concurrent make assets cannot change them
mid-run. Writes calibration.csv (one verify row per photo) and calibration.photos.csv; prints the summary.
"""

import csv
import hashlib
import importlib.util
import json
import re
import shutil
import sys
import urllib.parse
from datetime import date
from pathlib import Path

import numpy as np
import onnxruntime as ort
import PIL
from PIL import Image
from wild_find_pipeline.paths import GENERATED_ASSETS, LABELS_DIR, MODEL_CACHE, file_sha256, pin
from wild_find_pipeline.reference import SIZE, center_crop, image_input

OUT = Path(__file__).resolve().parent
RESULTS = OUT.parent
DAY2 = RESULTS / "day-2"
_spec = importlib.util.spec_from_file_location("toxic_block", OUT / "toxic_block.py")
tb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tb)

PHOTOS = MODEL_CACHE / "calibration-photos"
ASSETS = MODEL_CACHE / "calibration-assets"
MANIFEST = OUT / "calibration.photos.csv"
API = "https://api.inaturalist.org/v1/observations"
GATE_THRESHOLD = 0.5
HAZARD_TOP = 5
MARGIN = 0.048
TUTORIAL_TOP = 3
RUN_DATE = date(2026, 10, 8)
CALIBRATION_EXTRA = 7  # second photo for the 7 most-sighted targets: 23 + 7 = 30
GRASS_PHOTOS = 10
NON_GRASS = (
    "Trifolium repens",
    "Taraxacum officinale",
    "Plantago lanceolata",
    "Oxalis stricta",
    "Quercus alba",
    "Acer rubrum",
    "Lonicera japonica",
    "Rubus",
    "Polystichum acrostichoides",
    "Carex",
)
ASSET_FILES = {
    "flora_student_fp32.onnx": GENERATED_ASSETS,
    "plant_gate.onnx": GENERATED_ASSETS,
    "plant_gate.json": GENERATED_ASSETS,
    "species_table.npy": GENERATED_ASSETS,
    "species_labels.json": GENERATED_ASSETS,
    "labels.npy": LABELS_DIR,
    "labels.json": LABELS_DIR,
}


def snapshot_assets() -> dict[str, str]:
    """Copy the bundled assets once into ASSETS and return each file's SHA-256; BioCLIP must match its pin."""
    ASSETS.mkdir(parents=True, exist_ok=True)
    shas = {}
    for name, src in ASSET_FILES.items():
        dst = ASSETS / name
        shutil.copyfile(src / name, dst)
        shas[name] = file_sha256(dst)
        if file_sha256(src / name) != shas[name]:
            raise ValueError(f"{name} changed while it was copied; rerun")
    if shas["flora_student_fp32.onnx"] != pin("bioclip")["sha256"]:
        raise ValueError("bundled BioCLIP does not match its pin")
    return shas


def used_elsewhere() -> tuple[set[int], set[int], set[str], set[str]]:
    """Observation ids, iNat photo ids, SHA-256s, and Commons file pages named in any docs/results file but ours."""
    obs, photos, shas, pages = set(), set(), set(), set()
    for path in RESULTS.rglob("*"):
        if not path.is_file() or path.name.startswith(("calibration", "holdout")) or path.suffix in (".pyc", ".png"):
            continue
        text = path.read_text(errors="ignore")
        obs |= {int(m) for m in re.findall(r"observations/(\d+)", text)}
        photos |= {int(m) for m in re.findall(r"photos/(\d+)/", text)}
        shas |= set(re.findall(r"\b[0-9a-f]{64}\b", text))
        found = re.findall(r"commons\.wikimedia\.org/wiki/(File:[^\s,\"']+)", text)
        pages |= {urllib.parse.unquote(p) for p in found}
        if path.suffix in (".csv", ".tsv"):
            delimiter = "\t" if path.suffix == ".tsv" else ","
            for row in csv.DictReader(path.open(), delimiter=delimiter):
                for key in ("observation", "observation_id", "obs_id"):
                    if (row.get(key) or "").isdigit():
                        obs.add(int(row[key]))
    return obs, photos, shas, pages


def inat_candidates(name: str, taxon_id: str | None, skip_obs: set[int], skip_photos: set[int]) -> list[dict]:
    """Research-grade observations of exactly `name` with a CC0 photo, oldest ids skipped, one photo each."""
    query = {"quality_grade": "research", "photo_license": "cc0", "per_page": 100, "order_by": "id"}
    query |= {"taxon_id": taxon_id} if taxon_id else {"taxon_name": name}
    found = []
    for obs in json.loads(tb.get(f"{API}?{urllib.parse.urlencode(query)}"))["results"]:
        if obs["id"] in skip_obs:
            continue
        photo = next((p for p in obs["photos"] if p.get("license_code") == "cc0"), None)
        if photo is None or photo["id"] in skip_photos:
            continue
        found.append({"taxon": obs["taxon"]["name"], "rank": obs["taxon"]["rank"], "observation": obs["id"],
                      "photo_id": photo["id"], "photo_url": photo["url"].replace("/square.", "/medium."),
                      "license": "cc0", "source": f"https://www.inaturalist.org/observations/{obs['id']}"})  # fmt: skip
    return found


def download(rows: list[dict], photos: Path, skip_shas: set[str]) -> list[dict]:
    """Download each row's photo into `photos`, skipping any SHA-256 used before; adds `photo`, `sha256`."""
    photos.mkdir(parents=True, exist_ok=True)
    kept = []
    for row in rows:
        data = tb.get(row["photo_url"])
        sha = hashlib.sha256(data).hexdigest()
        if sha in skip_shas:
            continue
        skip_shas.add(sha)
        file = row.pop("file", None) or f"{row['set']}_{row['species'].replace(' ', '_')}_{row['observation']}.jpg"
        (photos / file).write_bytes(data)
        kept.append({"photo": file, **row, "sha256": sha})
    return kept


def cached(rows: list[dict], photos: Path) -> list[dict]:
    """Download any manifest photo missing from `photos` and check every SHA-256."""
    photos.mkdir(parents=True, exist_ok=True)
    for row in rows:
        path = photos / row["photo"]
        if not path.exists():
            path.write_bytes(tb.get(row["photo_url"]))
        if hashlib.sha256(path.read_bytes()).hexdigest() != row["sha256"]:
            raise ValueError(f"{path} does not match its manifest SHA-256")
    return rows


def hunt(assets: Path) -> dict:
    """West Georgia October hunt as toxic_block.py builds it: table, flags, eligible and blocker rows, iNat names."""
    labels = json.loads((assets / "species_labels.json").read_text())
    names = [e["scientific"] for e in labels]
    row_of = {alias: i for i, e in enumerate(labels) for alias in [e["scientific"], *e["synonyms"]]}
    if len(row_of) != sum(1 + len(e["synonyms"]) for e in labels):
        raise ValueError("a name maps to two species rows")
    is_hazard = np.array([e["hazard"] for e in labels])
    is_toxic = np.array([e["toxic"] or e["hazard"] for e in labels])
    genus = [e["genus"] for e in labels]
    taxon_ids = {r["name"]: r["taxon_id"] for r in csv.DictReader((DAY2 / "inat_species_oct.csv").open())}
    rows = [r for r in csv.DictReader((DAY2 / "threshold_regions.csv").open()) if r["place"] == tb.PLACE]
    floor = max(tb.MIN_SIGHTINGS, tb.SHARE * sum(int(r["count"]) for r in rows))
    by_row = tb.local_rows(rows, row_of)
    count = {i: sum(int(r["count"]) for r in rs) for i, rs in by_row.items()}
    common = {i: max(rs, key=lambda r: int(r["count"]))["common"].strip() for i, rs in by_row.items()}
    inat = {i: max(rs, key=lambda r: int(r["count"]))["name"] for i, rs in by_row.items()}
    ranked = sorted(by_row, key=lambda i: (-count[i], i))
    eligible = [i for i in ranked if count[i] >= floor and not is_toxic[i] and common[i]
                and len(common[i].split()) <= 3]  # fmt: skip
    blockers = [i for i in ranked if is_toxic[i]]
    return {"names": names, "row_of": row_of, "is_hazard": is_hazard, "is_toxic": is_toxic, "genus": genus,
            "table": np.load(assets / "species_table.npy").astype(np.float64), "eligible": eligible,
            "blockers": blockers, "count": count, "common": common, "inat": inat, "taxon_ids": taxon_ids}  # fmt: skip


class Verifier:
    """Laptop copy of the phone's per-frame verify on one still photo."""

    def __init__(self, assets: Path, h: dict) -> None:
        provider = ["CPUExecutionProvider"]
        self.gate = ort.InferenceSession(str(assets / "plant_gate.onnx"), providers=provider)
        self.bioclip = ort.InferenceSession(str(assets / "flora_student_fp32.onnx"), providers=provider)
        gate = json.loads((assets / "plant_gate.json").read_text())
        self.gate_vectors = np.array([g["vector"] for g in gate["labels"]], dtype=np.float64)
        self.gate_plant = np.array([g["plant"] for g in gate["labels"]])
        self.scale = gate["logit_scale"]
        self.tutorial = np.load(assets / "labels.npy").astype(np.float64)
        self.tutorial_names = [e["scientific"] for e in json.loads((assets / "labels.json").read_text())["labels"]]
        self.h = h
        self.pool = np.array(h["eligible"] + h["blockers"])

    def share(self, x: np.ndarray) -> float:
        """TinyCLIP plant share (core PlantGate)."""
        e = self.gate.run(None, {"image": x})[0][0].astype(np.float64)
        logits = self.scale * (self.gate_vectors @ e)
        w = np.exp(logits - logits.max())
        return float(w[self.gate_plant].sum() / w.sum())

    def embed(self, x: np.ndarray) -> np.ndarray:
        """BioCLIP unit embedding; a non-finite one fails like core FrameVerifier."""
        e = self.bioclip.run(None, {"image": x})[0][0]
        if not np.isfinite(e).all():
            raise ValueError("BioCLIP embedding is not finite")
        return e.astype(np.float64)

    def hazard_rank(self, e: np.ndarray) -> tuple[int, str]:
        """Rank of the best hazard row over the whole table (core HazardCheck: ties warn)."""
        s = self.h["table"] @ e
        hazard_rows = np.flatnonzero(self.h["is_hazard"])
        best = hazard_rows[np.argmax(s[hazard_rows])]
        return int(1 + (s > s[best]).sum()), self.h["names"][best]

    def run(self, path: Path) -> dict:
        """Every intermediate of one capture frame: gate shares, hazard ranks, row 4 pool, tutorial rank, verdict."""
        img = Image.open(path).convert("RGB")
        side = min(img.size)
        full = center_crop(img, side, side).resize((SIZE, SIZE), Image.Resampling.BICUBIC)
        r_x, f_x = image_input(tb.reticle(img)), image_input(full)
        r_share, f_share = self.share(r_x), self.share(f_x)
        r_plant, f_plant = r_share > GATE_THRESHOLD, f_share > GATE_THRESHOLD
        r_emb = self.embed(r_x)  # also scored when the gate says no, for the record; the verdict ignores it then
        r_rank, r_hazard = self.hazard_rank(r_emb)
        f_rank, f_hazard = self.hazard_rank(self.embed(f_x)) if f_plant else (0, "")
        warn = (r_plant and r_rank <= HAZARD_TOP) or (f_plant and f_rank <= HAZARD_TOP)
        h, names, genus = self.h, self.h["names"], self.h["genus"]
        s = h["table"] @ r_emb
        elig, blk = np.array(h["eligible"]), np.array(h["blockers"])
        best = s[elig].max()
        top = int(elig[np.argmax(s[elig])])  # first maximum, as TargetGoal's indexOf
        blocker = int(blk[np.argmax(s[blk])])
        gap = best - s[blocker]
        unique = int((s[elig] == best).sum()) == 1
        met = unique and gap >= MARGIN  # TargetGoal for any target in top's genus; top's genus is always a target's
        if met != tb.judge(s, self.pool, h["is_toxic"], MARGIN)["ok"]:
            raise ValueError("TargetGoal copy disagrees with toxic_block.judge")
        pool_top = int(self.pool[np.argmax(s[self.pool])])
        t = self.tutorial @ r_emb
        grass = self.tutorial_names.index("Poaceae")
        grass_rank = int(1 + (t > t[grass]).sum())
        verdict = "hazard" if warn else "not_plant" if not r_plant else "goal_met" if met else "no_match"
        return {"reticle_share": f"{r_share:.4f}", "full_share": f"{f_share:.4f}", "reticle_plant": r_plant,
                "full_plant": f_plant, "reticle_hazard_rank": r_rank, "reticle_best_hazard": r_hazard,
                "full_hazard_rank": f_rank, "full_best_hazard": f_hazard, "hazard_warn": warn,
                "top1": names[top], "top1_genus": genus[top], "top1_score": f"{best:.4f}", "top1_unique": unique,
                "best_blocker": names[blocker], "blocker_score": f"{s[blocker]:.4f}", "gap": f"{gap:.4f}",
                "margin_ok": met, "pool_top1": names[pool_top], "whole_table_top1": names[int(np.argmax(s))],
                "tutorial_top1": self.tutorial_names[int(np.argmax(t))], "grass_rank": grass_rank,
                "tutorial_pass": r_plant and grass_rank <= TUTORIAL_TOP, "verdict": verdict,
                "found_any": verdict == "goal_met"}  # fmt: skip


def write(path: Path, rows: list[dict]) -> None:
    """Write dict rows as CSV with the first row's keys as header."""
    tb.write(path, rows)


def header(shas: dict[str, str], manifests: list[Path]) -> None:
    """Print run date, model and asset SHA-256s, package versions, and manifest SHA-256s."""
    print(f"run date {RUN_DATE.isoformat()}; laptop CPU, onnxruntime {ort.__version__}, numpy {np.__version__}, "
          f"Pillow {PIL.__version__}, python {sys.version.split()[0]}")  # fmt: skip
    print(f"BioCLIP pin {pin('bioclip')['repo']}@{pin('bioclip')['revision']}; TinyCLIP pin "
          f"{pin('tinyclip')['repo']}@{pin('tinyclip')['revision']}")  # fmt: skip
    for name, sha in shas.items():
        print(f"  asset {name} sha256 {sha}")
    for m in manifests:
        print(f"  input {m.relative_to(RESULTS.parent.parent)} sha256 {file_sha256(m)}")
    print("focus (verify rows 3 and 5) cannot be simulated on still photos: every photo is treated as focused")


def build_manifest(h: dict) -> list[dict]:
    """Fresh calibration photos: 1 per eligible target (2 for the 7 most sighted), 10 grass, 10 non-grass plants."""
    skip_obs, skip_photos, skip_shas, _ = used_elsewhere()
    wanted = []
    for k, i in enumerate(h["eligible"]):
        name = h["inat"][i]
        found = [c for c in inat_candidates(name, h["taxon_ids"][name], skip_obs, skip_photos) if c["taxon"] == name]
        take = 2 if k < CALIBRATION_EXTRA else 1
        wanted += [{"set": "target", "species": h["names"][i], **c} for c in found[:take]]
        skip_obs |= {c["observation"] for c in found[:take]}
        print(f"  target {h['names'][i]}: {min(take, len(found))} of {take} ({len(found)} candidates)", file=sys.stderr)
    genera = set()
    for c in inat_candidates("Poaceae", "47434", skip_obs, skip_photos):
        g = c["taxon"].split()[0]
        if c["rank"] in ("species", "genus") and g not in genera and len(genera) < GRASS_PHOTOS:
            genera.add(g)
            wanted.append({"set": "grass", "species": c["taxon"], **c})
            skip_obs.add(c["observation"])
    for name in NON_GRASS:
        found = inat_candidates(name, None, skip_obs, skip_photos)
        found = [c for c in found if c["taxon"].split()[0] == name.split()[0]]
        wanted += [{"set": "non_grass", "species": c["taxon"], **c} for c in found[:1]]
        skip_obs |= {c["observation"] for c in found[:1]}
    return download(wanted, PHOTOS, skip_shas)


def summarize_targets(rows: list[dict]) -> None:
    """S50: genus-pass rate at the shipped rule, misses by cause, and score distributions for a floor decision."""
    t = [r for r in rows if r["set"] == "target"]
    passed = [r for r in t if r["pass_own"]]
    print(f"\nS50 calibration: {len(t)} target photos, {len({r['species'] for r in t})} species")
    print(f"genus pass at the shipped rule (rows 1-4): {len(passed)}/{len(t)} ({len(passed) / len(t):.0%})")
    for cause in ("hazard", "not_plant", "no_match"):
        print(f"  lost to {cause}: {sum(r['verdict'] == cause for r in t)}")
    wrong = [r for r in t if r["verdict"] == "goal_met" and not r["pass_own"]]
    print(f"  margin met but top-1 in another target's genus (would pass that target): {len(wrong)}")
    print(f"row-1 hazard warning on target photos: {sum(r['hazard_warn'] for r in t)}/{len(t)}")
    blocked = sum(r["pool_top1"] == r["best_blocker"] for r in t)
    print(f"a blocker tops the whole pool: {blocked}/{len(t)}; top-1 eligible is the right genus: "
          f"{sum(r['right_genus'] for r in t)}/{len(t)}")  # fmt: skip

    def q(xs: list[float]) -> str:
        if not xs:
            return "none"
        return " / ".join(f"{v:.3f}" for v in np.quantile(xs, [0, 0.25, 0.5, 0.75, 1]))

    groups = {
        "passes (right genus, margin met)": passed,
        "right genus, margin missed": [r for r in t if r["right_genus"] and not r["margin_ok"]],
        "wrong genus, margin met": wrong,
        "wrong genus, margin missed": [r for r in t if not r["right_genus"] and not r["margin_ok"]],
    }
    print("top-1 score min/q1/median/q3/max:")
    for label, group in groups.items():
        print(f"  {label} ({len(group)}): {q([float(r['top1_score']) for r in group])}")
    print("gap to best blocker min/q1/median/q3/max:")
    print(f"  right genus: {q([float(r['gap']) for r in t if r['right_genus']])}")
    print(f"  wrong genus: {q([float(r['gap']) for r in t if not r['right_genus']])}")
    for r in t:
        if not r["pass_own"]:
            print(f"  miss {r['photo']}: {r['verdict']}, top1 {r['top1']} {r['top1_score']}, pool top1 "
                  f"{r['pool_top1']}, blocker "
                  f"{r['best_blocker']} gap {r['gap']}, reticle share {r['reticle_share']}, hazard ranks "
                  f"{r['reticle_hazard_rank']}/{r['full_hazard_rank']} ({r['reticle_best_hazard']})")  # fmt: skip


def summarize_tutorial(rows: list[dict]) -> None:
    """Hole 3: tutorial pass on grass vs non-grass plants."""
    for group in ("grass", "non_grass"):
        g = [r for r in rows if r["set"] == group]
        ok = sum(r["tutorial_pass"] for r in g)
        ranks = [r["grass_rank"] for r in g]
        print(f"hole 3 {group}: tutorial pass {ok}/{len(g)}; reticle plant {sum(r['reticle_plant'] for r in g)}/"
              f"{len(g)}; Poaceae rank counts {dict(sorted({k: ranks.count(k) for k in ranks}.items()))}")  # fmt: skip
        for r in g:
            print(f"  {r['photo']}: grass rank {r['grass_rank']}, tutorial top1 {r['tutorial_top1']}, reticle share "
                  f"{r['reticle_share']}, pass {r['tutorial_pass']}")  # fmt: skip


def main() -> int:
    """Run S50 calibration and the hole-3 tutorial check; write calibration.csv."""
    shas = snapshot_assets()
    h = hunt(ASSETS)
    if len(h["eligible"]) != 23:
        raise ValueError(f"expected 23 eligible West Georgia targets, got {len(h['eligible'])}")
    if not MANIFEST.exists():
        write(MANIFEST, build_manifest(h))
    photos = cached(list(csv.DictReader(MANIFEST.open())), PHOTOS)
    header(shas, [MANIFEST, DAY2 / "threshold_regions.csv", DAY2 / "inat_species_oct.csv"])
    print(f"West Georgia October: {len(h['eligible'])} eligible, {len(h['blockers'])} local toxic or hazard blockers")
    v = Verifier(ASSETS, h)
    out = []
    for p in photos:
        r = {k: p[k] for k in ("photo", "set", "species", "observation")} | v.run(PHOTOS / p["photo"])
        own = h["row_of"].get(p["species"])
        r["right_genus"] = own is not None and r["top1_genus"] == h["genus"][own]
        r["pass_own"] = p["set"] == "target" and r["found_any"] and r["right_genus"]
        out.append(r)
    write(OUT / "calibration.csv", out)
    summarize_targets(out)
    print()
    summarize_tutorial(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
