import json
import struct

from wild_find_pipeline import places


def feature(name, ring, key="name_en"):
    return {"properties": {key: name}, "geometry": {"type": "Polygon", "coordinates": [ring]}}


SQUARE = [[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]


def test_places_names_each_feature_by_the_first_property_it_has_and_drops_empty_ones():
    geojson = {
        "features": [
            feature("Georgia", SQUARE),
            feature("Kakheti", SQUARE, key="name"),
            {"properties": {"name_en": "Nowhere"}, "geometry": None},
            {"properties": {}, "geometry": {"type": "Polygon", "coordinates": [SQUARE]}},
            feature("Speck", [[0, 0], [0.001, 0], [0, 0.001], [0, 0]]),
        ]
    }

    out = places.places(geojson, places.STATE, ("name_en", "name"))

    assert [(kind, name) for kind, name, _ in out] == [(0, "Georgia"), (0, "Kakheti")]
    assert out[0][2] == [[(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)]]


def test_pack_writes_a_shared_name_table_then_places_in_order():
    ring = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 0.0)]
    data = places.pack([(places.STATE, "Bahía", [ring]), (places.COUNTRY, "Bahía", [ring, ring])])

    assert data[:5] == b"WFPL\x01"
    assert struct.unpack_from("<HH", data, 5) == (1, len("Bahía".encode()))
    end = 9 + len("Bahía".encode())
    assert data[9:end].decode() == "Bahía"
    assert struct.unpack_from("<IBHH", data, end) == (2, 0, 0, 1)
    assert struct.unpack_from("<I", data, end + 9) == (4,)
    assert struct.unpack_from("<hh", data, end + 13 + 8) == (100, 100)


def test_write_packs_states_before_countries(tmp_path, monkeypatch):
    def fetch(name, size, sha, cache):
        path = tmp_path / name
        key = "name_en" if "admin_1" in name else "NAME_EN"
        path.write_text(json.dumps({"features": [feature(name[:6], SQUARE, key=key)]}))
        return path

    monkeypatch.setattr(places, "fetch", fetch)

    size = places.write(tmp_path / "places.bin", tmp_path)
    data = (tmp_path / "places.bin").read_bytes()

    assert size == len(data)
    assert data.index(b"ne_10m") < data.index(b"ne_50m")
