"""The map picker's built-in world map: Natural Earth land polygons (public domain), packed for the APK.

Two levels ship: 1:110m for the zoomed-out world and 1:50m for area detail. The phone draws them itself, so no tile
server exists and iNaturalist stays the app's only network call. `make assets` calls write(); the GeoJSON comes from
a pinned commit of the Natural Earth vector repo and is trusted only after its size and SHA-256 match.

land.bin, little-endian: b"WFLD", version u8, level count u8; per level a ring count u32; per ring a point count u32
and that many (longitude, latitude) int16 pairs in hundredths of a degree.
"""

import json
import struct
import urllib.request
from pathlib import Path

from wild_find_pipeline.paths import MODEL_CACHE, file_sha256

# nvkelso/natural-earth-vector tag v5.1.2; public domain per its LICENSE.md ("Everything here is public domain").
COMMIT = "f1890d9f152c896d250a77557a5751a93d494776"
URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{commit}/geojson/{name}"
# Zoomed-out world first, area detail second; the app picks the level by zoom.
LEVELS = (
    ("ne_110m_land.geojson", 138160, "9e0729ee253ca7d7a5c4ae9395fb1902264c5377c52e224d13dd85010e2835d9"),
    ("ne_50m_land.geojson", 1636166, "e874b27a51d146452be360cafb3cc50c86001074a67d534113e6534682f9826b"),
)
MAGIC = b"WFLD"
VERSION = 1
SCALE = 100
CACHE = MODEL_CACHE / "natural-earth"


def verified(path: Path, size: int, sha256: str) -> bool:
    """True when [path] exists with the pinned byte count and SHA-256."""
    return path.is_file() and path.stat().st_size == size and file_sha256(path) == sha256


def fetch(name: str, size: int, sha256: str, cache: Path = CACHE) -> Path:
    """The pinned GeoJSON [name], downloaded once into [cache]; raises when the bytes don't match the pins."""
    path = cache / name
    if not verified(path, size, sha256):
        cache.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(URL.format(commit=COMMIT, name=name), path)
        if not verified(path, size, sha256):
            raise ValueError(f"{name} does not match its pinned size/SHA-256")
    return path


def rings(geojson: dict) -> list[list[tuple[float, float]]]:
    """Every ring of every (Multi)Polygon feature, as (longitude, latitude) points."""
    out = []
    for feature in geojson["features"]:
        geometry = feature["geometry"]
        polygons = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
        out.extend([[(point[0], point[1]) for point in ring] for polygon in polygons for ring in polygon])
    return out


def pack(levels: list[list[list[tuple[float, float]]]]) -> bytes:
    """Encode [levels] as land.bin; coordinates round to hundredths of a degree, about a kilometer."""
    out = bytearray(MAGIC + struct.pack("<BB", VERSION, len(levels)))
    for level in levels:
        out += struct.pack("<I", len(level))
        for ring in level:
            out += struct.pack("<I", len(ring))
            for lng, lat in ring:
                if not (-180 <= lng <= 180 and -90 <= lat <= 90):
                    raise ValueError(f"point {lng}, {lat} is off the globe")
                out += struct.pack("<hh", round(lng * SCALE), round(lat * SCALE))
    return bytes(out)


def write(out: Path, cache: Path = CACHE) -> int:
    """Fetch both pinned levels and write land.bin to [out]; returns its size in bytes."""
    levels = [rings(json.loads(fetch(name, size, sha, cache).read_text())) for name, size, sha in LEVELS]
    data = pack(levels)
    out.write_bytes(data)
    return len(data)
