"""Day-3 rerun of Day 2's toxic-block probe with the app's synonym name matching; only name resolution changed.

Run from the repo root after make assets: uv --project pipeline run python -I docs/results/day-3/toxic_block.py

An iNat name now resolves to a species-table row by the row's scientific name or any of its `synonyms`
(species_labels.json, core NameIndex), and sightings of every iNat name hitting one row are summed (core LocalSpecies).
Eligible rule unchanged: summed count >= 0.5% of the pull's plant sightings and >= 3, a common name (the row's most
sighted iNat name's) of 3 words or fewer, not toxic-flagged or hazard. Blockers: every local toxic-flagged or hazard
row at any count. West Georgia October, threshold_regions.csv from Day 2. Rules:
  A: pool = the eligible species only
  C: pool = eligible + blockers; top-1 must be eligible and in the target's genus
  C-m<m>: C, and top-1 leads the best blocker by at least m (0.048 is the shipped TargetGoal.MARGIN);
  C-m*raw / C-m*hazard is the smallest m with 0 false passes, without and with row 1
A tie for top-1 among eligible rows is no pass (TargetGoal). Verify row 1 warns when a hazard row ranks in the top 5
of the whole table on the reticle crop; that frame never reaches row 4. Every number is reported with and without that
hazard step. A positive passes when its own species is a passing target; a negative is a false pass when any eligible
species would pass.

Photos: Day 2's manifest (docs/results/day-2/toxic_block_photos.csv), plus 3 iNaturalist research-grade CC0 photos for
each local toxic or hazard species that only synonym matching brings into the blocker set, fetched at about 1 request
per second under its iNat name. toxic_block_photos.csv here lists every photo used; later runs reuse it and check each
cached photo's SHA-256. Photos and reticle embeddings cache in .models/toxic-block-photos (gitignored), never
committed. Each photo's center 60% reticle crop (Pillow bicubic to 224, as Day 1) is embedded by the pinned BioCLIP
Mobile fp32 ONNX and scored by cosine (float64, as the app's dot product) against species_table.npy.
Writes toxic_block.csv (one row per photo and rule) and toxic_block_photos.csv.
"""

import csv
import hashlib
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image
from wild_find_pipeline.paths import GENERATED_ASSETS, MODEL_CACHE, TOXICITY, ensure_artifact
from wild_find_pipeline.reference import SIZE, USER_AGENT, center_crop, image_input

OUT = Path(__file__).resolve().parent
PHOTOS = MODEL_CACHE / "toxic-block-photos"
DAY2 = OUT.parent / "day-2"
MANIFEST = OUT / "toxic_block_photos.csv"
PLACE = "west-georgia-us"
SHARE = 0.005
MIN_SIGHTINGS = 3
PER_SPECIES = 3
API = "https://api.inaturalist.org/v1/observations"


def reticle(img: Image.Image) -> Image.Image:
    """Center 60% square crop resized to 224, as Day 1 cut its reticle crops."""
    side = int(min(img.size) * 0.6)
    return center_crop(img.convert("RGB"), side, side).resize((SIZE, SIZE), Image.Resampling.BICUBIC)


def get(url: str) -> bytes:
    """GET with the pipeline's User-Agent, then wait a second so iNat sees about 1 request per second."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        body = response.read()
    time.sleep(1)
    return body


def find_photos(name: str, taxon_id: str) -> list[dict]:
    """Up to PER_SPECIES research-grade observations of a taxon with a CC0 photo, one photo each."""
    query = {"taxon_id": taxon_id, "quality_grade": "research", "photo_license": "cc0", "per_page": 30}
    found = []
    for obs in json.loads(get(f"{API}?{urllib.parse.urlencode(query)}"))["results"]:
        if obs["taxon"]["name"] != name:  # taxon_id also matches subspecies and varieties
            continue
        photo = next((p for p in obs["photos"] if p.get("license_code") == "cc0"), None)
        if photo:
            url = photo["url"].replace("/square.", "/medium.")
            found.append({"observation": obs["id"], "photo_url": url, "license": "cc0"})
        if len(found) == PER_SPECIES:
            break
    return found


def fetch_photos(species: list[tuple[str, str]], taxon_ids: dict[str, str]) -> list[dict]:
    """Download PER_SPECIES CC0 photos per (table row name, iNat name) pair; manifest rows keyed by the row name."""
    PHOTOS.mkdir(parents=True, exist_ok=True)
    rows = []
    for name, inat in species:
        for i, found in enumerate(find_photos(inat, taxon_ids[inat])):
            file = f"{name.replace(' ', '_')}_{i}.jpg"
            data = get(found["photo_url"])
            (PHOTOS / file).write_bytes(data)
            rows.append({"photo": file, "species": name, "toxic": True, **found,
                         "sha256": hashlib.sha256(data).hexdigest()})  # fmt: skip
        print(f"  {name} (iNat {inat}): {sum(r['species'] == name for r in rows)} photos", file=sys.stderr)
    return rows


def write(path: Path, rows: list[dict]) -> None:
    """Write dict rows as CSV with the first row's keys as header."""
    with path.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def cached(rows: list[dict]) -> list[dict]:
    """Download any manifest photo missing from the cache and check every SHA-256."""
    PHOTOS.mkdir(parents=True, exist_ok=True)
    for row in rows:
        path = PHOTOS / row["photo"]
        if not path.exists():
            path.write_bytes(get(row["photo_url"]))
        if hashlib.sha256(path.read_bytes()).hexdigest() != row["sha256"]:
            raise ValueError(f"{path} does not match its manifest SHA-256")
    return rows


HAZARD_TOP = 5
MARGINS = (0.01, 0.02, 0.03, 0.04, 0.048, 0.05, 0.06, 0.08, 0.10)
SHIPPED = 0.048
EMBEDDINGS = PHOTOS / "reticle_embeddings.npz"


def embeddings(photos: list[dict]) -> np.ndarray:
    """Reticle embedding per photo, keyed by photo SHA-256 in a cache so reruns skip the ONNX pass."""
    saved = dict(np.load(EMBEDDINGS)) if EMBEDDINGS.exists() else {}
    missing = [p for p in photos if p["sha256"] not in saved]
    if missing:
        session = ort.InferenceSession(str(ensure_artifact("bioclip")), providers=["CPUExecutionProvider"])
        for p in missing:
            x = image_input(reticle(Image.open(PHOTOS / p["photo"])))
            saved[p["sha256"]] = session.run(None, {"image": x})[0][0]
        np.savez(EMBEDDINGS, **saved)
    return np.stack([saved[p["sha256"]] for p in photos])


def judge(scores: np.ndarray, pool: np.ndarray, toxic: np.ndarray, margin: float = 0.0) -> dict:
    """Top-1 of the pool, its best blocker and gap, and whether the rule lets top-1's genus pass."""
    order = pool[np.argsort(-scores[pool], kind="stable")]
    top = order[0]
    blockers = order[toxic[order]]
    best = blockers[0] if len(blockers) else None
    gap = scores[top] - scores[best] if best is not None else np.inf
    tied = (scores[order[~toxic[order]]] == scores[top]).sum() > 1
    return {"top": top, "best": best, "gap": gap, "ok": not toxic[top] and not tied and gap >= margin}


def local_rows(rows: list[dict], row_of: dict[str, int]) -> dict[int, list[dict]]:
    """iNat names grouped by the table row they resolve to, through the row's name or a synonym."""
    by_row: dict[int, list[dict]] = {}
    for r in rows:
        if r["name"] in row_of:
            by_row.setdefault(row_of[r["name"]], []).append(r)
    return by_row


def main() -> int:
    """Per photo and rule: top-1, best blocker, gap, hazard warning, and which eligible targets it would pass."""
    labels = json.loads((GENERATED_ASSETS / "species_labels.json").read_text())
    names = [e["scientific"] for e in labels]
    row_of = {alias: i for i, e in enumerate(labels) for alias in [e["scientific"], *e["synonyms"]]}
    if len(row_of) != sum(1 + len(e["synonyms"]) for e in labels):
        raise ValueError("a name maps to two species rows")
    table = np.load(GENERATED_ASSETS / "species_table.npy").astype(np.float64)
    toxic = {name: entry["toxic"] for name, entry in json.loads(TOXICITY.read_text())["species"].items()}
    is_toxic = np.array([toxic.get(n, True) or e["hazard"] for n, e in zip(names, labels, strict=True)])
    is_hazard = np.array([e["hazard"] for e in labels])
    genus = [e["genus"] for e in labels]
    taxon_ids = {r["name"]: r["taxon_id"] for r in csv.DictReader((DAY2 / "inat_species_oct.csv").open())}
    rows = [r for r in csv.DictReader((DAY2 / "threshold_regions.csv").open()) if r["place"] == PLACE]
    floor = max(MIN_SIGHTINGS, SHARE * sum(int(r["count"]) for r in rows))
    by_row = local_rows(rows, row_of)
    count = {i: sum(int(r["count"]) for r in rs) for i, rs in by_row.items()}
    common = {i: max(rs, key=lambda r: int(r["count"]))["common"].strip() for i, rs in by_row.items()}
    ranked = sorted(by_row, key=lambda i: (-count[i], i))
    eligible = [names[i] for i in ranked if count[i] >= floor and not is_toxic[i]
                and common[i] and len(common[i].split()) <= 3]  # fmt: skip
    blockers = [names[i] for i in ranked if is_toxic[i]]
    via_synonym = {names[i]: r["name"] for i in ranked for r in by_row[i] if r["name"] != names[i]}
    new = [n for n in blockers if n in via_synonym and all(r["name"] != n for r in by_row[row_of[n]])]
    targets: dict[str, list[str]] = {}
    for t in eligible:
        targets.setdefault(genus[row_of[t]], []).append(t)

    if not MANIFEST.exists():
        day2 = list(csv.DictReader((DAY2 / "toxic_block_photos.csv").open()))
        write(MANIFEST, day2 + fetch_photos([(n, via_synonym[n]) for n in new], taxon_ids))
    photos = cached(list(csv.DictReader(MANIFEST.open())))
    for photo in photos:
        photo["toxic"] = photo["toxic"] == "True"
    pos = [p for p in photos if not p["toxic"]]
    neg = [p for p in photos if p["toxic"]]
    if {p["species"] for p in pos} != set(eligible):
        raise ValueError("positive photos no longer match the eligible set")
    scores = embeddings(photos).astype(np.float64) @ table.T
    warns = [bool(is_hazard[np.argsort(-s)[:HAZARD_TOP]].any()) for s in scores]

    pool_a = np.array([row_of[n] for n in eligible])
    pool_c = np.array([row_of[n] for n in eligible + blockers])
    rules = {"A": (pool_a, 0.0), "C": (pool_c, 0.0)} | {f"C-m{m:g}": (pool_c, m) for m in MARGINS}
    # Smallest zero-false-pass margin: just above the largest gap any negative wins by, with and without row 1.
    c = [judge(s, pool_c, is_toxic) for s in scores]
    for tag, skip in (("raw", [False] * len(photos)), ("hazard", warns)):
        worst = max((j["gap"] for j, p, w in zip(c, photos, skip, strict=True)
                     if p["toxic"] and not w and j["ok"] and genus[j["top"]] in targets), default=0.0)  # fmt: skip
        rules[f"C-m*{tag}"] = (pool_c, float(np.nextafter(worst, np.inf)))

    out = []
    for photo, s, warn in zip(photos, scores, warns, strict=True):
        for name, (pool, m) in rules.items():
            j = judge(s, pool, is_toxic, m)
            passing = targets.get(genus[j["top"]], []) if j["ok"] else []
            out.append({"photo": photo["photo"], "species": photo["species"], "toxic": photo["toxic"],
                        "variant": name, "margin": f"{m:.6f}", "top1": names[j["top"]],
                        "top1_toxic": bool(is_toxic[j["top"]]),
                        "best_blocker": names[j["best"]] if j["best"] is not None else "",
                        "gap": f"{j['gap']:.6f}", "hazard_warn": warn,
                        "pass_any_target": bool(passing), "passing_targets": ";".join(passing),
                        "pass_own_target": photo["species"] in passing})  # fmt: skip
    write(OUT / "toxic_block.csv", out)

    print(f"{PLACE}: floor {floor:.1f} sightings; {len(by_row)} table rows matched by name or synonym "
          f"({len(via_synonym)} only via synonym: {'; '.join(f'{v} -> {k}' for k, v in via_synonym.items())})"
          )  # fmt: skip
    print(f"{len(eligible)} eligible species; {len(blockers)} local toxic or hazard blocker rows")
    print(f"blockers new through synonyms (3 photos each fetched): {'; '.join(new) or 'none'}")
    print(f"photos: {len(pos)} positive ({len({p['species'] for p in pos})} species), "
          f"{len(neg)} negative ({len({p['species'] for p in neg})} toxic species)")  # fmt: skip
    short = [
        n for n in {p["species"] for p in photos} | set(new) if sum(p["species"] == n for p in photos) < PER_SPECIES
    ]
    print(f"species with fewer than {PER_SPECIES} CC0 photos: {'; '.join(sorted(short)) or 'none'}")
    print(f"row-1 hazard warning (hazard row in whole-table top {HAZARD_TOP}): "
          f"{sum(w for p, w in zip(photos, warns, strict=True) if not p['toxic'])} of {len(pos)} positives, "
          f"{sum(w for p, w in zip(photos, warns, strict=True) if p['toxic'])} of {len(neg)} negatives")  # fmt: skip

    print("rule | pool rows | margin | target pass (raw / after row 1) | toxic false pass (raw / after row 1)")
    for name, (pool, m) in rules.items():
        v = [r for r in out if r["variant"] == name]
        p = [r for r in v if not r["toxic"]]
        n = [r for r in v if r["toxic"]]
        won = (sum(r["pass_own_target"] for r in p), sum(r["pass_own_target"] and not r["hazard_warn"] for r in p))
        bad = (sum(r["pass_any_target"] for r in n), sum(r["pass_any_target"] and not r["hazard_warn"] for r in n))
        flag = " (shipped)" if m == SHIPPED else ""
        print(f"{name}{flag} | {len(pool)} | {m:.4f} | {won[0]}/{len(p)} ({won[0] / len(p):.0%}) / {won[1]}/{len(p)} "
              f"({won[1] / len(p):.0%}) | {bad[0]}/{len(n)} / {bad[1]}/{len(n)}")  # fmt: skip
    for name in rules:
        for r in out:
            if r["variant"] == name and r["toxic"] and r["pass_any_target"]:
                print(f"  {name} FALSE PASS {r['photo']} top1 {r['top1']} gap {float(r['gap']):.4f} "
                      f"passes {r['passing_targets']}"
                      + (" (row-1 hazard warns first)" if r["hazard_warn"] else ""))  # fmt: skip
    for r in out:
        if r["variant"] == f"C-m{SHIPPED:g}" and not r["toxic"] and not r["pass_own_target"]:
            print(f"  shipped loses {r['photo']}: top1 {r['top1']}, best blocker {r['best_blocker']} "
                  f"gap {float(r['gap']):.4f}" + (", row-1 hazard warns" if r["hazard_warn"] else ""))  # fmt: skip
    return 0


if __name__ == "__main__":
    sys.exit(main())
