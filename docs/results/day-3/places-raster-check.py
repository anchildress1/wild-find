"""Day-3 check, Oct 8: the whole-degree places.bin raster against the polygon lookup it replaced.

Run from the repo root (needs the 40 MB ne_10m_admin_1_states_provinces.geojson download; make assets caches it in
.models/natural-earth, otherwise this fetches it once):
uv --project pipeline run python -I docs/results/day-3/places-raster-check.py > docs/results/day-3/places-raster-check.log

Inputs, pinned in pipeline/src/wild_find_pipeline/places.py and checked by size and SHA-256 before use, from
nvkelso/natural-earth-vector @ f1890d9f152c896d250a77557a5751a93d494776 (v5.1.2, public domain):
- ne_10m_admin_1_states_provinces.geojson, 40,726,851 bytes, 22d0e3ad85eb3e27f17cabf8ba2d50e554fbc27a87796ff891d958185da62fb5
- ne_50m_admin_0_countries.geojson, 3,083,490 bytes, 3e458fc036ad0a66411f2c1e6cac49c5d7bfb81cb1123bc513b22511a2b7fdeb

At all 181 x 360 = 65,160 whole-degree centers the label can show, it compares the raster (places.rasterize) with a
point-in-polygon lookup under the same rule (states first, then countries, first containing place wins) over:
1. the full-resolution polygons the raster is built from, and
2. the polygons as bf8dfa3 shipped them: thinned to 0.05 degrees with Douglas-Peucker, rings under 4 points dropped.
"""

import json
from collections import Counter

import numpy as np
from wild_find_pipeline import places
from wild_find_pipeline.world_map import CACHE, fetch, simplify

SHIPPED_TOLERANCE = 0.05
MIN_RING = 4
EXAMPLES = 15


def polygon_lookup(
    layers: list[tuple[dict, tuple[str, ...]]], tolerance: float | None
) -> np.ndarray:
    """The name at every whole-degree center by point-in-polygon, polygons thinned to [tolerance] when given."""
    entries = []
    for geojson, keys in layers:
        for feature in geojson["features"]:
            properties = feature["properties"]
            name = next((properties[k] for k in keys if properties.get(k)), None)
            if feature["geometry"] is None or not name:
                continue
            rings = places.rings(feature["geometry"])
            if tolerance:
                rings = [
                    np.asarray(simplify([tuple(p) for p in r], tolerance))
                    for r in rings
                ]
                rings = [r for r in rings if len(r) >= MIN_RING]
            if rings:
                entries.append((name, rings))
    lng, lat = np.meshgrid(places.LNGS.astype(float), places.LATS.astype(float))
    out = np.full(lng.shape, "", dtype=object)
    todo = np.ones(lng.shape, dtype=bool)
    for name, rings in entries:
        corners = np.vstack(rings)
        (west, south), (east, north) = corners.min(axis=0), corners.max(axis=0)
        box = todo & (lng >= west) & (lng <= east) & (lat >= south) & (lat <= north)
        if not box.any():
            continue
        hit = places.inside(np.column_stack([lng[box], lat[box]]), rings)
        named = out[box]
        named[hit] = name
        out[box] = named
        open_cells = todo[box]
        open_cells[hit] = False
        todo[box] = open_cells
    return out


def main() -> None:
    """Print mismatch counts against both polygon lookups, with examples and a breakdown."""
    layers = [
        (json.loads(fetch(n, s, h, CACHE).read_text()), k)
        for n, s, h, k in places.SOURCES
    ]
    names, grid = places.rasterize(layers)
    raster = np.array(
        [[names[v - 1] if v else "" for v in row] for row in grid], dtype=object
    )
    print(f"raster: {len(names)} names over {raster.size} whole-degree centers")
    for label, tolerance in (
        ("full-resolution polygons", None),
        ("polygons as shipped, 0.05 degrees", SHIPPED_TOLERANCE),
    ):
        polygons = polygon_lookup(layers, tolerance)
        diff = np.argwhere(raster != polygons)
        kinds = Counter(
            "water vs land" if not raster[r, c] or not polygons[r, c] else "two names"
            for r, c in diff
        )
        near = sum(1 for r, _ in diff if abs(90 - r) <= 60)
        print(
            f"\n{label}: {len(diff)} mismatches of {raster.size}; {dict(kinds)}; {near} within 60 degrees of the equator"
        )
        for r, c in diff[:EXAMPLES]:
            print(
                f"  {90 - r},{c - 180}: raster={raster[r, c]!r} polygons={polygons[r, c]!r}"
            )


if __name__ == "__main__":
    main()
