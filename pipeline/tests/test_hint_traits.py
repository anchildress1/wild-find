import itertools

from wild_find_pipeline import hint_traits as t
from wild_find_pipeline.descriptions import BANNED, NOUNS

SHOWY = t.SHOWY


def texts(kind, traits):
    return {c["aspect"]: c["text"] for c in t.candidates(kind, traits)}


def test_a_tree_whose_fruit_shows_in_fall_gets_a_fall_hint_and_a_size_hint():
    traits = {
        t.HEIGHT: ["30", "90"],
        SHOWY: ["fruitSeedConspicuousYes", "flowerConspicuousYes"],
        t.FRUIT_COLOR: ["PATO_0000953"],
        t.SEED_BEGIN: ["Thesaurus.owl#C94732"],
        t.SEED_END: ["Thesaurus.owl#C94733"],
        t.FLOWER_COLOR: ["PATO_0000324"],
        t.BLOOM: ["lateSpring"],
    }

    assert texts("tree", traits) == {"size": "It is a tall tree.", "season": "Look for orange fruit in fall."}


def test_bright_leaves_need_fall_show_without_showy_fruit_and_never_on_an_evergreen():
    fall = {SHOWY: ["fallConspicuousYes"]}

    assert texts("tree", fall) == {"season": "Look for bright leaves in fall."}
    assert texts("tree", {**fall, t.LEAF_RETENTION: [t.EVERGREEN]}) == {}
    # Only woody types are scored for Leaf Retention, so an herb's fall show stays unsaid.
    assert texts("herb", fall) == {}
    # Fall Conspicuous covers leaves or fruits; with showy fruit it can't say which.
    showy_fruit = {SHOWY: ["fallConspicuousYes", "fruitSeedConspicuousYes"], t.FRUIT_COLOR: ["PATO_0000322"]}
    assert texts("shrub", showy_fruit) == {"sign": "Look for red fruit."}


def test_fruit_names_fall_only_when_its_period_covers_fall():
    summer, fall, winter, spring = (f"Thesaurus.owl#C9473{n}" for n in (2, 3, 0, 1))

    assert t.fruit_season([summer], [fall]) == "fall"
    assert t.fruit_season([summer], [winter]) == "fall"
    assert t.fruit_season([winter], [spring]) is None
    assert t.fruit_season([summer], [summer]) is None
    assert t.fruit_season([fall], [summer]) == "fall"
    assert t.fruit_season([summer], []) is None
    assert t.fruit_season(["yearRound"], ["yearRound"]) is None


def test_flowers_need_one_known_non_green_color_and_take_their_season():
    blue = {t.FLOWER_COLOR: ["PATO_0000318"], t.BLOOM: ["Thesaurus.owl#C94733"]}

    assert texts("herb", blue) == {"season": "Look for blue flowers in fall."}
    assert texts("herb", {t.FLOWER_COLOR: ["PATO_0000320"]}) == {}
    assert texts("herb", {t.FLOWER_COLOR: ["PATO_0000318", "PATO_0000323"]}) == {}
    assert texts("herb", {t.FLOWER_COLOR: ["PATO_0000323"], t.BLOOM: ["midSpring", "midSummer"]}) == {
        "sign": "Look for white flowers."
    }


def test_trees_need_showy_flowers_and_conifers_ferns_and_grasses_get_none():
    yellow = {t.FLOWER_COLOR: ["PATO_0000324"], t.BLOOM: ["midSpring"]}

    assert texts("tree", yellow) == {}
    assert texts("tree", {**yellow, SHOWY: ["flowerConspicuousYes"]}) == {
        "season": "Look for yellow flowers in spring."
    }
    assert texts("shrub", yellow) == {"season": "Look for yellow flowers in spring."}
    for kind in t.NO_FLOWERS:
        assert texts(kind, {**yellow, SHOWY: ["flowerConspicuousYes"]}) == {}


def test_size_words_follow_the_tallest_height_per_type_and_vines_get_none():
    assert texts("herb", {t.HEIGHT: ["1"]}) == {"size": "It is a low plant."}
    assert texts("shrub", {t.HEIGHT: ["2", "15"]}) == {"size": "It is a big bush."}
    assert texts("tree", {t.HEIGHT: ["40"]}) == {}
    assert texts("vine", {t.HEIGHT: ["90"]}) == {}
    assert texts("conifer", {t.HEIGHT: ["80"]}) == {"size": "It is a tall evergreen."}


def test_unshowy_fruit_plain_leaves_and_unknown_terms_say_nothing():
    traits = {
        SHOWY: ["fruitSeedConspicuousNo", "fallConspicuousNo"],
        t.FRUIT_COLOR: ["PATO_0000322"],
        t.LEAF_COLOR: ["PATO_0000320"],
        t.BLOOM: ["Thesaurus.owl#C48658"],
    }

    assert texts("tree", traits) == {}
    assert texts("herb", {t.LEAF_COLOR: ["grayGreen"]}) == {"sign": "Look for gray-green leaves."}


def test_brown_fruit_reads_as_seeds_and_at_most_two_features_show():
    traits = {
        SHOWY: ["fruitSeedConspicuousYes", "flowerConspicuousYes"],
        t.FRUIT_COLOR: ["PATO_0000952"],
        t.SEED_BEGIN: ["Thesaurus.owl#C94733"],
        t.SEED_END: ["Thesaurus.owl#C94733"],
        t.FLOWER_COLOR: ["PATO_0000322"],
        t.LEAF_COLOR: ["whiteGrey"],
    }

    assert texts("tree", traits) == {"season": "Look for brown seeds in fall.", "sign": "Look for red flowers."}


def test_candidates_are_usda_backed_and_bucket_by_size_or_season_only():
    found = t.candidates("shrub", {t.HEIGHT: ["20"], SHOWY: ["fallConspicuousYes"]})

    assert found == [
        {"aspect": "size", "text": "It is a big bush.", "support": "usda", "bucket": "big"},
        {"aspect": "season", "text": "Look for bright leaves in fall.", "support": "usda", "bucket": "fall"},
    ]


SPAN = {t.SEED_BEGIN: ["Thesaurus.owl#C94731"], t.SEED_END: ["Thesaurus.owl#C94733"]}


def test_no_generated_hint_ever_uses_a_banned_kid_word():
    colors = list(t.COLORS)
    for kind, flower, fruit, showy in itertools.product(
        NOUNS, colors, colors, [["fallConspicuousYes"], ["fallConspicuousYes", "fruitSeedConspicuousYes"], []]
    ):
        traits = {t.FLOWER_COLOR: [flower], t.FRUIT_COLOR: [fruit], SHOWY: showy, t.HEIGHT: ["0.5"], **SPAN}
        for text in texts(kind, traits).values():
            assert not any(word in text.lower() for word in BANNED), text
