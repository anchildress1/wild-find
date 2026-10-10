"""Day-5 target pass: every photo's TinyCLIP plant shares and BioCLIP Mobile embeddings for each crop a rule can use.

Run from the repo root after make assets and fetch.py: uv run --project pipeline python -I
docs/results/day-5/target_pass/embed.py

Assets: copied once from app/generated/assets and app/src/main/assets into .models/target-pass-assets (gitignored) so
a concurrent make assets cannot change them mid-study; every later run checks their SHA-256 against assets.sha256.
Photo sets and their manifests (each photo's SHA-256 is checked before use):
  S50   docs/results/day-3/calibration.photos.csv   .models/calibration-photos   (target, grass, non_grass)
  TB    docs/results/day-3/toxic_block_photos.csv   .models/toxic-block-photos   (Day-2 positives and negatives + 9)
  S51   docs/results/day-3/holdout.photos.csv       .models/holdout-photos       (target, toxic, non_plant)
  NEW   docs/results/day-5/target_pass/photos.csv   .models/target-pass-photos   (target, toxic)
Views, each a center-anchored square cut from the upright photo and resized Pillow bicubic to 224 (Day-1 geometry):
  ret60 is the shipped reticle (side int(0.6 x shorter edge)); full is the shipped full frame (side = shorter edge);
  ret50, ret70, ret80 are other reticle scales; ret60 shifted by 4% of the shorter edge left, right, up, and down
  stand in for hand shake across the 3 capture frames; *_flip is the 224 input mirrored left to right.
Writes .models/target-pass-embeddings/<set>.npz: sha256 (n), views (v), emb (n, v, 1024 float32), share_ret60 and
share_full (n float64, TinyCLIP plant share as core PlantGate).
"""

import csv
import hashlib
import importlib.util
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from wild_find_pipeline.paths import MODEL_CACHE, file_sha256
from wild_find_pipeline.reference import SIZE, image_input

OUT = Path(__file__).resolve().parent
RESULTS = OUT.parents[1]
DAY3 = RESULTS / "day-3"
_spec = importlib.util.spec_from_file_location("calibration", DAY3 / "calibration.py")
cal = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cal)

ASSETS = MODEL_CACHE / "target-pass-assets"
ASSET_SHAS = OUT / "assets.sha256"
CACHE = MODEL_CACHE / "target-pass-embeddings"
SETS = {
    "S50": (DAY3 / "calibration.photos.csv", MODEL_CACHE / "calibration-photos"),
    "TB": (DAY3 / "toxic_block_photos.csv", MODEL_CACHE / "toxic-block-photos"),
    "S51": (DAY3 / "holdout.photos.csv", MODEL_CACHE / "holdout-photos"),
    "NEW": (OUT / "photos.csv", MODEL_CACHE / "target-pass-photos"),
}
SHIFT = 0.04
# name -> (side as a share of the shorter edge, x shift, y shift, mirrored)
VIEWS = {
    "ret60": (0.6, 0, 0, False),
    "full": (1.0, 0, 0, False),
    "ret60_flip": (0.6, 0, 0, True),
    "full_flip": (1.0, 0, 0, True),
    "ret50": (0.5, 0, 0, False),
    "ret70": (0.7, 0, 0, False),
    "ret80": (0.8, 0, 0, False),
    "ret60_l": (0.6, -SHIFT, 0, False),
    "ret60_r": (0.6, SHIFT, 0, False),
    "ret60_u": (0.6, 0, -SHIFT, False),
    "ret60_d": (0.6, 0, SHIFT, False),
}


def snapshot() -> dict[str, str]:
    """Copy the bundled assets once, then check them against assets.sha256 on every run."""
    if not ASSET_SHAS.exists():
        ASSETS.mkdir(parents=True, exist_ok=True)
        for name, src in cal.ASSET_FILES.items():
            shutil.copyfile(src / name, ASSETS / name)
        ASSET_SHAS.write_text("".join(f"{file_sha256(ASSETS / n)}  {n}\n" for n in cal.ASSET_FILES))
    shas = {line.split()[1]: line.split()[0] for line in ASSET_SHAS.read_text().splitlines()}
    for name, sha in shas.items():
        if file_sha256(ASSETS / name) != sha:
            raise ValueError(f"{ASSETS / name} does not match assets.sha256")
    if shas["flora_student_fp32.onnx"] != cal.pin("bioclip")["sha256"]:
        raise ValueError("bundled BioCLIP does not match its pin")
    return shas


def crop(img: Image.Image, scale: float, dx: float, dy: float, flip: bool) -> Image.Image:
    """One view: a square of side int(scale x shorter edge), centered then shifted, bicubic to 224."""
    w, h = img.size
    short = min(w, h)
    side = int(short * scale)
    left = (w - side) // 2 + round(dx * short)
    top = (h - side) // 2 + round(dy * short)
    left, top = min(max(left, 0), w - side), min(max(top, 0), h - side)
    out = img.crop((left, top, left + side, top + side)).resize((SIZE, SIZE), Image.Resampling.BICUBIC)
    return out.transpose(Image.Transpose.FLIP_LEFT_RIGHT) if flip else out


def photos(set_name: str) -> list[dict]:
    """A set's manifest rows after checking every cached photo's SHA-256."""
    manifest, folder = SETS[set_name]
    rows = list(csv.DictReader(manifest.open()))
    for row in rows:
        if hashlib.sha256((folder / row["photo"]).read_bytes()).hexdigest() != row["sha256"]:
            raise ValueError(f"{row['photo']} does not match its manifest SHA-256")
    return rows


def main() -> int:
    """Embed every view of every photo in every set; cached per set by photo SHA-256."""
    snapshot()
    h = cal.hunt(ASSETS)
    v = cal.Verifier(ASSETS, h)
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, (manifest, folder) in SETS.items():
        if not manifest.exists():
            print(f"{name}: no manifest yet, skipped", file=sys.stderr)
            continue
        rows = photos(name)
        path = CACHE / f"{name}.npz"
        if path.exists():
            old = np.load(path)
            if list(old["sha256"]) == [r["sha256"] for r in rows] and list(old["views"]) == list(VIEWS):
                print(f"{name}: {len(rows)} photos cached", file=sys.stderr)
                continue
        emb = np.zeros((len(rows), len(VIEWS), 1024), dtype=np.float32)
        share_ret, share_full = np.zeros(len(rows)), np.zeros(len(rows))
        for k, row in enumerate(rows):
            img = Image.open(folder / row["photo"]).convert("RGB")
            for j, view in enumerate(VIEWS.values()):
                emb[k, j] = v.embed(image_input(crop(img, *view)))
            share_ret[k] = v.share(image_input(crop(img, *VIEWS["ret60"])))
            share_full[k] = v.share(image_input(crop(img, *VIEWS["full"])))
        np.savez(path, sha256=np.array([r["sha256"] for r in rows]), views=np.array(list(VIEWS)), emb=emb,
                 share_ret60=share_ret, share_full=share_full)  # fmt: skip
        print(f"{name}: {len(rows)} photos embedded", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
