import itertools

import pytest

from wild_find_pipeline import descriptions as d

SHOWY = d.SHOWY


def test_a_tree_whose_fruit_shows_in_fall_leads_with_it_and_names_fall_once():
    traits = {
        d.HEIGHT: ["30", "90"],
        SHOWY: ["fruitSeedConspicuousYes", "flowerConspicuousYes"],
        d.FRUIT_COLOR: ["PATO_0000953"],
        d.SEED_BEGIN: ["Thesaurus.owl#C94732"],
        d.SEED_END: ["Thesaurus.owl#C94733"],
        d.FLOWER_COLOR: ["PATO_0000324"],
        d.BLOOM: ["lateSpring"],
    }

    assert d.sentence("tree", traits) == "A tall tree with orange fruit in fall and yellow flowers in spring."


def test_bright_leaves_need_fall_show_without_showy_fruit_and_never_on_an_evergreen():
    fall = {SHOWY: ["fallConspicuousYes"]}

    assert d.sentence("tree", fall) == "A tree with bright leaves in fall."
    assert d.sentence("tree", {**fall, d.LEAF_RETENTION: ["PATO_0001731"]}) == "A tree with bright leaves in fall."
    # Southern magnolia: evergreen, so its fall show is not its leaves.
    assert d.sentence("tree", {**fall, d.LEAF_RETENTION: [d.EVERGREEN]}) is None
    # Only woody types are scored for Leaf Retention, so an herb's fall show stays unsaid.
    assert d.sentence("herb", fall) is None
    # Fall Conspicuous covers leaves or fruits; with showy fruit it can't say which.
    showy_fruit = {SHOWY: ["fallConspicuousYes", "fruitSeedConspicuousYes"], d.FRUIT_COLOR: ["PATO_0000322"]}
    assert d.sentence("shrub", showy_fruit) == "A bush with red fruit."


def test_fruit_names_fall_only_when_its_period_covers_fall():
    summer, fall, winter, spring = (f"Thesaurus.owl#C9473{n}" for n in (2, 3, 0, 1))

    assert d.fruit_season([summer], [fall]) == "fall"
    assert d.fruit_season([summer], [winter]) == "fall"
    assert d.fruit_season([winter], [spring]) is None
    assert d.fruit_season([summer], [summer]) is None
    assert d.fruit_season([fall], [summer]) == "fall"
    assert d.fruit_season([summer], []) is None
    assert d.fruit_season(["yearRound"], ["yearRound"]) is None


def test_flowers_need_one_known_non_green_color_and_take_their_season():
    assert d.sentence("herb", {d.FLOWER_COLOR: ["PATO_0000318"], d.BLOOM: ["Thesaurus.owl#C94733"]}) == (
        "A plant with blue flowers in fall."
    )
    assert d.sentence("herb", {d.FLOWER_COLOR: ["PATO_0000320"]}) is None
    assert d.sentence("herb", {d.FLOWER_COLOR: ["PATO_0000318", "PATO_0000323"]}) is None
    assert d.sentence("herb", {d.FLOWER_COLOR: ["PATO_0000323"], d.BLOOM: ["midSpring", "midSummer"]}) == (
        "A plant with white flowers."
    )


def test_trees_need_showy_flowers_and_conifers_ferns_and_grasses_get_none():
    yellow = {d.FLOWER_COLOR: ["PATO_0000324"], d.BLOOM: ["midSpring"]}

    assert d.sentence("tree", yellow) is None
    assert d.sentence("tree", {**yellow, SHOWY: ["flowerConspicuousYes"]}) == "A tree with yellow flowers in spring."
    assert d.sentence("shrub", yellow) == "A bush with yellow flowers in spring."
    for kind in d.NO_FLOWERS:
        assert d.sentence(kind, {**yellow, SHOWY: ["flowerConspicuousYes"]}) is None


def test_size_words_follow_the_tallest_height_per_type_and_vines_get_none():
    assert d.sentence("herb", {d.HEIGHT: ["1"]}) == "A low plant."
    assert d.sentence("shrub", {d.HEIGHT: ["2", "15"]}) == "A big bush."
    assert d.sentence("tree", {d.HEIGHT: ["40"]}) is None
    assert d.sentence("vine", {d.HEIGHT: ["90"]}) is None
    assert d.sentence("conifer", {d.HEIGHT: ["80"]}) == "A tall evergreen."


def test_unshowy_fruit_plain_leaves_and_unknown_terms_say_nothing():
    traits = {
        SHOWY: ["fruitSeedConspicuousNo", "fallConspicuousNo"],
        d.FRUIT_COLOR: ["PATO_0000322"],
        d.LEAF_COLOR: ["PATO_0000320"],
        d.BLOOM: ["Thesaurus.owl#C48658"],
    }

    assert d.sentence("tree", traits) is None
    assert d.sentence("herb", {d.LEAF_COLOR: ["grayGreen"]}) == "A plant with gray-green leaves."


def test_brown_fruit_reads_as_seeds_and_at_most_two_features_show():
    traits = {
        SHOWY: ["fruitSeedConspicuousYes", "flowerConspicuousYes"],
        d.FRUIT_COLOR: ["PATO_0000952"],
        d.SEED_BEGIN: ["Thesaurus.owl#C94733"],
        d.SEED_END: ["Thesaurus.owl#C94733"],
        d.FLOWER_COLOR: ["PATO_0000322"],
        d.LEAF_COLOR: ["whiteGrey"],
    }

    assert d.sentence("tree", traits) == "A tree with brown seeds in fall and red flowers."


def test_the_article_fits_the_noun_and_the_first_name_with_a_sentence_wins():
    out = d.describe("conifer", [("Pinus a", {}), ("Pinus b", {d.HEIGHT: ["100"]})])

    assert out == {"description": "A tall evergreen.", "usda": "Pinus b", "traits": {d.HEIGHT: ["100"]}}
    assert d.article("evergreen") == "An"
    assert d.describe("tree", []) == {"description": None, "usda": None, "traits": {}}


SPAN = {d.SEED_BEGIN: ["Thesaurus.owl#C94731"], d.SEED_END: ["Thesaurus.owl#C94733"]}


def test_no_generated_sentence_ever_uses_a_banned_kid_word():
    colors = list(d.COLORS)
    for kind, flower, fruit, showy in itertools.product(
        d.NOUNS, colors, colors, [["fallConspicuousYes"], ["fallConspicuousYes", "fruitSeedConspicuousYes"], []]
    ):
        traits = {d.FLOWER_COLOR: [flower], d.FRUIT_COLOR: [fruit], SHOWY: showy, d.HEIGHT: ["0.5"], **SPAN}
        text = d.sentence(kind, traits) or ""
        assert not any(word in text.lower() for word in d.BANNED), text


def test_a_banned_word_fails_the_build(monkeypatch):
    monkeypatch.setitem(d.NOUNS, "herb", "safe plant")

    with pytest.raises(ValueError, match="banned kid word"):
        d.sentence("herb", {d.HEIGHT: ["0.5"]})
