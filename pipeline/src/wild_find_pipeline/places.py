"""Offline place names for the map picker's label: a whole-degree raster of Natural Earth states and countries.

The app only ever names a whole-degree region by its center, so the index samples exactly those 181 x 360 points:
each holds the state or province there (Natural Earth 1:10m admin-1, full resolution), else the country (1:50m
admin-0) for points no state covers, else nothing over water. The phone reads one cell, so naming a place makes no
network call and ships no polygons; the borders it draws come from map.bin. `make assets` calls write(); both GeoJSON
files come from the pinned Natural Earth commit in world_map and are trusted only after their size and SHA-256 match.

places.bin, little-endian: b"WFPL", version u8; a name count u16 and per name its UTF-8 length u16 and bytes; then
181 rows (latitude 90 down to -90) of 360 u16 cells (longitude -180 up to 179), each 0 for none or 1 + a name index.
"""

import json
import struct
from pathlib import Path

import numpy as np

from wild_find_pipeline.world_map import CACHE, fetch

MAGIC = b"WFPL"
VERSION = 2
LATS = np.arange(90, -91, -1)
LNGS = np.arange(-180, 180)
# (file, bytes, SHA-256, name properties tried in order); states first, countries only fill what states leave.
SOURCES = (
    (
        "ne_10m_admin_1_states_provinces.geojson",
        40726851,
        "22d0e3ad85eb3e27f17cabf8ba2d50e554fbc27a87796ff891d958185da62fb5",
        ("name_en", "name"),
    ),
    (
        "ne_50m_admin_0_countries.geojson",
        3083490,
        "3e458fc036ad0a66411f2c1e6cac49c5d7bfb81cb1123bc513b22511a2b7fdeb",
        ("NAME_EN", "NAME"),
    ),
)


def rings(geometry: dict) -> list[np.ndarray]:
    """Every ring of a (Multi)Polygon as an (n, 2) array of (longitude, latitude)."""
    polygons = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
    return [np.asarray(ring, dtype=float) for polygon in polygons for ring in polygon]


def inside(points: np.ndarray, shape: list[np.ndarray]) -> np.ndarray:
    """Even-odd point-in-polygon over every ring of [shape], so holes count as outside; [points] is (n, 2)."""
    x, y = points[:, 0], points[:, 1]
    hit = np.zeros(len(points), dtype=bool)
    for ring in shape:
        for (xi, yi), (xj, yj) in zip(ring, np.roll(ring, 1, axis=0), strict=True):
            if yi == yj:
                continue
            hit ^= ((yi > y) != (yj > y)) & (x < (xj - xi) * (y - yi) / (yj - yi) + xi)
    return hit


def rasterize(layers: list[tuple[dict, tuple[str, ...]]]) -> tuple[list[str], np.ndarray]:
    """Names and the 181 x 360 cell grid; a later layer only fills cells the earlier ones left empty."""
    lng, lat = np.meshgrid(LNGS.astype(float), LATS.astype(float))
    grid = np.zeros(lng.shape, dtype=np.uint16)
    names: list[str] = []
    for geojson, keys in layers:
        empty = grid == 0
        for feature in geojson["features"]:
            properties = feature["properties"]
            name = next((properties[key] for key in keys if properties.get(key)), None)
            if feature["geometry"] is None or not name:
                continue
            shape = rings(feature["geometry"])
            corners = np.vstack(shape)
            (west, south), (east, north) = corners.min(axis=0), corners.max(axis=0)
            box = empty & (lng >= west) & (lng <= east) & (lat >= south) & (lat <= north)
            if not box.any():
                continue
            hit = inside(np.column_stack([lng[box], lat[box]]), shape)
            if hit.any():
                if name not in names:
                    names.append(name)
                cells = grid[box]
                cells[hit] = names.index(name) + 1
                grid[box] = cells
    return names, grid


def pack(names: list[str], grid: np.ndarray) -> bytes:
    """Encode the names and the grid as places.bin."""
    out = bytearray(MAGIC + struct.pack("<BH", VERSION, len(names)))
    for name in names:
        encoded = name.encode()
        out += struct.pack("<H", len(encoded)) + encoded
    return bytes(out) + grid.astype("<u2").tobytes()


def write(out: Path, cache: Path = CACHE) -> int:
    """Fetch both pinned files and write places.bin to [out]; returns its size in bytes."""
    layers = [(json.loads(fetch(name, size, sha, cache).read_text()), keys) for name, size, sha, keys in SOURCES]
    data = pack(*rasterize(layers))
    out.write_bytes(data)
    return len(data)
