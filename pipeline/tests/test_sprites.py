import numpy as np
import pytest
from PIL import Image

from wild_find_pipeline.sprites import PLANT_PX, clip, key_white, plant_art


def test_plant_art_trims_the_margin_and_stands_the_plant_on_the_bottom_edge():
    source = Image.new("RGBA", (1254, 1254))
    source.paste((0, 120, 0, 255), (527, 427, 727, 827))  # a 200 x 400 plant, centered with a margin

    art = plant_art(source)
    box = art.getchannel("A").getbbox()

    assert art.size == (PLANT_PX, PLANT_PX)
    assert box[3] == PLANT_PX  # on the bottom edge
    assert box[1] == 0  # the taller side fills the square
    assert abs((box[0] + box[2]) / 2 - PLANT_PX / 2) <= 1  # centered across


def test_plant_art_rejects_an_empty_picture():
    with pytest.raises(ValueError):
        plant_art(Image.new("RGBA", (100, 100)))


def test_key_white_drops_edge_white_and_keeps_white_inside_briar():
    frame = np.full((40, 40, 3), 255, np.uint8)
    frame[10:30, 10:30] = (120, 80, 50)  # Briar
    frame[18:22, 18:22] = 255  # a white tail stripe inside him

    alpha = key_white(frame)[..., 3]

    assert alpha[0, 0] == 0
    assert alpha[20, 20] == 255
    assert alpha[15, 15] == 255


def test_key_white_drops_a_grey_shadow_touching_the_background():
    frame = np.full((40, 40, 3), 255, np.uint8)
    frame[10:30, 10:30] = (60, 140, 50)  # leaves
    frame[30:34, 8:32] = 205  # their grey shadow on the ground
    frame[14:18, 14:18] = 205  # the same grey inside the leaves, which stays

    alpha = key_white(frame)[..., 3]

    assert alpha[33, 20] == 0
    assert alpha[16, 16] == 255


def test_key_white_removes_white_spill_from_edges():
    frame = np.full((40, 40, 3), 255, np.uint8)
    frame[10:30, 10:30] = (120, 80, 50)
    frame[10:30, 10] = (187, 167, 152)  # half-white blend on Briar's left edge

    keyed = key_white(frame)

    # One pixel in, the edge is 40% opaque; dividing out the white leaves a darker fur tone, not a light rim.
    assert keyed[20, 10, 3] == 102
    assert abs(int(keyed[20, 10, 0]) - 85) <= 2


def test_clip_crops_every_frame_to_one_box_and_takes_the_figure_height_from_the_first():
    frames = []
    for grow in (0, 10):
        frame = np.full((60, 60, 3), 255, np.uint8)
        frame[20 : 40 + grow, 15:35] = (120, 80, 50)  # later frames grow leaves below his feet
        frames.append(frame)

    images, figure = clip(frames)

    assert len({i.size for i in images}) == 1
    # The keyed edge fades over its outer row, which falls under the solid-alpha cut on each side.
    assert figure == 18


def test_clip_rejects_a_video_with_no_briar():
    with pytest.raises(ValueError, match="all background"):
        clip([np.full((10, 10, 3), 255, np.uint8)])
