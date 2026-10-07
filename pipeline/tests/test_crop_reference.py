import numpy as np
from PIL import Image

from wild_find_pipeline.crop_reference import DOWN_SIZE, SOURCE_SIZE, references, reticle, source, write
from wild_find_pipeline.reference import SIZE


def test_reticle_is_the_centered_sixty_percent_square():
    img = Image.new("RGB", (333, 250), (0, 0, 0))
    img.paste((0, 255, 0), (91, 50, 241, 200))  # the 150 x 150 reticle square at floor-centered offsets

    out = reticle(img)

    assert out.size == (SIZE, SIZE)
    assert (np.asarray(out) == (0, 255, 0)).all()


def test_source_is_the_centered_region_in_rgb():
    photo = Image.new("RGBA", (375, 500))

    out = source(photo)

    assert out.size == SOURCE_SIZE
    assert out.mode == "RGB"


def test_rotated_references_turn_back_upright_clockwise():
    upright = Image.new("RGB", (3, 2))
    upright.putpixel((0, 0), (255, 0, 0))

    refs = references(upright)

    # Each rotNN rotated clockwise by NN degrees (PIL rotates counterclockwise, hence the minus) is the upright frame.
    for degrees in (90, 180, 270):
        assert refs[f"rot{degrees}.png"].rotate(-degrees, expand=True).tobytes() == upright.tobytes()
    assert refs["down.png"].size == DOWN_SIZE
    assert refs["full.png"].size == refs["reticle.png"].size == (SIZE, SIZE)


def test_write_saves_lossless_png(tmp_path):
    img = Image.new("RGB", (2, 2), (1, 2, 3))

    write(tmp_path, {"a.png": img})

    assert Image.open(tmp_path / "a.png").tobytes() == img.tobytes()
