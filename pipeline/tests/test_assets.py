import hashlib
import json
import sys
import types

import numpy as np
import pytest

from wild_find_pipeline import assets, paths
from wild_find_pipeline.assets import plant_share, species_table
from wild_find_pipeline.labels import HAZARDS, is_hazard


def test_plant_share_sums_the_scaled_softmax_of_plant_labels():
    vectors = np.array([[1.0, 0.0], [0.0, 1.0]])
    logits = 50.0 * np.array([1.0, 0.0])
    expected = np.exp(logits[0]) / np.exp(logits).sum()

    assert plant_share(np.array([1.0, 0.0]), vectors, np.array([True, False]), 50.0) == pytest.approx(expected)


def test_plant_share_depends_on_the_scale():
    # Two weak plant matches beat one stronger non-plant match only without the scale.
    vectors = np.array([[0.5, 0.866], [0.5, -0.866], [0.6, 0.8]])
    plant = np.array([True, True, False])
    embedding = np.array([1.0, 0.0])

    assert plant_share(embedding, vectors, plant, 1.0) > 0.5
    assert plant_share(embedding, vectors, plant, 50.0) < 0.5


def test_species_table_appends_missing_rows_and_flags_hazards():
    table, labels = species_table(
        np.eye(2, dtype=np.float64),
        ["Quercus alba", "Toxicodendron radicans"],
        {"Toxicodendron radicans": np.zeros(2), "Toxicodendron pubescens": np.array([0.6, 0.8])},
    )

    assert table.dtype == np.float32
    assert table.shape == (3, 2)
    assert table[2].tolist() == pytest.approx([0.6, 0.8])
    assert labels == [
        {"scientific": "Quercus alba", "hazard": False},
        {"scientific": "Toxicodendron radicans", "hazard": True},
        {"scientific": "Toxicodendron pubescens", "hazard": True},
    ]


def test_species_table_unchanged_when_nothing_is_missing():
    table, labels = species_table(np.eye(1), ["Quercus alba"], {})

    assert table.shape == (1, 1)
    assert [entry["scientific"] for entry in labels] == ["Quercus alba"]


@pytest.mark.parametrize(
    ("name", "hazard"),
    [
        ("Toxicodendron diversilobum", True),
        ("Phytolacca americana", True),
        ("Solanum carolinense", True),
        ("Solanum lycopersicum", False),
        ("Quercus rubra", False),
    ],
)
def test_is_hazard_covers_every_toxicodendron_and_the_named_species(name, hazard):
    assert is_hazard(name) is hazard


@pytest.fixture
def pinned(tmp_path, monkeypatch):
    data = b"model-bytes"
    manifest = tmp_path / "m.properties"
    manifest.write_text(
        "toy.repo=org/toy\ntoy.revision=abc\ntoy.file=toy.onnx\n"
        f"toy.bytes={len(data)}\ntoy.sha256={hashlib.sha256(data).hexdigest()}\n"
    )
    calls = []

    def download(repo, filename, revision, local_dir, body=data):
        calls.append((repo, filename, revision))
        (local_dir / filename).write_bytes(body)

    monkeypatch.setitem(sys.modules, "huggingface_hub", types.SimpleNamespace(hf_hub_download=download))
    return tmp_path, manifest, calls


def test_ensure_artifact_downloads_the_pinned_revision_when_missing(pinned):
    cache, manifest, calls = pinned

    assert paths.ensure_artifact("toy", cache, manifest) == cache / "toy.onnx"
    assert calls == [("org/toy", "toy.onnx", "abc")]


def test_ensure_artifact_skips_the_download_when_already_verified(pinned):
    cache, manifest, calls = pinned
    (cache / "toy.onnx").write_bytes(b"model-bytes")

    paths.ensure_artifact("toy", cache, manifest)

    assert calls == []


def test_ensure_artifact_rejects_downloaded_bytes_that_miss_the_pins(pinned, monkeypatch):
    cache, manifest, _ = pinned
    bad = types.SimpleNamespace(hf_hub_download=lambda repo, f, revision, local_dir: (local_dir / f).write_bytes(b"x"))
    monkeypatch.setitem(sys.modules, "huggingface_hub", bad)

    with pytest.raises(ValueError, match="does not match"):
        paths.ensure_artifact("toy", cache, manifest)


@pytest.fixture
def stored(tmp_path, monkeypatch):
    path = tmp_path / "hazard_vectors.json"
    path.write_text(json.dumps({"species": {"Toxicodendron pubescens": [0.6, 0.8]}}))
    monkeypatch.setattr(assets, "HAZARD_VECTORS", path)


def test_hazard_vectors_returns_only_hazards_the_table_lacks(stored):
    names = [taxon for taxon in HAZARDS.values() if taxon != "Toxicodendron pubescens"]

    extra = assets.hazard_vectors(names)

    assert list(extra) == ["Toxicodendron pubescens"]
    assert extra["Toxicodendron pubescens"].dtype == np.float32


def test_hazard_vectors_fails_when_a_lacking_hazard_has_no_stored_vector(stored):
    with pytest.raises(ValueError, match="make hazard-vectors"):
        assets.hazard_vectors(["Toxicodendron radicans"])
