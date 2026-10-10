import hashlib
import json
import sys
import types

import numpy as np
import pytest

from wild_find_pipeline import assets, labels, paths
from wild_find_pipeline.assets import (
    plant_share,
    species_table,
    with_descriptions,
    with_hints,
    with_plant_types,
    with_synonyms,
    with_toxicity,
)
from wild_find_pipeline.labels import HAZARDS, embedding_versions, is_hazard, lacking_hazards, table_rows
from wild_find_pipeline.paths import pin


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
        {"Toxicodendron pubescens": np.array([0.6, 0.8])},
    )

    assert table.dtype == np.float32
    assert table.shape == (3, 2)
    assert table[2].tolist() == pytest.approx([0.6, 0.8])
    assert labels == [
        {"scientific": "Quercus alba", "hazard": False},
        {"scientific": "Toxicodendron radicans", "hazard": True},
        {"scientific": "Toxicodendron pubescens", "hazard": True},
    ]


def test_species_table_rejects_labels_that_do_not_match_its_rows():
    with pytest.raises(ValueError, match="2 rows but 1 labels"):
        species_table(np.eye(2), ["Quercus alba"], {})


def test_species_table_unchanged_when_nothing_is_missing():
    table, labels = species_table(np.eye(1), ["Quercus alba"], {})

    assert table.shape == (1, 1)
    assert [entry["scientific"] for entry in labels] == ["Quercus alba"]


def test_lacking_hazards_keeps_hazards_order():
    present = [taxon for taxon in HAZARDS.values() if taxon not in ("Toxicodendron vernix", "Phytolacca americana")]

    assert lacking_hazards(present) == ["Toxicodendron vernix", "Phytolacca americana"]


def test_table_rows_are_the_pinned_names_then_the_hazards_they_lack(tmp_path, monkeypatch):
    present = [taxon for taxon in HAZARDS.values() if taxon != "Toxicodendron pubescens"]
    (tmp_path / "taxa.json").write_text(json.dumps([{"scientific": n} for n in ["Quercus alba", *present]]))
    monkeypatch.setattr(labels, "ensure_artifact", lambda model: tmp_path / "taxa.json")

    assert table_rows() == ["Quercus alba", *present, "Toxicodendron pubescens"]


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

    def download(repo, filename, revision, local_dir, force_download, body=data):
        calls.append((repo, filename, revision, force_download))
        (local_dir / filename).write_bytes(body)

    monkeypatch.setitem(sys.modules, "huggingface_hub", types.SimpleNamespace(hf_hub_download=download))
    return tmp_path, manifest, calls


def test_ensure_artifact_downloads_the_pinned_revision_when_missing(pinned):
    cache, manifest, calls = pinned

    assert paths.ensure_artifact("toy", cache, manifest) == cache / "toy.onnx"
    assert calls == [("org/toy", "toy.onnx", "abc", True)]


def test_ensure_artifact_force_redownloads_a_corrupt_cached_file(pinned):
    cache, manifest, calls = pinned
    (cache / "toy.onnx").write_bytes(b"MODEL-BYTES")

    assert paths.ensure_artifact("toy", cache, manifest).read_bytes() == b"model-bytes"
    assert calls == [("org/toy", "toy.onnx", "abc", True)]


def test_ensure_artifact_skips_the_download_when_already_verified(pinned):
    cache, manifest, calls = pinned
    (cache / "toy.onnx").write_bytes(b"model-bytes")

    paths.ensure_artifact("toy", cache, manifest)

    assert calls == []


def test_ensure_artifact_rejects_downloaded_bytes_that_miss_the_pins(pinned, monkeypatch):
    cache, manifest, _ = pinned
    bad = types.SimpleNamespace(
        hf_hub_download=lambda repo, f, revision, local_dir, force_download: (local_dir / f).write_bytes(b"x")
    )
    monkeypatch.setitem(sys.modules, "huggingface_hub", bad)

    with pytest.raises(ValueError, match="does not match"):
        paths.ensure_artifact("toy", cache, manifest)


@pytest.fixture
def stored(tmp_path, monkeypatch):
    path = tmp_path / "hazard_vectors.json"
    path.write_text(
        json.dumps(
            {
                "text_model": {"repo": pin("teacher")["repo"], "revision": pin("teacher")["revision"]},
                "taxa_labels_sha256": pin("taxa_labels")["sha256"],
                "prompts": {"Toxicodendron pubescens": "a photo of Toxicodendron pubescens."},
                "packages": {"open-clip-torch": "3.3.0", "torch": "2.14.1"},
                "species": {"Toxicodendron pubescens": [0.6, 0.8]},
            }
        )
    )
    monkeypatch.setattr(assets, "HAZARD_VECTORS", path)
    monkeypatch.setattr(assets, "embedding_versions", lambda: {"open-clip-torch": "3.3.0", "torch": "2.14.1"})


def test_hazard_vectors_returns_only_hazards_the_table_lacks(stored):
    names = [taxon for taxon in HAZARDS.values() if taxon != "Toxicodendron pubescens"]

    extra = assets.hazard_vectors(names)

    assert list(extra) == ["Toxicodendron pubescens"]
    assert extra["Toxicodendron pubescens"].dtype == np.float32


def test_hazard_vectors_fails_when_a_lacking_hazard_has_no_stored_vector(stored):
    with pytest.raises(ValueError, match="make hazard-vectors"):
        assets.hazard_vectors(["Toxicodendron radicans"])


@pytest.mark.parametrize(
    "stale",
    [
        {"text_model": {"repo": "imageomics/bioclip-2.5-vith14", "revision": "old"}},
        {"taxa_labels_sha256": "0" * 64},
        {"packages": {"open-clip-torch": "3.2.0", "torch": "2.14.1"}},
        {"prompts": {"Toxicodendron pubescens": "a photo of poison oak."}},
    ],
)
def test_hazard_vectors_rejects_rows_built_against_other_pins(stored, stale):
    path = assets.HAZARD_VECTORS
    path.write_text(json.dumps({**json.loads(path.read_text()), **stale}))

    with pytest.raises(ValueError, match="run make hazard-vectors"):
        assets.hazard_vectors(["Toxicodendron radicans"])


def test_publish_swaps_the_folder_and_stamps_inputs_last(tmp_path, monkeypatch):
    staging, target, stamp = tmp_path / "staging", tmp_path / "assets", tmp_path / "inputs.json"
    staging.mkdir()
    (staging / "new.bin").write_bytes(b"new")
    target.mkdir()
    (target / "old.bin").write_bytes(b"old")
    (tmp_path / "assets.new").mkdir()  # leftover from an interrupted run
    monkeypatch.setattr(assets, "INPUTS", (paths.MANIFEST,))

    assets.publish(staging, target, stamp)

    assert sorted(p.name for p in target.iterdir()) == ["new.bin"]
    assert not (tmp_path / "assets.new").exists()
    assert not (tmp_path / "assets.old").exists()
    manifest_key = "core/src/main/resources/models.properties"
    assert json.loads(stamp.read_text()) == {manifest_key: paths.file_sha256(paths.MANIFEST)}


def test_embedding_versions_drop_local_labels(monkeypatch):
    import importlib.metadata

    monkeypatch.setattr(importlib.metadata, "version", {"open-clip-torch": "3.3.0", "torch": "2.14.1+cpu"}.__getitem__)

    assert embedding_versions() == {"open-clip-torch": "3.3.0", "torch": "2.14.1"}


def test_with_toxicity_adds_genus_and_the_committed_flag():
    labels = [{"scientific": "Quercus nigra", "hazard": False}, {"scientific": "Nandina domestica", "hazard": False}]
    flags = {"Quercus nigra": {"toxic": False}, "Nandina domestica": {"toxic": True}}

    assert with_toxicity(labels, flags) == [
        {"scientific": "Quercus nigra", "hazard": False, "genus": "Quercus", "toxic": False},
        {"scientific": "Nandina domestica", "hazard": False, "genus": "Nandina", "toxic": True},
    ]


def test_with_toxicity_rejects_a_row_without_a_flag():
    with pytest.raises(ValueError, match="run make toxicity"):
        with_toxicity([{"scientific": "Quercus nigra", "hazard": False}], {})


def test_with_toxicity_rejects_a_hazard_that_is_not_flagged():
    labels = [{"scientific": "Toxicodendron radicans", "hazard": True}]

    with pytest.raises(ValueError, match=r"hazards \['Toxicodendron radicans'\] unflagged"):
        with_toxicity(labels, {"Toxicodendron radicans": {"toxic": False}})


def test_with_synonyms_adds_each_rows_aliases():
    labels = [{"scientific": "Berberis bealei", "toxic": False}, {"scientific": "Quercus nigra", "toxic": False}]
    aliases = {"Berberis bealei": ["Mahonia bealei"], "Quercus nigra": []}

    assert with_synonyms(labels, aliases) == [
        {"scientific": "Berberis bealei", "toxic": False, "synonyms": ["Mahonia bealei"]},
        {"scientific": "Quercus nigra", "toxic": False, "synonyms": []},
    ]


def test_with_synonyms_rejects_a_row_without_an_entry():
    with pytest.raises(ValueError, match="run make synonyms"):
        with_synonyms([{"scientific": "Quercus nigra", "hazard": False}], {"Quercus alba": []})


def test_with_plant_types_adds_each_rows_type_or_none():
    labels = [
        {"scientific": "Quercus nigra", "toxic": False},
        {"scientific": "Erechtites hieraciifolius", "toxic": False},
    ]
    types = {"Quercus nigra": {"type": "tree", "source": "usda"}, "Erechtites hieraciifolius": {"type": None}}

    assert with_plant_types(labels, types) == [
        {"scientific": "Quercus nigra", "toxic": False, "type": "tree"},
        {"scientific": "Erechtites hieraciifolius", "toxic": False, "type": None},
    ]


def test_with_plant_types_rejects_a_row_without_an_entry():
    with pytest.raises(ValueError, match="run make plant-types"):
        with_plant_types([{"scientific": "Quercus nigra", "hazard": False}], {"Quercus alba": {"type": "tree"}})


def test_with_descriptions_adds_each_rows_sentence_or_none():
    labels = [{"scientific": "Quercus nigra"}, {"scientific": "Epifagus virginiana"}]
    found = {"Quercus nigra": {"description": "A tall tree."}, "Epifagus virginiana": {"description": None}}

    assert with_descriptions(labels, found) == [
        {"scientific": "Quercus nigra", "description": "A tall tree."},
        {"scientific": "Epifagus virginiana", "description": None},
    ]


def test_with_descriptions_rejects_a_row_without_an_entry():
    with pytest.raises(ValueError, match="run make descriptions"):
        with_descriptions([{"scientific": "Quercus nigra"}], {})


def test_with_hints_ships_only_picked_clean_hints_best_first_and_names_the_season():
    entry = {
        "hints": [
            {"aspect": "ground", "text": "It likes moist soil.", "issues": [], "score": 3.0},
            {"aspect": "place", "text": "Look in woods.", "issues": [], "score": 3.0},
            {"aspect": "season", "text": "Look for red fruit in fall.", "issues": [], "score": 1.3, "bucket": "fall"},
            {"aspect": "place", "text": "Look near streams.", "issues": ["water"], "score": None},
            {"aspect": "light", "text": "It likes sun.", "issues": [], "score": None},
        ]
    }

    label = {"scientific": "Quercus nigra", "toxic": False, "hazard": False}
    shipped = with_hints([label], {"done": True, "species": {"Quercus nigra": entry}})

    assert shipped == [
        {
            **label,
            "hints": [
                {"text": "It likes moist soil."},
                {"text": "Look in woods."},
                {"text": "Look for red fruit in fall.", "season": "fall"},
            ],
        }
    ]


def test_with_hints_gives_toxic_and_hazard_rows_none_and_refuses_a_checkpoint():
    toxic = {"scientific": "Conium maculatum", "toxic": True, "hazard": False}
    hazard = {"scientific": "Toxicodendron radicans", "toxic": True, "hazard": True}

    assert with_hints([toxic, hazard], {"done": True, "species": {}}) == [
        {**toxic, "hints": []},
        {**hazard, "hints": []},
    ]
    with pytest.raises(ValueError, match="make hints"):
        with_hints([], {"done": False, "species": {}})


def test_with_hints_refuses_a_playable_row_the_file_lacks():
    row = {"scientific": "Quercus nigra", "toxic": False, "hazard": False}

    with pytest.raises(ValueError, match=r"lacks 1 playable species, e\.g\. \['Quercus nigra'\]"):
        with_hints([row], {"done": True, "species": {}})


def test_with_hints_refuses_a_season_the_app_does_not_know():
    row = {"scientific": "Quercus nigra", "toxic": False, "hazard": False}
    odd = {"aspect": "season", "text": "x", "issues": [], "score": 1.0, "bucket": "monsoon"}

    with pytest.raises(ValueError, match="monsoon"):
        with_hints([row], {"done": True, "species": {"Quercus nigra": {"hints": [odd]}}})
