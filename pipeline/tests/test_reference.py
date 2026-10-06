import hashlib
import json

import numpy as np
import pytest
from PIL import Image

from wild_find_pipeline import reference
from wild_find_pipeline.paths import MANIFEST, pin, verified_artifact
from wild_find_pipeline.reference import SIZE, image_input, ranked, square_fixture


def test_square_fixture_center_crops_portrait_to_model_size():
    img = Image.new("RGB", (300, 500), (0, 0, 0))
    img.paste((255, 0, 0), (0, 100, 300, 400))  # the centered 300x300 square is all red

    out = square_fixture(img)

    assert out.size == (SIZE, SIZE)
    assert (np.asarray(out) == (255, 0, 0)).all()


def test_square_fixture_converts_to_rgb():
    assert square_fixture(Image.new("RGBA", (400, 400))).mode == "RGB"


def test_image_input_is_nchw_float32_in_unit_range_and_rgb_ordered():
    img = Image.new("RGB", (SIZE, SIZE), (255, 0, 51))

    x = image_input(img)

    assert x.shape == (1, 3, SIZE, SIZE)
    assert x.dtype == np.float32
    assert x[0, :, 0, 0] == pytest.approx([1.0, 0.0, 0.2])


@pytest.mark.parametrize("img", [Image.new("RGB", (SIZE, SIZE + 1)), Image.new("L", (SIZE, SIZE))])
def test_image_input_rejects_wrong_size_or_mode(img):
    with pytest.raises(ValueError):
        image_input(img)


def test_ranked_orders_by_score_descending():
    assert [k for k, _ in ranked({"lawn": 0.1, "oak": 0.3, "screen": -0.2})] == ["oak", "lawn", "screen"]


def test_pin_reads_bioclip_from_the_shared_manifest():
    bioclip = pin("bioclip")

    assert bioclip["file"] == "flora_student_fp16.onnx"
    assert bioclip["bytes"] == "23849085"
    assert len(bioclip["sha256"]) == 64


def test_pin_raises_for_unknown_model(tmp_path):
    manifest = tmp_path / "m.properties"
    manifest.write_text(f"# comment\n{MANIFEST.read_text()}")

    with pytest.raises(KeyError):
        pin("nope", manifest)


@pytest.fixture
def artifact(tmp_path):
    data = b"model-bytes"
    manifest = tmp_path / "m.properties"
    manifest.write_text(f"toy.file=toy.onnx\ntoy.bytes={len(data)}\ntoy.sha256={hashlib.sha256(data).hexdigest()}\n")
    (tmp_path / "toy.onnx").write_bytes(data)
    return tmp_path, manifest


def test_verified_artifact_returns_path_when_pins_match(artifact):
    cache, manifest = artifact

    assert verified_artifact("toy", cache, manifest) == cache / "toy.onnx"


def test_verified_artifact_rejects_same_size_different_bytes(artifact):
    cache, manifest = artifact
    (cache / "toy.onnx").write_bytes(b"MODEL-BYTES")

    with pytest.raises(ValueError, match="does not match"):
        verified_artifact("toy", cache, manifest)


def test_verified_artifact_rejects_wrong_size(artifact):
    cache, manifest = artifact
    (cache / "toy.onnx").write_bytes(b"short")

    with pytest.raises(ValueError, match="does not match"):
        verified_artifact("toy", cache, manifest)


def test_verified_artifact_rejects_missing_file(artifact):
    cache, manifest = artifact
    (cache / "toy.onnx").unlink()

    with pytest.raises(ValueError, match="missing"):
        verified_artifact("toy", cache, manifest)


def run_gate(monkeypatch, tmp_path, winner: int | None) -> int:
    """Run main() with stub models where label `winner` scores 1 and every other label 0 (label 0 is the word).

    `winner=None` stubs an all-zero image embedding, so every label ties; `winner=-1` stubs an all-NaN one.
    """
    labels = 1 + 5 + 6
    monkeypatch.setattr(reference, "REFERENCE_DIR", tmp_path / "ref")
    monkeypatch.setattr(reference, "fetch_fixture", lambda: Image.new("RGB", (300, 400)))
    monkeypatch.setattr(reference, "verified_artifact", lambda model: tmp_path / "model.onnx")
    image = np.zeros(labels) if winner is None else np.full(labels, np.nan) if winner == -1 else np.eye(labels)[winner]
    monkeypatch.setattr(reference, "embed_image", lambda path, x: image)
    monkeypatch.setattr(reference, "embed_texts", lambda texts: np.eye(len(texts)))
    return reference.main()


def test_main_writes_nothing_when_the_fixture_word_is_not_top_1(monkeypatch, tmp_path):
    assert run_gate(monkeypatch, tmp_path, winner=1) == 1
    assert not (tmp_path / "ref").exists()


def test_main_writes_fixture_and_reference_when_the_gate_passes(monkeypatch, tmp_path):
    assert run_gate(monkeypatch, tmp_path, winner=0) == 0

    written = json.loads((tmp_path / "ref" / "reference.json").read_text())
    assert ranked(written["scores"])[0][0] == reference.FIXTURE_WORD
    assert written["fixture"]["sha256"] == hashlib.sha256((tmp_path / "ref" / "fixture.png").read_bytes()).hexdigest()


def test_main_writes_nothing_on_a_tie(monkeypatch, tmp_path):
    assert run_gate(monkeypatch, tmp_path, winner=None) == 1
    assert not (tmp_path / "ref").exists()


def test_main_writes_nothing_when_scores_are_nan(monkeypatch, tmp_path):
    assert run_gate(monkeypatch, tmp_path, winner=-1) == 1
    assert not (tmp_path / "ref").exists()
