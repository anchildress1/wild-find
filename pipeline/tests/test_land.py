import hashlib
import struct

import pytest

from wild_find_pipeline import land


def square(lng, lat):
    return [[lng, lat], [lng + 1, lat], [lng + 1, lat + 1], [lng, lat]]


def test_rings_reads_polygons_and_multipolygons_with_their_holes():
    geojson = {
        "features": [
            {"geometry": {"type": "Polygon", "coordinates": [square(0, 0), square(0.2, 0.2)]}},
            {"geometry": {"type": "MultiPolygon", "coordinates": [[square(10, 10)], [square(-20, -5)]]}},
        ]
    }

    out = land.rings(geojson)

    assert len(out) == 4
    assert out[3][0] == (-20, -5)


def test_pack_writes_the_header_counts_and_hundredths_of_a_degree():
    data = land.pack([[[(-180.0, 89.996), (12.345, -0.004)]], []])

    assert data[:4] == b"WFLD"
    assert data[4:6] == bytes([1, 2])
    assert struct.unpack_from("<II", data, 6) == (1, 2)
    assert struct.unpack_from("<hhhh", data, 14) == (-18000, 9000, 1234, 0)
    assert struct.unpack_from("<I", data, 22) == (0,)
    assert len(data) == 26


def test_pack_rejects_a_point_off_the_globe():
    with pytest.raises(ValueError, match="off the globe"):
        land.pack([[[(181.0, 0.0)]]])


def test_fetch_keeps_a_cached_file_that_matches_its_pins(tmp_path, monkeypatch):
    body = b'{"features": []}'
    (tmp_path / "ne.geojson").write_bytes(body)
    monkeypatch.setattr(land.urllib.request, "urlretrieve", pytest.fail)

    path = land.fetch("ne.geojson", len(body), hashlib.sha256(body).hexdigest(), tmp_path)

    assert path.read_bytes() == body


def test_fetch_refuses_bytes_that_do_not_match_its_pins(tmp_path, monkeypatch):
    def download(url, path):
        assert land.COMMIT in url
        path.write_bytes(b"tampered")

    monkeypatch.setattr(land.urllib.request, "urlretrieve", download)

    with pytest.raises(ValueError, match="pinned size/SHA-256"):
        land.fetch("ne.geojson", 3, "0" * 64, tmp_path)


def test_write_packs_both_pinned_levels(tmp_path, monkeypatch):
    world = b'{"features": [{"geometry": {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [0, 1]]]}}]}'

    def fetch(name, size, sha, cache):
        path = tmp_path / name
        path.write_bytes(world)
        return path

    monkeypatch.setattr(land, "fetch", fetch)

    size = land.write(tmp_path / "land.bin", tmp_path)

    assert size == (tmp_path / "land.bin").stat().st_size
    assert (tmp_path / "land.bin").read_bytes()[5] == len(land.LEVELS)
