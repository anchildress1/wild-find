import hashlib
import io
import struct

import pytest

from wild_find_pipeline import world_map


def square(lng, lat):
    return [[lng, lat], [lng + 1, lat], [lng + 1, lat + 1], [lng, lat]]


def test_shapes_reads_rings_and_lines_and_skips_empty_features():
    geojson = {
        "features": [
            {"geometry": {"type": "Polygon", "coordinates": [square(0, 0), square(0.2, 0.2)]}},
            {"geometry": {"type": "MultiPolygon", "coordinates": [[square(10, 10)], [square(-20, -5)]]}},
            {"geometry": {"type": "LineString", "coordinates": [[1, 2], [3, 4]]}},
            {"geometry": {"type": "MultiLineString", "coordinates": [[[5, 6], [7, 8]], [[9, 9], [9, 10]]]}},
            {"geometry": None},
        ]
    }

    out = world_map.shapes(geojson)

    assert len(out) == 7
    assert out[3][0] == (-20, -5)
    assert out[4] == [(1, 2), (3, 4)]


def test_shapes_refuses_an_unexpected_geometry():
    with pytest.raises(ValueError, match="unexpected geometry Point"):
        world_map.shapes({"features": [{"geometry": {"type": "Point", "coordinates": [0, 0]}}]})


def test_simplify_drops_points_near_the_line_and_keeps_corners():
    line = [(0.0, 0.0), (1.0, 0.005), (2.0, 0.0), (2.0, 1.0)]

    assert world_map.simplify(line, 0.02) == [(0.0, 0.0), (2.0, 0.0), (2.0, 1.0)]
    assert world_map.simplify(line, 0.001) == line
    assert world_map.simplify(line[:2], 10.0) == line[:2]


def test_simplify_keeps_a_closed_ring_closed():
    ring = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 0.0)]

    assert world_map.simplify(ring, 0.02) == ring


def test_pack_writes_the_header_layers_and_hundredths_of_a_degree():
    data = world_map.pack([(world_map.LAND, world_map.WORLD, [[(-180.0, 89.996), (12.345, -0.004)]]), (2, 1, [])])

    assert data[:4] == b"WFMP"
    assert data[4:6] == bytes([1, 2])
    assert struct.unpack_from("<BBI", data, 6) == (0, 0, 1)
    assert struct.unpack_from("<I", data, 12) == (2,)
    assert struct.unpack_from("<hhhh", data, 16) == (-18000, 9000, 1234, 0)
    assert struct.unpack_from("<BBI", data, 24) == (2, 1, 0)
    assert len(data) == 30


def test_pack_rejects_a_point_off_the_globe():
    with pytest.raises(ValueError, match="off the globe"):
        world_map.pack([(0, 0, [[(181.0, 0.0)]])])


def test_fetch_keeps_a_cached_file_that_matches_its_pins(tmp_path, monkeypatch):
    body = b'{"features": []}'
    (tmp_path / "ne.geojson").write_bytes(body)
    monkeypatch.setattr(world_map.urllib.request, "urlopen", pytest.fail)

    path = world_map.fetch("ne.geojson", len(body), hashlib.sha256(body).hexdigest(), tmp_path)

    assert path.read_bytes() == body


def test_fetch_refuses_bytes_that_do_not_match_its_pins(tmp_path, monkeypatch):
    def download(request, timeout):
        assert world_map.COMMIT in request.full_url
        assert request.get_header("User-agent") == world_map.USER_AGENT and timeout
        return io.BytesIO(b"tampered")

    monkeypatch.setattr(world_map.urllib.request, "urlopen", download)

    with pytest.raises(ValueError, match="pinned size/SHA-256"):
        world_map.fetch("ne.geojson", 3, "0" * 64, tmp_path)


def test_write_packs_every_pinned_layer_and_thins_only_the_state_lines(tmp_path, monkeypatch):
    wiggle = [[0, 0], [1, 0.001], [2, 0]]
    geojson = b'{"features": [{"geometry": {"type": "LineString", "coordinates": %s}}]}' % str(wiggle).encode()

    def fetch(name, size, sha, cache):
        path = tmp_path / name
        path.write_bytes(geojson)
        return path

    monkeypatch.setattr(world_map, "fetch", fetch)

    size = world_map.write(tmp_path / "map.bin", tmp_path)
    data = (tmp_path / "map.bin").read_bytes()

    assert size == len(data)
    assert data[5] == len(world_map.SOURCES)
    # Four unthinned 3-point layers, then the thinned 2-point state layer.
    assert len(data) == 6 + 4 * (6 + 4 + 3 * 4) + (6 + 4 + 2 * 4)
