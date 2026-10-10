import numpy as np
import pytest
from PIL import Image

from wild_find_pipeline.sprites import PLANT_PX, PLANT_TYPES, UNTYPED, clip, key_white, plant_art, trimmed


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


def test_key_white_drops_paper_white_that_briar_encloses_but_keeps_eye_whites_and_fur_highlights():
    frame = np.full((60, 60, 3), 255, np.uint8)
    frame[5:55, 5:55] = (120, 80, 50)  # Briar
    frame[10:20, 10:20] = 252  # background seen between his legs: bright, flat, neutral
    frame[30:40, 10:20] = 243  # an eye white: a shade darker
    frame[30:40, 30:40] = (250, 242, 236)  # a cream fur highlight: warm

    alpha = key_white(frame)[..., 3]

    assert alpha[15, 15] == 0
    assert alpha[35, 15] == 255
    assert alpha[35, 35] == 255


def test_key_white_keeps_a_tiny_white_speck():
    frame = np.full((40, 40, 3), 255, np.uint8)
    frame[5:35, 5:35] = (120, 80, 50)
    frame[18:22, 18:22] = 255  # 16 px, under POCKET_PX: a catchlight, not a gap

    assert key_white(frame)[20, 20, 3] == 255


def test_untyped_picture_is_not_a_plant_type():
    assert UNTYPED not in PLANT_TYPES


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


def test_key_white_drops_floor_white_and_warm_shadow_the_leaves_wall_off_but_keeps_the_paws():
    frame = np.full((100, 100, 3), 255, np.uint8)
    frame[10:90, 10:90] = (60, 140, 50)  # a wall of leaves down to the floor
    frame[78:84, 30:40] = 250  # floor white closed off by the leaves, below the knees
    frame[86:89, 50:60] = (205, 185, 160)  # the leaves' warm shadow, past the paws' tops
    frame[80:88, 70:80] = (110, 70, 55)  # a brown paw
    frame[40:46, 30:40] = (240, 225, 210)  # cream fur higher up, above the floor band

    alpha = key_white(frame)[..., 3]

    assert alpha[81, 35] == 0
    assert alpha[87, 55] == 0
    assert alpha[84, 75] == 255
    assert alpha[43, 35] == 255


def test_trimmed_crops_to_the_visible_pixels_and_rejects_an_empty_picture():
    picture = Image.new("RGBA", (50, 40))
    picture.paste((40, 90, 30, 255), (10, 5, 30, 25))
    picture.putpixel((45, 35), (255, 255, 255, 4))  # faint glow, under ALPHA_FLOOR

    assert trimmed(picture).size == (20, 20)
    with pytest.raises(ValueError, match="empty"):
        trimmed(Image.new("RGBA", (10, 10)))
