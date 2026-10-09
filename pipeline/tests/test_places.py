import json
import struct

import numpy as np

from wild_find_pipeline import places


def feature(name, *rings, key="name_en"):
    return {"properties": {key: name}, "geometry": {"type": "Polygon", "coordinates": list(rings)}}


def square(west, south, side):
    return [[west, south], [west + side, south], [west + side, south + side], [west, south + side], [west, south]]


def cell(grid, lat, lng):
    return int(grid[90 - lat, lng + 180])


def test_rasterize_names_each_whole_degree_point_by_its_state_then_its_country():
    states = {
        "features": [
            # A 10-degree state with a 2-degree hole around 5, 5.
            feature("Inner", square(0.5, 0.5, 10), square(4.5, 4.5, 2)),
            {"properties": {"name_en": "Nowhere"}, "geometry": None},
            feature("", square(20.5, 20.5, 2)),
        ]
    }
    countries = {"features": [feature("Outer", square(-5.5, -5.5, 20), key="NAME_EN")]}

    names, grid = places.rasterize([(states, ("name_en", "name")), (countries, ("NAME_EN", "NAME"))])

    assert names == ["Inner", "Outer"]
    assert grid.shape == (181, 360)
    assert cell(grid, 2, 2) == 1
    assert cell(grid, 5, 5) == 2
    assert cell(grid, 12, -3) == 2
    assert cell(grid, 21, 21) == 0
    assert cell(grid, -80, 170) == 0


def test_rasterize_lets_a_multipolygon_name_every_part():
    multi = {
        "properties": {"name": "Islands"},
        "geometry": {"type": "MultiPolygon", "coordinates": [[square(-179.5, 10.5, 1)], [square(178.5, 10.5, 1)]]},
    }

    names, grid = places.rasterize([({"features": [multi]}, ("name_en", "name"))])

    assert names == ["Islands"]
    assert cell(grid, 11, -179) == 1
    assert cell(grid, 11, 179) == 1


def test_pack_writes_the_name_table_then_the_grid_row_by_row():
    grid = np.zeros((181, 360), dtype=np.uint16)
    grid[90 - 34, -85 + 180] = 1

    data = places.pack(["Bahía"], grid)

    assert data[:5] == b"WFPL\x02"
    assert struct.unpack_from("<HH", data, 5) == (1, len("Bahía".encode()))
    start = 9 + len("Bahía".encode())
    assert data[9:start].decode() == "Bahía"
    assert len(data) == start + 181 * 360 * 2
    assert struct.unpack_from("<H", data, start + ((90 - 34) * 360 + 95) * 2) == (1,)


def test_write_rasterizes_both_pinned_files(tmp_path, monkeypatch):
    def fetch(name, size, sha, cache):
        path = tmp_path / name
        key = "name_en" if "admin_1" in name else "NAME_EN"
        path.write_text(json.dumps({"features": [feature(name[:6], square(0.5, 0.5, 1), key=key)]}))
        return path

    monkeypatch.setattr(places, "fetch", fetch)

    size = places.write(tmp_path / "places.bin", tmp_path)
    data = (tmp_path / "places.bin").read_bytes()

    assert size == len(data)
    assert struct.unpack_from("<H", data, 5) == (1,)
