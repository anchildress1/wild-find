"""Key Briar's videos into transparent animated WebPs, and shrink the plant art, star, and launcher icon."""

import json
import sys
from pathlib import Path

import av
import numpy as np
from PIL import Image
from scipy import ndimage

from wild_find_pipeline.paths import REPO

SOURCE = REPO / "assets/source"
OUT = REPO / "app/src/main/assets/briar"
PLANTS_OUT = REPO / "app/src/main/assets/plants"
# One painted picture per PRD plant type, named by its `type` key in species_labels.json.
PLANT_TYPES = ("tree", "shrub", "vine", "herb", "grass", "fern", "moss", "conifer")
# Adaptive launcher icon layers, 108 dp each, written per density from the 432 px (xxxhdpi) sources.
ICON_SOURCE = SOURCE / "app_icons"
ICON_RES = REPO / "app/src/main/res"
ICON_LAYERS = ("foreground", "background", "monochrome")
ICON_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
# Tiles top out near 112 dp, about 340 px on a 3x screen.
PLANT_PX = 384
# The find star tops out at 76 dp, about 230 px on a 3x screen.
STAR_PX = 256
# Faint glow pixels below this alpha don't count as the plant's edge.
ALPHA_FLOOR = 16
# name: (source video, first frame kept, end frame). The cut drops the still stretch at each end, at frames that
# match so the loop joins without a jump. `found` plays `complete`'s clip, and `welcome` plays `opener`'s.
VIDEOS = {
    "opener": ("briar-welcome.mp4", 10, 217),
    "warning": ("briar-warning.mp4", 28, 225),
    "idle": ("briar-at-rest.mp4", 7, 108),
    "complete": ("briar-winning.mp4", 10, 202),
}
# The videos render Briar on white; white touching the frame's edge is background, so white fur inside him stays.
WHITE = 232
# Cast shadows are pale neutral grey; grey this light and this even, touching the background, goes with it. Fur and
# leaves are warmer or darker, so they stay.
SHADOW_MIN = 150
SHADOW_SPREAD = 18
# The background fades into Briar over this many pixels, softening the keyed edge.
FADE_PX = 2.5
VIDEO_QUALITY = 80


def solid_height(frame: Image.Image) -> int:
    """Rows from the top to the bottom of a frame's solid (alpha > 128) pixels."""
    rows = np.nonzero((np.asarray(frame)[..., 3] > 128).any(axis=1))[0]
    return int(rows.max() - rows.min() + 1)


def key_white(rgb: np.ndarray) -> np.ndarray:
    """RGBA from one video frame: near-white connected to the frame's edge turns transparent, with any pale grey
    shadow touching it.

    Edge pixels get their white spill divided out, so no light rim shows on a dark page.
    """
    image = rgb.astype(np.float32)
    low = image.min(axis=2)
    labels, _ = ndimage.label(low > WHITE)
    edge = np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))
    white = np.isin(labels, edge[edge > 0])
    shadow = (low > SHADOW_MIN) & (image.max(axis=2) - low < SHADOW_SPREAD)
    grown, _ = ndimage.label(white | shadow)
    touching = np.unique(grown[white])
    background = np.isin(grown, touching[touching > 0])
    alpha = np.clip(ndimage.distance_transform_edt(~background) / FADE_PX, 0, 1)[..., None]
    color = np.where(alpha > 0, (image - (1 - alpha) * 255) / np.maximum(alpha, 1e-3), 0)
    return np.dstack([np.clip(color, 0, 255), alpha[..., 0] * 255]).astype(np.uint8)


def clip(frames: list[np.ndarray]) -> tuple[list[Image.Image], int]:
    """Key every frame and crop all of them to the box that holds Briar in any frame.

    Returns the frames and Briar's height in the first one, which the app scales to: every cut starts on his rest
    pose, before props like the opener's leaves grow up around his feet.
    """
    keyed = [key_white(f) for f in frames]
    ys, xs = np.nonzero(np.stack([k[..., 3] for k in keyed]).any(axis=0))
    if len(ys) == 0:
        raise ValueError("the video is all background")
    box = (max(int(xs.min()) - 4, 0), max(int(ys.min()) - 4, 0), int(xs.max()) + 5, int(ys.max()) + 5)
    images = [Image.fromarray(k, "RGBA").crop(box) for k in keyed]
    return images, solid_height(images[0])


def video_frames(path: Path, first: int, end: int) -> tuple[list[np.ndarray], float]:
    """Frames [first, end) of the video at [path] as RGB arrays, and its frame rate."""
    with av.open(str(path)) as container:
        stream = container.streams.video[0]
        rate = float(stream.average_rate)
        frames = [f.to_ndarray(format="rgb24") for f in container.decode(stream)]
    if not 0 <= first < end <= len(frames):
        raise ValueError(f"cut {first}..{end} outside {len(frames)} frames")
    return frames[first:end], rate


def plant_art(source: Image.Image) -> Image.Image:
    """One plant-type picture on a PLANT_PX square, trimmed to the plant and standing on the bottom edge.

    The sources center each plant with a margin all round; trimming and grounding them lets every plant stand on
    the same line as Briar's feet.
    """
    rgba = source.convert("RGBA")
    box = rgba.getchannel("A").point(lambda a: 255 if a > ALPHA_FLOOR else 0).getbbox()
    if box is None:
        raise ValueError("plant art is empty")
    plant = rgba.crop(box)
    scale = PLANT_PX / max(plant.width, plant.height)
    plant = plant.resize(
        (max(1, round(plant.width * scale)), max(1, round(plant.height * scale))), Image.Resampling.LANCZOS
    )
    out = Image.new("RGBA", (PLANT_PX, PLANT_PX))
    out.alpha_composite(plant, ((PLANT_PX - plant.width) // 2, PLANT_PX - plant.height))
    return out


def main() -> int:
    """Write <state>.webp and <state>.json for every Briar video, and one WebP per plant."""
    OUT.mkdir(parents=True, exist_ok=True)
    PLANTS_OUT.mkdir(parents=True, exist_ok=True)
    for layer in ICON_LAYERS:
        source = Image.open(ICON_SOURCE / f"{layer}.png").convert("RGBA")
        for density, px in ICON_DENSITIES.items():
            folder = ICON_RES / f"mipmap-{density}"
            folder.mkdir(parents=True, exist_ok=True)
            source.resize((px, px), Image.Resampling.LANCZOS).save(folder / f"ic_launcher_{layer}.webp", lossless=True)
    print(f"OK: launcher icon, {len(ICON_LAYERS)} layers at {len(ICON_DENSITIES)} densities")
    star = Image.open(SOURCE / "star.png").convert("RGBA")
    star = star.crop(star.getchannel("A").getbbox())
    side = max(star.size)
    square = Image.new("RGBA", (side, side))
    square.alpha_composite(star, ((side - star.width) // 2, (side - star.height) // 2))
    square.resize((STAR_PX, STAR_PX), Image.Resampling.LANCZOS).save(OUT.parent / "star.webp", quality=90, method=6)
    print(f"OK: star {STAR_PX}x{STAR_PX}")
    for kind in PLANT_TYPES:
        plant_art(Image.open(SOURCE / f"{kind}.png")).save(PLANTS_OUT / f"{kind}.webp", quality=90, method=6)
        print(f"OK: plant {kind} {PLANT_PX}x{PLANT_PX}")
    for name, (file, first, end) in VIDEOS.items():
        frames, rate = video_frames(SOURCE / file, first, end)
        images, figure = clip(frames)
        images[0].save(
            OUT / f"{name}.webp",
            save_all=True,
            append_images=images[1:],
            duration=round(1000 / rate),
            loop=0,
            quality=VIDEO_QUALITY,
            method=6,
        )
        (OUT / f"{name}.json").write_text(json.dumps({"figure_height": figure}) + "\n")
        print(f"OK: {name} {images[0].width}x{images[0].height}, {len(images)} frames at {rate:g} fps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
