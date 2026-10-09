"""Day-2 probe: which verify rule lets no toxic plant pass as a hunt target, and what does each cost real targets?

Run from the repo root after make assets: uv --project pipeline run python -I docs/results/day-2/toxic_block.py

Verify row 4 passes when genus(top1(eligible_rows, reticle)) == target genus. Toxic-flagged species are kept out of
eligible_rows, so a photo of a toxic plant can only land on a non-toxic row. Rules tested, all West Georgia October
(threshold_regions.csv); toxic rows are blockers, never targets, and a blocker at top-1 fails the frame even in-genus:
  A: pool = the 23 eligible species (today's rule; playable_species.py)
  B: A plus toxic-flagged local species clearing the same 0.5% / 3 floor
  C: A plus every toxic-flagged local species with a species-table row
  C-vetoK: pool C, and no blocker anywhere in the pool's top K
  C-m<m>: pool C, and top-1 beats the best blocker by at least m; C-m*... is the smallest m with 0 false passes
  T, T-vetoK: pool = A plus every toxic-flagged row in the whole table
  W: top-1 over the whole table is in the target's genus and not toxic-flagged
Hazard species are toxic-flagged, so they count as toxic too. Verify row 1 warns when a hazard row ranks in the top 5
of the whole table on the reticle crop; that frame never reaches row 4. Every number is reported with and without that
hazard step. A positive passes when its own species is a passing target; a negative is a false pass when any of the 23
eligible species would pass.

Photos: 3 iNaturalist research-grade CC0 photos per eligible species (positives) and per toxic species (negatives:
every toxic species clearing the floor, then the most-sighted others up to NEGATIVE_SPECIES). The first run queries
api.inaturalist.org at about 1 request per second and writes toxic_block_photos.csv; later runs reuse that manifest
and check each cached photo's SHA-256. Photos and their reticle embeddings cache in .models/toxic-block-photos
(gitignored), never committed. Each photo's center 60% reticle crop (Pillow bicubic to 224, as Day 1) is embedded by
the pinned BioCLIP Mobile fp32 ONNX and scored by cosine against species_table.npy.
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
MANIFEST = OUT / "toxic_block_photos.csv"
PLACE = "west-georgia-us"
SHARE = 0.005
MIN_SIGHTINGS = 3
PER_SPECIES = 3
NEGATIVE_SPECIES = 60
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


def build_manifest(species: list[tuple[str, bool]], taxon_ids: dict[str, str]) -> None:
    """Query iNat for every species' photos, download them, and record each photo's SHA-256."""
    PHOTOS.mkdir(parents=True, exist_ok=True)
    rows = []
    for name, toxic in species:
        for i, found in enumerate(find_photos(name, taxon_ids[name])):
            file = f"{name.replace(' ', '_')}_{i}.jpg"
            data = get(found["photo_url"])
            (PHOTOS / file).write_bytes(data)
            rows.append({"photo": file, "species": name, "toxic": toxic, **found,
                         "sha256": hashlib.sha256(data).hexdigest()})  # fmt: skip
        print(f"  {name}: {sum(r['species'] == name for r in rows)} photos", file=sys.stderr)
    with MANIFEST.open("w", newline="") as f:
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
VETO_K = (2, 3, 5)
MARGINS = (0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.08, 0.10)
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


def judge(scores: np.ndarray, pool: np.ndarray, toxic: np.ndarray, k: int = 1, margin: float = 0.0) -> dict:
    """Top-1 of the pool, its best blocker and gap, and whether the rule lets top-1's genus pass."""
    order = pool[np.argsort(-scores[pool])]
    top = order[0]
    blockers = order[toxic[order]]
    best = blockers[0] if len(blockers) else None
    gap = scores[top] - scores[best] if best is not None else np.inf
    ok = not toxic[order[:k]].any() and gap >= margin
    return {"top": top, "best": best, "gap": gap, "ok": ok}


def main() -> int:
    """Per photo and rule: top-1, best blocker, gap, hazard warning, and which eligible targets it would pass."""
    labels = json.loads((GENERATED_ASSETS / "species_labels.json").read_text())
    index = {e["scientific"]: i for i, e in enumerate(labels)}
    names = [e["scientific"] for e in labels]
    table = np.load(GENERATED_ASSETS / "species_table.npy")
    toxic = {name: entry["toxic"] for name, entry in json.loads(TOXICITY.read_text())["species"].items()}
    taxon_ids = {r["name"]: r["taxon_id"] for r in csv.DictReader((OUT / "inat_species_oct.csv").open())}
    rows = [r for r in csv.DictReader((OUT / "threshold_regions.csv").open()) if r["place"] == PLACE]
    floor = max(MIN_SIGHTINGS, SHARE * sum(int(r["count"]) for r in rows))
    matched = [r for r in rows if r["name"] in index]
    eligible = [r["name"] for r in matched if int(r["count"]) >= floor and len(r["common"].split()) <= 3
                and not labels[index[r["name"]]]["hazard"] and not toxic.get(r["name"], True)]  # fmt: skip
    toxic_all = [r["name"] for r in sorted(matched, key=lambda r: -int(r["count"])) if toxic.get(r["name"], True)]
    toxic_floor = [r["name"] for r in matched if r["name"] in toxic_all and int(r["count"]) >= floor]
    negatives = toxic_floor + [n for n in toxic_all if n not in toxic_floor][: NEGATIVE_SPECIES - len(toxic_floor)]
    genus = [e["genus"] for e in labels]
    is_toxic = np.array([toxic.get(n, True) for n in names])
    is_hazard = np.array([e["hazard"] for e in labels])
    targets = {g: [t for t in eligible if genus[index[t]] == g] for g in {genus[index[t]] for t in eligible}}

    if not MANIFEST.exists():
        build_manifest([(n, False) for n in eligible] + [(n, True) for n in negatives], taxon_ids)
    photos = cached(list(csv.DictReader(MANIFEST.open())))
    for photo in photos:
        photo["toxic"] = photo["toxic"] == "True"
    scores = embeddings(photos) @ table.T
    warns = [bool(is_hazard[np.argsort(-s)[:HAZARD_TOP]].any()) for s in scores]

    rows_of = lambda names_: np.array([index[n] for n in names_])  # noqa: E731
    pool_c, pool_t = rows_of(eligible + toxic_all), np.union1d(rows_of(eligible), np.flatnonzero(is_toxic))
    rules = {"A": (rows_of(eligible), 1, 0.0), "B": (rows_of(eligible + toxic_floor), 1, 0.0), "C": (pool_c, 1, 0.0)}
    rules |= {f"C-veto{k}": (pool_c, k, 0.0) for k in VETO_K}
    rules |= {f"C-m{m:.2f}": (pool_c, 1, m) for m in MARGINS}
    rules |= {"T": (pool_t, 1, 0.0)} | {f"T-veto{k}": (pool_t, k, 0.0) for k in VETO_K}
    rules["W"] = (np.arange(len(names)), 1, 0.0)

    # Smallest zero-false-pass margin: just above the largest gap any negative wins by, with and without row 1.
    c = [judge(s, pool_c, is_toxic) for s in scores]
    for tag, skip in (("raw", [False] * len(photos)), ("hazard", warns)):
        worst = max((j["gap"] for j, p, w in zip(c, photos, skip, strict=True)
                     if p["toxic"] and not w and genus[j["top"]] in targets), default=0.0)  # fmt: skip
        rules[f"C-m*{tag}"] = (pool_c, 1, float(np.nextafter(np.float32(worst), np.float32(1))))

    out = []
    for photo, s, warn in zip(photos, scores, warns, strict=True):
        for name, (pool, k, m) in rules.items():
            j = judge(s, pool, is_toxic, k, m)
            passing = targets.get(genus[j["top"]], []) if j["ok"] and not is_toxic[j["top"]] else []
            out.append({"photo": photo["photo"], "species": photo["species"], "toxic": photo["toxic"],
                        "variant": name, "margin": f"{m:.6f}", "top1": names[j["top"]],
                        "top1_toxic": bool(is_toxic[j["top"]]),
                        "best_blocker": names[j["best"]] if j["best"] is not None else "",
                        "gap": f"{j['gap']:.6f}", "hazard_warn": warn,
                        "pass_any_target": bool(passing), "passing_targets": ";".join(passing),
                        "pass_own_target": photo["species"] in passing})  # fmt: skip
    with (OUT / "toxic_block.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)

    pos = [p for p in photos if not p["toxic"]]
    neg = [p for p in photos if p["toxic"]]
    print(f"{PLACE}: floor {floor:.1f} sightings; {len(eligible)} eligible species; {len(toxic_all)} toxic-flagged "
          f"local species with a table row, {len(toxic_floor)} clearing the floor; "
          f"{int(is_toxic.sum())} toxic-flagged rows in the {len(names)}-row table")  # fmt: skip
    print(f"photos: {len(pos)} positive ({len({p['species'] for p in pos})} species), "
          f"{len(neg)} negative ({len({p['species'] for p in neg})} toxic species)")  # fmt: skip
    print(f"negative species (floor first, then by sightings): {'; '.join(negatives)}")
    short = [n for n in eligible + negatives if sum(p["species"] == n for p in photos) < PER_SPECIES]
    print(f"species with fewer than {PER_SPECIES} CC0 photos: {'; '.join(short) or 'none'}")
    print(f"row-1 hazard warning (hazard row in whole-table top {HAZARD_TOP}): "
          f"{sum(w for p, w in zip(photos, warns, strict=True) if not p['toxic'])} of {len(pos)} positives, "
          f"{sum(w for p, w in zip(photos, warns, strict=True) if p['toxic'])} of {len(neg)} negatives")  # fmt: skip
    for target in eligible:
        shared = [n for n in toxic_all if genus[index[n]] == genus[index[target]]]
        if shared:
            print(f"genus shared: target {target} with toxic local {'; '.join(shared)}")

    summary = {}
    print("rule | pool rows | margin | target pass (raw / after row 1) | toxic false pass (raw / after row 1)")
    for name, (pool, _, m) in rules.items():
        v = [r for r in out if r["variant"] == name]
        p = [r for r in v if not r["toxic"]]
        n = [r for r in v if r["toxic"]]
        won = (sum(r["pass_own_target"] for r in p), sum(r["pass_own_target"] and not r["hazard_warn"] for r in p))
        bad = (sum(r["pass_any_target"] for r in n), sum(r["pass_any_target"] and not r["hazard_warn"] for r in n))
        summary[name] = (won, bad)
        print(f"{name} | {len(pool)} | {m:.4f} | {won[0]}/{len(p)} ({won[0] / len(p):.0%}) / {won[1]}/{len(p)} "
              f"({won[1] / len(p):.0%}) | {bad[0]}/{len(n)} / {bad[1]}/{len(n)}")  # fmt: skip
    for name in rules:
        for r in out:
            if r["variant"] == name and r["toxic"] and r["pass_any_target"]:
                print(f"  {name} FALSE PASS {r['photo']} top1 {r['top1']} passes {r['passing_targets']}"
                      + (" (row-1 hazard warns first)" if r["hazard_warn"] else ""))  # fmt: skip
    for i, tag in ((0, "raw"), (1, "after row 1")):
        zero = [n for n in rules if summary[n][1][i] == 0]
        best = max(zero, key=lambda n: summary[n][0][i])
        print(f"best zero-false-pass rule ({tag}): {best}, target pass {summary[best][0][i]}/{len(pos)}; lost:")
        for r in out:
            if (
                r["variant"] == best
                and not r["toxic"]
                and not (r["pass_own_target"] and (i == 0 or not r["hazard_warn"]))
            ):
                print(f"  {r['photo']}: top1 {r['top1']}, best blocker {r['best_blocker']} gap {float(r['gap']):.4f}"
                      + (", row-1 hazard warns" if r["hazard_warn"] else ""))  # fmt: skip
    return 0


if __name__ == "__main__":
    sys.exit(main())
