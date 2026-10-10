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
# The picture for a species whose `type` is null; not a type, so no species-table row ever names it.
UNTYPED = "plant"
# The opener's watercolor forest. The source is 841 px wide, under a phone's 1080, so it ships at its own size;
# quality 80 looked the same as the source side by side on Oct 10.
BACKGROUND = "background.png"
BACKGROUND_OUT = REPO / "app/src/main/assets/opener_background.webp"
BACKGROUND_QUALITY = 80
# The leafy "Wild Find" title art, shown on Start and the safety opener; it tops out near 300 dp wide, about 900 px.
TITLE = "wild-find-title-text.png"
TITLE_OUT = REPO / "app/src/main/assets/title.webp"
TITLE_PX = 900
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
# match so the loop joins without a jump. `found` plays `complete`'s clip, and the opener plays `warning`'s.
VIDEOS = {
    "welcome": ("briar-welcome.mp4", 10, 217),
    "warning": ("briar-warning.mp4", 28, 225),
    "idle": ("briar-at-rest.mp4", 7, 108),
    "complete": ("briar-winning.mp4", 10, 202),
    "try_again": ("briar-try-again.mp4", 9, 213),
}
# The videos render Briar on white; white touching the frame's edge is background, so white fur inside him stays.
WHITE = 232
# White that the legs, arms, or leaves close off is still background, but so are Briar's eye whites and fur highlights.
# Oct 10, every frame of all five videos: closed-off white that borders green leaves occurs only in the warning clip's
# leaf band, never next to an eye or fur; and the flattest, brightest closed-off white (mean min channel 246.9-251,
# spread 3.6 or less) is background too, while the closest eye white measured 245.8 and 3.6. Below POCKET_PX,
# compression noise decides it, so a speck stays.
POCKET_MIN = 246.5
POCKET_SPREAD = 3.6
POCKET_PX = 50
# Share of a closed-off region's rim within LEAF_REACH px of leaf green that marks it as a gap between leaves.
LEAF_RIM = 0.05
LEAF_REACH = 3
LEAF_MARGIN = 12
# Leaves are big green patches; green glints in Briar's eyes are small, and the closed-off white beside them is an eye.
LEAF_PX = 400
# The leaves cast a warm, soft shadow on the white floor, too warm for the grey shadow rule; near leaves, pale
# pixels this even that touch the background go with it.
LEAF_SHADOW_SPREAD = 45
# Cast shadows are pale neutral grey; grey this light and this even, touching the background, goes with it. Fur and
# leaves are warmer or darker, so they stay.
SHADOW_MIN = 150
SHADOW_SPREAD = 18
# Floor band: from this share of the frame's height down, pale pixels this light and even are floor, not Briar.
FLOOR_FROM = 0.75
FLOOR_MIN = 170
FLOOR_SPREAD = 40
SHADOW_FROM = 0.84
SHADOW_FLOOR_MIN = 140
# The background fades into Briar over this many pixels, softening the keyed edge.
FADE_PX = 2.5
VIDEO_QUALITY = 80


def solid_height(frame: Image.Image) -> int:
    """Rows from the top to the bottom of a frame's solid (alpha > 128) pixels."""
    rows = np.nonzero((np.asarray(frame)[..., 3] > 128).any(axis=1))[0]
    return int(rows.max() - rows.min() + 1)


def key_white(rgb: np.ndarray) -> np.ndarray:
    """RGBA from one video frame: near-white connected to the frame's edge, or closed off but flat paper white or
    hemmed in by leaves, turns transparent, with any pale grey shadow touching it.

    Edge pixels get their white spill divided out, so no light rim shows on a dark page.
    """
    image = rgb.astype(np.float32)
    low = image.min(axis=2)
    labels, _ = ndimage.label(low > WHITE)
    count = int(labels.max())
    edge = np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))
    ids = np.arange(1, count + 1)
    size = ndimage.sum(np.ones_like(low), labels, ids)
    brightness = ndimage.mean(low, labels, ids)
    spread = ndimage.mean(image.max(axis=2) - low, labels, ids)
    near_white = labels > 0
    rim = near_white & ~ndimage.binary_erosion(near_white)
    r, g, b = image[..., 0], image[..., 1], image[..., 2]
    green = (g > r + LEAF_MARGIN) & (g > b + LEAF_MARGIN)
    patches, _ = ndimage.label(green)
    patch_px = np.bincount(patches.ravel())
    green &= patch_px[patches] >= LEAF_PX
    leaf = ndimage.binary_dilation(green, iterations=LEAF_REACH)
    leaf_near = ndimage.binary_dilation(green, iterations=2 * LEAF_REACH) & ~green
    leafy = ndimage.sum(rim & leaf, labels, ids) / np.maximum(ndimage.sum(rim, labels, ids), 1)
    flat = (brightness >= POCKET_MIN) & (spread <= POCKET_SPREAD)
    pockets = ids[(size >= POCKET_PX) & (flat | (leafy >= LEAF_RIM))]
    white = np.isin(labels, np.concatenate([edge[edge > 0], pockets]))
    even = image.max(axis=2) - low
    shadow = (low > SHADOW_MIN) & ((even < SHADOW_SPREAD) | (leaf_near & (even < LEAF_SHADOW_SPREAD)))
    grown, _ = ndimage.label(white | shadow)
    touching = np.unique(grown[white])
    background = np.isin(grown, touching[touching > 0])
    # Below Briar's knees only leaves, brown paws, and the white floor with its warm shadows show; the leaves wall
    # off floor pockets no connectivity rule reaches, so every pale, even, non-leaf pixel there is floor.
    row = np.arange(low.shape[0])[:, None]
    background |= (row >= int(low.shape[0] * FLOOR_FROM)) & (low > FLOOR_MIN) & (even < FLOOR_SPREAD) & ~green
    # Lower still, past the paws' tops, the leaves' warm shadow is the only pale thing; the paws are dark brown.
    background |= (row >= int(low.shape[0] * SHADOW_FROM)) & (low > SHADOW_FLOOR_MIN) & ~green
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


def trimmed(source: Image.Image) -> Image.Image:
    """The picture as RGBA cropped to its visible pixels, ignoring glow fainter than ALPHA_FLOOR."""
    rgba = source.convert("RGBA")
    box = rgba.getchannel("A").point(lambda a: 255 if a >= ALPHA_FLOOR else 0).getbbox()
    if box is None:
        raise ValueError("the picture is empty")
    return rgba.crop(box)


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
    """Write each Briar clip with its JSON, one WebP per plant, the opener background, and the title."""
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
    background = Image.open(SOURCE / BACKGROUND).convert("RGB")
    background.save(BACKGROUND_OUT, quality=BACKGROUND_QUALITY, method=6)
    print(f"OK: opener background {background.width}x{background.height}, {BACKGROUND_OUT.stat().st_size} bytes")
    title = trimmed(Image.open(SOURCE / TITLE))
    title = title.resize((TITLE_PX, round(title.height * TITLE_PX / title.width)), Image.Resampling.LANCZOS)
    title.save(TITLE_OUT, quality=90, method=6)
    print(f"OK: title {title.width}x{title.height}, {TITLE_OUT.stat().st_size} bytes")
    for kind in (*PLANT_TYPES, UNTYPED):
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
        # The app reserves the clip's space from these before its frames decode.
        width, height = images[0].size
        meta = {"figure_height": figure, "width": width, "height": height}
        (OUT / f"{name}.json").write_text(json.dumps(meta) + "\n")
        print(f"OK: {name} {images[0].width}x{images[0].height}, {len(images)} frames at {rate:g} fps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
