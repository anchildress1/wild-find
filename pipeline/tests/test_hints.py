import json

from wild_find_pipeline import hints as h

ARTICLE = "It grows in moist woods. It also turns up along fence lines. Birds eat the fruit."


def test_a_quote_must_appear_in_the_article_however_it_wraps():
    assert h.issues("place", "Look in woods.", "It grows in  moist\nwoods.", ARTICLE, []) == []
    assert "evidence not in article" in h.issues("place", "Look in woods.", "It grows in dry woods.", ARTICLE, [])
    assert "evidence not in article" in h.issues("place", "Look in woods.", "", ARTICLE, [])


def test_names_banned_words_and_long_hints_fail():
    quote = "It grows in moist woods."
    assert "names the plant" in h.issues("place", "Look for beech trees.", quote, ARTICLE, ["Beech"])
    assert "banned word" in h.issues("place", "It is safe in woods.", quote, ARTICLE, [])
    assert "banned word" in h.issues("place", "Do not pick it in woods.", quote, ARTICLE, [])
    assert "too long" in h.issues("place", " ".join(["woods"] * 21), quote, ARTICLE, [])


def test_hints_that_send_a_kid_to_water_or_traffic_fail():
    quote = "It grows in moist woods."
    for text in ("Look near stream banks.", "It lives in swampy areas.", "Look in wetlands."):
        assert "water" in h.issues("place", text, quote, ARTICLE, [])
    for text in ("Look along roadsides.", "You might see it along interstate highways."):
        assert "road" in h.issues("edges", text, quote, ARTICLE, [])


def test_hints_that_send_a_kid_to_a_drop_or_open_water_fail():
    quote = "It grows in moist woods."
    for text in ("Look on rocky cliffs.", "It grows at bluff margins.", "Look on canyon walls."):
        assert "height" in h.issues("place", text, quote, ARTICLE, [])
    for text in ("Look along sand dune shorelines.", "It grows in quiet freshwaters.", "Look on flood-prone lands."):
        assert "water" in h.issues("place", text, quote, ARTICLE, [])


def test_a_place_naming_only_a_region_fails_but_other_kinds_may_name_one():
    quote = "It grows in moist woods."
    assert "region" in h.issues("place", "Look in the eastern United States.", quote, ARTICLE, [])
    assert "region" not in h.issues("light", "It grows in the eastern sun.", quote, ARTICLE, [])


def test_parse_keeps_two_per_kind_and_survives_junk():
    reply = json.dumps(
        {
            "place": [
                {"hint": "A.", "evidence": "x"},
                {"hint": "B.", "evidence": "y"},
                {"hint": "C.", "evidence": "z"},
            ],
            "light": [{"hint": "  ", "evidence": "x"}, "junk", {"hint": 3}],
            "ground": "no",
        }
    )

    parsed = h.parse(reply)

    assert parsed["place"] == [("A.", "x"), ("B.", "y")]
    assert parsed["light"] == [] and parsed["ground"] == [] and parsed["edges"] == []
    assert h.parse("not json") == {a: [] for a in h.ASPECTS}


def test_excerpt_leads_with_the_article_start_then_keeps_setting_sentences():
    text = (
        "A tall plant of the aster family here. It has a long history of use. "
        "Nothing else to say about it. It grows in moist woods."
    )

    shown = h.excerpt(text).splitlines()

    assert shown[:2] == ["A tall plant of the aster family here.", "It has a long history of use."]
    assert "It grows in moist woods." in shown
    assert "Nothing else to say about it." not in shown


def test_usda_fills_only_a_clear_single_rating_under_the_first_name_that_has_one():
    facts = {"Alias x": {"ShadeTolerance": ["PATO_0002393"]}, "Row x": {"ShadeTolerance": ["PATO_0002393", "other"]}}

    fill = h.usda_fill("light", ["Row x", "Alias x"], facts)

    assert fill["text"] == "It can grow in shade, like under trees." and fill["bucket"] == "shade"
    assert h.usda_fill("light", ["Row x"], facts) is None
    assert h.usda_fill("place", ["Alias x"], facts) is None


def test_rank_all_scores_only_clean_picked_hints():
    species = {
        "A a": {
            "hints": [
                {"aspect": "place", "text": "Woods.", "evidence": "q", "source": "model", "issues": []},
                {"aspect": "place", "text": "Water.", "evidence": "q", "source": "model", "issues": ["water"]},
            ]
        }
    }

    h.rank_all(species)

    clean, dropped = species["A a"]["hints"]
    assert clean["score"] == 3.0
    assert dropped["score"] is None
