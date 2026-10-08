"""Offline place names for the map picker's label: Natural Earth state/province and country polygons.

The phone looks the crosshairs' whole-degree center up in these polygons itself, so naming a place makes no network
call (iNaturalist stays the app's only peer). `make assets` calls write(); both GeoJSON files come from the pinned
Natural Earth commit in world_map and are trusted only after their size and SHA-256 match. Outlines are thinned with
Douglas-Peucker to SIMPLIFY degrees, about 5 km, plenty for a label that says "about".

places.bin, little-endian: b"WFPL", version u8; a name count u16 and per name its UTF-8 length u16 and bytes; a place
count u32 and per place its kind u8 (STATE, COUNTRY), name index u16, ring count u16, and per ring a point count u32
and that many (longitude, latitude) int16 pairs in hundredths of a degree. States come first, so a lookup that checks
in order names the state before its country.
"""

import json
import struct
from pathlib import Path

from wild_find_pipeline.world_map import CACHE, SCALE, fetch, shapes, simplify

STATE, COUNTRY = 0, 1
SIMPLIFY = 0.05
# (kind, file, bytes, SHA-256, name properties tried in order)
SOURCES = (
    (
        STATE,
        "ne_10m_admin_1_states_provinces.geojson",
        40726851,
        "22d0e3ad85eb3e27f17cabf8ba2d50e554fbc27a87796ff891d958185da62fb5",
        ("name_en", "name"),
    ),
    (
        COUNTRY,
        "ne_50m_admin_0_countries.geojson",
        3083490,
        "3e458fc036ad0a66411f2c1e6cac49c5d7bfb81cb1123bc513b22511a2b7fdeb",
        ("NAME_EN", "NAME"),
    ),
)
MAGIC = b"WFPL"
VERSION = 1
# A ring thinned below a triangle encloses nothing.
MIN_RING = 4


def places(geojson: dict, kind: int, keys: tuple[str, ...]) -> list[tuple[int, str, list]]:
    """(kind, name, thinned rings) per feature with a geometry and a name; tiny rings that thin away are dropped."""
    out = []
    for feature in geojson["features"]:
        name = next((feature["properties"].get(key) for key in keys if feature["properties"].get(key)), None)
        if feature["geometry"] is None or not name:
            continue
        rings = [simplify(ring, SIMPLIFY) for ring in shapes({"features": [feature]})]
        rings = [ring for ring in rings if len(ring) >= MIN_RING]
        if rings:
            out.append((kind, name, rings))
    return out


def pack(entries: list[tuple[int, str, list]]) -> bytes:
    """Encode places as places.bin, names deduplicated into one table."""
    names = list(dict.fromkeys(name for _, name, _ in entries))
    index = {name: i for i, name in enumerate(names)}
    out = bytearray(MAGIC + struct.pack("<BH", VERSION, len(names)))
    for name in names:
        encoded = name.encode()
        out += struct.pack("<H", len(encoded)) + encoded
    out += struct.pack("<I", len(entries))
    for kind, name, rings in entries:
        out += struct.pack("<BHH", kind, index[name], len(rings))
        for ring in rings:
            out += struct.pack("<I", len(ring))
            for lng, lat in ring:
                out += struct.pack("<hh", round(lng * SCALE), round(lat * SCALE))
    return bytes(out)


def write(out: Path, cache: Path = CACHE) -> int:
    """Fetch both pinned files and write places.bin to [out], states first; returns its size in bytes."""
    entries = []
    for kind, name, size, sha, keys in SOURCES:
        entries += places(json.loads(fetch(name, size, sha, cache).read_text()), kind, keys)
    data = pack(entries)
    out.write_bytes(data)
    return len(data)
