import numpy as np
import pytest
from PIL import Image

from wild_find_pipeline.sprites import PLANT_PX, clip, feet, frames_of, key_white, plant_art, repack


def sheet_with(spots: list[tuple[int, int]], size=(400, 200), box=(20, 30)) -> Image.Image:
    """A sheet of solid frames whose top-left corners sit at `spots`, deliberately off any even grid."""
    sheet = Image.new("RGBA", size)
    for x, y in spots:
        sheet.paste((200, 100, 50, 255), (x, y, x + box[0], y + box[1]))
    return sheet


SPOTS = [(5, 10), (107, 4), (203, 15), (310, 9), (8, 112), (101, 120), (209, 105), (300, 118)]


def test_frames_come_out_in_reading_order():
    frames = frames_of(sheet_with(SPOTS), columns=4, rows=2)

    assert len(frames) == 8
    # Growing the outline only keeps pixels that have some alpha, so a hard-edged frame crops to itself.
    assert {f.size for f in frames} == {(20, 30)}
    assert [f.getpixel((0, 0)) for f in frames] == [(200, 100, 50, 255)] * 8


def test_repack_plants_every_frame_on_the_same_feet_point():
    sheet, cell, _ = repack(sheet_with(SPOTS), columns=4, rows=2)

    assert sheet.size == (4 * cell, 2 * cell)
    points = []
    for n in range(8):
        frame = sheet.crop(((n % 4) * cell, (n // 4) * cell, (n % 4 + 1) * cell, (n // 4 + 1) * cell))
        points.append(feet(frame))
    assert len({(round(x), y) for x, y in points}) == 1


def test_feet_center_on_the_lowest_rows():
    frame = Image.new("RGBA", (40, 60))
    frame.paste((0, 0, 0, 255), (0, 0, 40, 30))  # wide body
    frame.paste((0, 0, 0, 255), (25, 30, 35, 60))  # narrow foot on the right

    x, bottom = feet(frame)

    assert bottom == 59
    assert x == pytest.approx(29.5)


def test_feet_ignore_a_prop_beside_briar():
    frame = Image.new("RGBA", (80, 60))
    frame.paste((0, 0, 0, 255), (0, 0, 20, 60))  # Briar
    frame.paste((40, 140, 40, 255), (60, 40, 70, 60))  # a seedling standing on the same ground

    assert feet(frame) == (pytest.approx(9.5), 59)


def test_rejects_a_sheet_with_too_few_frames():
    with pytest.raises(ValueError, match="8 frames"):
        frames_of(sheet_with(SPOTS[:5]), columns=4, rows=2)


def test_soft_fur_edges_next_to_a_frame_are_kept():
    sheet = sheet_with([(50, 50)], size=(120, 120))
    sheet.putpixel((48, 60), (200, 100, 50, 40))  # faint fringe two pixels outside the solid outline

    frame = frames_of(sheet, columns=1, rows=1)[0]

    assert np.asarray(frame)[..., 3].max() == 255
    assert (np.asarray(frame)[..., 3] == 40).any()


def test_a_prop_drawn_apart_joins_its_frame():
    sheet = sheet_with(SPOTS)
    # A small "seedling" beside the first frame, not touching it.
    sheet.paste((40, 140, 40, 255), (30, 30, 36, 40))

    frames = frames_of(sheet, columns=4, rows=2)

    assert frames[0].size == (31, 30)
    assert {f.size for f in frames[1:]} == {(20, 30)}


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


def test_repack_reports_the_figure_height():
    _, _, figure = repack(sheet_with(SPOTS), columns=4, rows=2)

    assert figure == 30


def test_key_white_drops_edge_white_and_keeps_white_inside_briar():
    frame = np.full((40, 40, 3), 255, np.uint8)
    frame[10:30, 10:30] = (120, 80, 50)  # Briar
    frame[18:22, 18:22] = 255  # a white tail stripe inside him

    alpha = key_white(frame)[..., 3]

    assert alpha[0, 0] == 0
    assert alpha[20, 20] == 255
    assert alpha[15, 15] == 255


def test_key_white_removes_white_spill_from_edges():
    frame = np.full((40, 40, 3), 255, np.uint8)
    frame[10:30, 10:30] = (100, 100, 100)
    frame[10:30, 10] = 177  # half-white blend on Briar's left edge

    keyed = key_white(frame)

    # One pixel in, the edge is 40% opaque; dividing out the white leaves Briar's own grey, not a light rim.
    assert keyed[20, 10, 3] == 102
    assert abs(int(keyed[20, 10, 0]) - 62) <= 2


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
