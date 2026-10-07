import json

import numpy as np
import pytest

from wild_find_pipeline import label_vectors
from wild_find_pipeline.label_vectors import check_vectors, label_rows, write
from wild_find_pipeline.labels import DEV_WORDS, GRASS, HAZARDS, TUTORIAL


def test_label_rows_put_words_first_then_the_eleven_tutorial_labels():
    rows = label_rows({"oak": "Quercus"})

    assert rows[0] == {"id": "oak", "kind": "word", "scientific": "Quercus"}
    assert [r["id"] for r in rows[1:]] == list(TUTORIAL)
    assert {r["kind"] for r in rows[1:]} == {"tutorial"}


def test_tutorial_set_is_prd_r3():
    assert len(TUTORIAL) == 11
    assert TUTORIAL[0] == GRASS
    assert set(HAZARDS.values()) <= set(TUTORIAL)
    assert set(DEV_WORDS.values()) <= set(TUTORIAL)


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
    rows = label_rows({"oak": "Quercus"})
    vectors = np.zeros((len(rows), 2))
    vectors[:, 0] = 1.0

    write(tmp_path, rows, vectors)

    saved = np.load(tmp_path / "labels.npy")
    meta = json.loads((tmp_path / "labels.json").read_text())
    assert saved.shape == (12, 2)
    assert meta["schema_version"] == 1
    assert meta["labels"][0] == {"id": "oak", "kind": "word", "scientific": "Quercus", "prompt": "a photo of Quercus."}
    assert len(meta["labels"]) == 12
    assert meta["packages"] == {"open-clip-torch": "x", "torch": "y"}
