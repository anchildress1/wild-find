import json

import numpy as np
import pytest

from wild_find_pipeline import label_vectors
from wild_find_pipeline.label_vectors import check_vectors, label_rows, write
from wild_find_pipeline.labels import GRASS, HAZARDS, TUTORIAL


def test_label_rows_are_the_eleven_tutorial_labels_in_order():
    rows = label_rows()

    assert [r["scientific"] for r in rows] == list(TUTORIAL)
    assert rows[0] == {"scientific": GRASS, "prompt": "a photo of Poaceae."}


def test_tutorial_set_is_prd_r3():
    assert len(TUTORIAL) == 11
    assert TUTORIAL[0] == GRASS
    assert set(HAZARDS.values()) <= set(TUTORIAL)


def test_check_vectors_returns_little_endian_float32():
    out = check_vectors(np.eye(2, dtype=np.float64), 2)

    assert out.dtype == np.dtype("<f4")
    assert out.flags.c_contiguous


@pytest.mark.parametrize(
    "vectors",
    [np.eye(3), np.ones((2,)), np.array([[1.0, 0.0], [np.nan, 0.0]]), np.array([[1.0, 0.0], [0.5, 0.0]])],
)
def test_check_vectors_rejects_wrong_count_shape_nan_and_non_unit(vectors):
    with pytest.raises(ValueError):
        check_vectors(vectors, 2)


def test_write_saves_parallel_npy_and_json(tmp_path, monkeypatch):
    # The real lookup needs the reference group's open-clip-torch, which CI's pipeline tests don't install.
    monkeypatch.setattr(label_vectors, "embedding_versions", lambda: {"open-clip-torch": "x", "torch": "y"})
    rows = label_rows()
    vectors = np.zeros((len(rows), 2))
    vectors[:, 0] = 1.0

    write(tmp_path, rows, vectors)

    saved = np.load(tmp_path / "labels.npy")
    meta = json.loads((tmp_path / "labels.json").read_text())
    assert saved.shape == (11, 2)
    assert meta["schema_version"] == 2
    assert meta["labels"] == rows
    assert meta["packages"] == {"open-clip-torch": "x", "torch": "y"}


@pytest.fixture
def committed(tmp_path, monkeypatch):
    monkeypatch.setattr(label_vectors, "embedding_versions", lambda: {"open-clip-torch": "x", "torch": "y"})
    rows = label_rows()
    vectors = np.zeros((len(rows), 2))
    vectors[:, 0] = 1.0
    write(tmp_path, rows, vectors)
    return tmp_path


def test_committed_labels_that_match_pass(committed):
    label_vectors.check_committed(committed)


@pytest.mark.parametrize(
    "drift",
    [
        lambda mp: TUTORIAL[:-1],
        lambda mp: (*TUTORIAL, "Acer"),
        lambda mp: mp.setattr(label_vectors, "prompt", lambda text: f"an image of {text}.") or TUTORIAL,
        lambda mp: mp.setattr(label_vectors, "teacher_model", lambda: {"repo": "x", "revision": "y"}) or TUTORIAL,
        lambda mp: mp.setattr(label_vectors, "embedding_versions", lambda: {"torch": "z"}) or TUTORIAL,
    ],
    ids=["dropped label", "new label", "prompt", "teacher", "packages"],
)
def test_committed_labels_that_drift_are_rejected(committed, monkeypatch, drift):
    taxa = drift(monkeypatch)

    with pytest.raises(ValueError):
        label_vectors.check_committed(committed, taxa)


def test_committed_vectors_of_the_wrong_count_are_rejected(committed):
    np.save(committed / "labels.npy", np.ones((12, 2), dtype="<f4"))

    with pytest.raises(ValueError):
        label_vectors.check_committed(committed)
