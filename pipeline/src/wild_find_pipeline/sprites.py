"""Repack Briar source sprite sheets onto whole-pixel cells and write the PRD sheet contract JSON."""

import json
import math
import sys

import numpy as np
from PIL import Image
from scipy import ndimage

from wild_find_pipeline.paths import REPO

SOURCE = REPO / "assets/source"
OUT = REPO / "app/src/main/assets/briar"
# name: (source file, columns, rows, fps)
SHEETS = {"idle": ("briar-rest-blink-16.png", 4, 4, 8)}
# Rows of each frame's lowest pixels that count as its feet.
FEET_ROWS = 24
# Soft fur edges sit outside the alpha > 128 outline; grow the outline this far to keep them.
EDGE = 6


def frames_of(source: Image.Image, columns: int, rows: int) -> list[Image.Image]:
    """Cut out each frame by its own outline, in reading order.

    Generated sheets don't keep frames on an even grid, so frames are found by connected alpha, not cell edges.
    """
    alpha = np.asarray(source)[..., 3]
    labels, count = ndimage.label(alpha > 128)
    sizes = ndimage.sum(np.ones_like(labels), labels, range(1, count + 1))
    biggest = np.argsort(sizes)[::-1][: columns * rows] + 1
    if len(biggest) < columns * rows or sizes[biggest[-1] - 1] < sizes[biggest[0] - 1] / 2:
        raise ValueError(f"expected {columns * rows} frames of similar size")
    boxes = ndimage.find_objects(labels)
    # Reading order: bucket by row from the frame's vertical center, then left to right.
    row_height = source.height / rows
    order = sorted(
        biggest,
        key=lambda i: (int((boxes[i - 1][0].start + boxes[i - 1][0].stop) / 2 // row_height), boxes[i - 1][1].start),
    )
    frames = []
    for i in order:
        keep = ndimage.binary_dilation(labels == i, iterations=EDGE) & (alpha > 0)
        ys, xs = np.nonzero(keep)
        rgba = np.asarray(source).copy()
        rgba[..., 3] = np.where(keep, rgba[..., 3], 0)
        frames.append(Image.fromarray(rgba).crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)))
    return frames


def feet(frame: Image.Image) -> tuple[float, int]:
    """Anchor point: horizontal center of the lowest FEET_ROWS rows of solid pixels, and the bottom row."""
    alpha = np.asarray(frame)[..., 3] > 128
    bottom = int(np.nonzero(alpha.any(axis=1))[0].max())
    _, xs = np.nonzero(alpha[max(0, bottom - FEET_ROWS) : bottom + 1])
    return float(xs.mean()), bottom


def repack(source: Image.Image, columns: int, rows: int) -> tuple[Image.Image, int]:
    """Place every frame on a square whole-pixel cell with its feet at one shared point, so the loop stays planted.

    Returns the repacked sheet and its cell size.
    """
    frames = frames_of(source, columns, rows)
    anchors = [feet(f) for f in frames]
    left = max(ax for ax, _ in anchors)
    right = max(f.width - ax for f, (ax, _) in zip(frames, anchors, strict=True))
    above = max(ay for _, ay in anchors)
    below = max(f.height - ay for f, (_, ay) in zip(frames, anchors, strict=True))
    cell = 8 * math.ceil(max(left + right, above + below) / 8)
    # Shared feet point inside every cell, centered on the frames' combined extent.
    fx = (cell - (left + right)) / 2 + left
    fy = (cell - (above + below)) / 2 + above
    sheet = Image.new("RGBA", (cell * columns, cell * rows))
    for n, (frame, (ax, ay)) in enumerate(zip(frames, anchors, strict=True)):
        col, row = n % columns, n // columns
        sheet.alpha_composite(frame, (col * cell + round(fx - ax), row * cell + round(fy - ay)))
    return sheet, cell


def main() -> int:
    """Write <state>.png and <state>.json for every source sheet."""
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (file, columns, rows, fps) in SHEETS.items():
        sheet, cell = repack(Image.open(SOURCE / file).convert("RGBA"), columns, rows)
        sheet.save(OUT / f"{name}.png", optimize=True)
        meta = {"frame_width": cell, "frame_height": cell, "frames": columns * rows, "columns": columns, "fps": fps}
        (OUT / f"{name}.json").write_text(json.dumps({**meta, "loop": True}) + "\n")
        print(f"OK: {name} {sheet.width}x{sheet.height}, {columns * rows} frames of {cell} px at {fps} fps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
