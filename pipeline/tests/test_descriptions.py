import pytest

from wild_find_pipeline import descriptions as d


def test_every_plant_type_gets_a_generic_sentence_with_the_right_article():
    assert d.describe("tree") == {"description": "A tree."}
    assert d.describe("shrub") == {"description": "A bush."}
    assert d.describe("herb") == {"description": "An herb."}
    assert d.describe("conifer") == {"description": "An evergreen."}
    assert d.describe(None) == {"description": "A plant."}


def test_a_description_never_carries_size_color_or_season():
    for kind in d.NOUNS:
        text = d.describe(kind)["description"]
        assert text == f"{d.article(d.NOUNS[kind])} {d.NOUNS[kind]}."


def test_a_banned_word_fails_the_build(monkeypatch):
    monkeypatch.setitem(d.NOUNS, "herb", "safe plant")

    with pytest.raises(ValueError, match="banned kid word"):
        d.describe("herb")
