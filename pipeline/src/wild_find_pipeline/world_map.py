"""The map picker's built-in world map: Natural Earth land, country borders, and state lines, packed for the APK.

Natural Earth is public domain. The phone draws this file itself, so no tile server exists and iNaturalist stays the
app's only network call. `make assets` calls write(); every GeoJSON comes from a pinned commit of the Natural Earth
vector repo and is trusted only after its size and SHA-256 match.

Two levels: 0 for the zoomed-out world (1:110m land and borders), 1 for area detail (1:50m land and borders, 1:10m
state and province lines, the only scale that has them outside nine large countries). State lines are thinned with
Douglas-Peucker to SIMPLIFY degrees, under two pixels at the zoom where picking unlocks, which keeps the APK cost down.

map.bin, little-endian: b"WFMP", version u8, layer count u8; per layer its kind u8 (LAND, BORDERS, STATES) and level
u8, a shape count u32, and per shape a point count u32 and that many (longitude, latitude) int16 pairs in hundredths
of a degree. Land shapes are closed rings; border and state shapes are open lines.
"""

import json
import struct
import urllib.request
from pathlib import Path

from wild_find_pipeline.gbif import USER_AGENT
from wild_find_pipeline.paths import MODEL_CACHE, file_sha256

# nvkelso/natural-earth-vector tag v5.1.2; public domain per its LICENSE.md ("Everything here is public domain").
COMMIT = "f1890d9f152c896d250a77557a5751a93d494776"
URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{commit}/geojson/{name}"
LAND, BORDERS, STATES = 0, 1, 2
WORLD, AREA = 0, 1
SIMPLIFY = 0.02
# (kind, level, file, bytes, SHA-256, simplify tolerance in degrees or None)
SOURCES = (
    (
        LAND,
        WORLD,
        "ne_110m_land.geojson",
        138160,
        "9e0729ee253ca7d7a5c4ae9395fb1902264c5377c52e224d13dd85010e2835d9",
        None,
    ),
    (
        LAND,
        AREA,
        "ne_50m_land.geojson",
        1636166,
        "e874b27a51d146452be360cafb3cc50c86001074a67d534113e6534682f9826b",
        None,
    ),
    (
        BORDERS,
        WORLD,
        "ne_110m_admin_0_boundary_lines_land.geojson",
        340010,
        "d42479fd79552cca4eec7f85fcdca717a790d29ff06be7676f1af0568c6d3f7c",
        None,
    ),
    (
        BORDERS,
        AREA,
        "ne_50m_admin_0_boundary_lines_land.geojson",
        760189,
        "2faac4f6b34386f3d21b6e018cf151f241f00e5c936d44dd17d7d9bfb147fa48",
        None,
    ),
    (
        STATES,
        AREA,
        "ne_10m_admin_1_states_provinces_lines.geojson",
        21092537,
        "1a1f30ccaaf4cc9c4bde34266f0b8cbb955d3a4cf254b756912255f2ec7c75b6",
        SIMPLIFY,
    ),
)
MAGIC = b"WFMP"
VERSION = 1
SCALE = 100
CACHE = MODEL_CACHE / "natural-earth"

Shape = list[tuple[float, float]]


def verified(path: Path, size: int, sha256: str) -> bool:
    """True when [path] exists with the pinned byte count and SHA-256."""
    return path.is_file() and path.stat().st_size == size and file_sha256(path) == sha256


def fetch(name: str, size: int, sha256: str, cache: Path = CACHE) -> Path:
    """The pinned GeoJSON [name], downloaded once into [cache]; raises when the bytes don't match the pins."""
    path = cache / name
    if not verified(path, size, sha256):
        cache.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(URL.format(commit=COMMIT, name=name), headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=300) as response:
            path.write_bytes(response.read())
        if not verified(path, size, sha256):
            raise ValueError(f"{name} does not match its pinned size/SHA-256")
    return path


def shapes(geojson: dict) -> list[Shape]:
    """Every ring of every (Multi)Polygon and every (Multi)LineString, as (longitude, latitude) points.

    Features without geometry, which Natural Earth ships a few of, are skipped.
    """
    out: list[Shape] = []
    for feature in geojson["features"]:
        geometry = feature["geometry"]
        if geometry is None:
            continue
        kind, coordinates = geometry["type"], geometry["coordinates"]
        if kind == "Polygon":
            parts = coordinates
        elif kind == "MultiPolygon":
            parts = [ring for polygon in coordinates for ring in polygon]
        elif kind == "LineString":
            parts = [coordinates]
        elif kind == "MultiLineString":
            parts = coordinates
        else:
            raise ValueError(f"unexpected geometry {kind}")
        out.extend([[(point[0], point[1]) for point in part] for part in parts])
    return out


def simplify(shape: Shape, tolerance: float) -> Shape:
    """Douglas-Peucker: drop points within [tolerance] degrees of the line between the points kept around them."""
    if len(shape) < 3:
        return shape
    keep = [False] * len(shape)
    keep[0] = keep[-1] = True
    stack = [(0, len(shape) - 1)]
    while stack:
        first, last = stack.pop()
        (ax, ay), (bx, by) = shape[first], shape[last]
        dx, dy = bx - ax, by - ay
        length = (dx * dx + dy * dy) ** 0.5
        far, index = 0.0, -1
        for i in range(first + 1, last):
            px, py = shape[i]
            distance = (
                abs(dx * (py - ay) - dy * (px - ax)) / length if length else ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
            )
            if distance > far:
                far, index = distance, i
        if far > tolerance:
            keep[index] = True
            stack += [(first, index), (index, last)]
    return [point for point, kept in zip(shape, keep, strict=True) if kept]


def pack(layers: list[tuple[int, int, list[Shape]]]) -> bytes:
    """Encode (kind, level, shapes) layers as map.bin; coordinates round to hundredths of a degree, about a km."""
    out = bytearray(MAGIC + struct.pack("<BB", VERSION, len(layers)))
    for kind, level, layer in layers:
        out += struct.pack("<BBI", kind, level, len(layer))
        for shape in layer:
            out += struct.pack("<I", len(shape))
            for lng, lat in shape:
                if not (-180 <= lng <= 180 and -90 <= lat <= 90):
                    raise ValueError(f"point {lng}, {lat} is off the globe")
                out += struct.pack("<hh", round(lng * SCALE), round(lat * SCALE))
    return bytes(out)


def write(out: Path, cache: Path = CACHE) -> int:
    """Fetch every pinned layer, thin the state lines, and write map.bin to [out]; returns its size in bytes."""
    layers = []
    for kind, level, name, size, sha, tolerance in SOURCES:
        found = shapes(json.loads(fetch(name, size, sha, cache).read_text()))
        if tolerance is not None:
            found = [simplify(shape, tolerance) for shape in found]
        layers.append((kind, level, found))
    data = pack(layers)
    out.write_bytes(data)
    return len(data)
