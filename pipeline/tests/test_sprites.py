import numpy as np
import pytest
from PIL import Image

from wild_find_pipeline.sprites import feet, frames_of, repack


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
    sheet, cell = repack(sheet_with(SPOTS), columns=4, rows=2)

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


def test_rejects_a_sheet_with_too_few_frames():
    with pytest.raises(ValueError, match="8 frames"):
        frames_of(sheet_with(SPOTS[:5]), columns=4, rows=2)


def test_soft_fur_edges_next_to_a_frame_are_kept():
    sheet = sheet_with([(50, 50)], size=(120, 120))
    sheet.putpixel((48, 60), (200, 100, 50, 40))  # faint fringe two pixels outside the solid outline

    frame = frames_of(sheet, columns=1, rows=1)[0]

    assert np.asarray(frame)[..., 3].max() == 255
    assert (np.asarray(frame)[..., 3] == 40).any()
