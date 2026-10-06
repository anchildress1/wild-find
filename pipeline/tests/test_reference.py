import hashlib

import numpy as np
import pytest
from PIL import Image

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
