"""Pillow crop and resize references that the app's Kotlin frame path must reproduce bit for bit."""

import sys
from pathlib import Path

from PIL import Image

from wild_find_pipeline.paths import CROP_REFERENCE_DIR
from wild_find_pipeline.reference import SIZE, fetch_fixture, square_fixture

# Odd width minus either crop side leaves an odd remainder, which exercises the floor in the centering.
SOURCE_SIZE = (333, 250)
# A small, non-square target with a wide kernel: about 5.5x down in both directions.
DOWN_SIZE = (61, 47)
RETICLE_SHARE = 0.6


def reticle(img: Image.Image) -> Image.Image:
    """Center 60% square crop resized to 224, exactly as Day 1 cut its reticle crops."""
    side = int(min(img.size) * RETICLE_SHARE)
    left, top = (img.width - side) // 2, (img.height - side) // 2
    return img.crop((left, top, left + side, top + side)).resize((SIZE, SIZE), Image.Resampling.BICUBIC)


def source(photo: Image.Image) -> Image.Image:
    """Center SOURCE_SIZE region of the fixture photo, standing in for an upright camera frame."""
    photo = photo.convert("RGB")
    width, height = SOURCE_SIZE
    left, top = (photo.width - width) // 2, (photo.height - height) // 2
    return photo.crop((left, top, left + width, top + height))


def references(upright: Image.Image) -> dict[str, Image.Image]:
    """Every reference image by file name.

    `rotNN.png` is the upright frame turned so a clockwise rotation of NN degrees restores it, as CameraX reports.
    """
    return {
        "upright.png": upright,
        "rot90.png": upright.transpose(Image.Transpose.ROTATE_90),
        "rot180.png": upright.transpose(Image.Transpose.ROTATE_180),
        "rot270.png": upright.transpose(Image.Transpose.ROTATE_270),
        "reticle.png": reticle(upright),
        "full.png": square_fixture(upright),
        "down.png": upright.resize(DOWN_SIZE, Image.Resampling.BICUBIC),
    }


def write(out: Path, images: dict[str, Image.Image]) -> None:
    """Save each image as lossless PNG under `out`."""
    out.mkdir(parents=True, exist_ok=True)
    for name, img in images.items():
        img.save(out / name, format="PNG")


def main() -> int:
    """Write the crop references for the JVM frame tests."""
    write(CROP_REFERENCE_DIR, references(source(fetch_fixture())))
    print(f"OK: wrote crop references to {CROP_REFERENCE_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
