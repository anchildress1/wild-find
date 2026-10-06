"""Repack Briar source sprite sheets onto whole-pixel cells and write the PRD sheet contract JSON."""

import json
import math
import sys
from itertools import pairwise

from PIL import Image

from wild_find_pipeline.paths import REPO

SOURCE = REPO / "assets/source"
OUT = REPO / "app/src/main/assets/briar"
# name: (source file, columns, rows, fps); source cells may be fractional, e.g. 1774 / 4 = 443.5 px.
SHEETS = {"idle": ("briar-idle-sprites-twigs.png", 4, 2, 8)}


def repack(source: Image.Image, columns: int, rows: int) -> tuple[Image.Image, int]:
    """Cut a columns x rows grid at rounded cell edges and paste each frame onto a square whole-pixel cell.

    Every frame gets the same offset inside its cell, so frames stay registered to within a pixel.
    Returns the repacked sheet and its cell size.
    """
    xs = [round(i * source.width / columns) for i in range(columns + 1)]
    ys = [round(j * source.height / rows) for j in range(rows + 1)]
    widest = max(b - a for a, b in pairwise(xs))
    tallest = max(b - a for a, b in pairwise(ys))
    cell = 8 * math.ceil(max(widest, tallest) / 8)
    sheet = Image.new("RGBA", (cell * columns, cell * rows))
    for row in range(rows):
        for col in range(columns):
            frame = source.crop((xs[col], ys[row], xs[col + 1], ys[row + 1]))
            sheet.paste(frame, (col * cell + (cell - widest) // 2, row * cell + (cell - tallest) // 2))
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
