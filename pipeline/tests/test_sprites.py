from PIL import Image

from wild_find_pipeline.sprites import repack


def test_repack_puts_fractional_cells_onto_whole_square_cells():
    source = Image.new("RGBA", (1774, 887))

    sheet, cell = repack(source, 4, 2)

    assert cell == 448
    assert sheet.size == (1792, 896)


def test_repack_keeps_every_frame_in_its_own_cell_at_the_same_offset():
    source = Image.new("RGBA", (9, 3))
    for i, color in enumerate([(255, 0, 0, 255), (0, 255, 0, 255), (0, 0, 255, 255)]):
        source.putpixel((i * 3, 0), color)  # top-left pixel of each 3 px cell

    sheet, cell = repack(source, 3, 1)

    assert cell == 8
    assert [sheet.getpixel((i * cell + 2, 2)) for i in range(3)] == [
        (255, 0, 0, 255),
        (0, 255, 0, 255),
        (0, 0, 255, 255),
    ]
