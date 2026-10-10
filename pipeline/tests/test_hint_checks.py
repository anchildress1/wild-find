import re
from pathlib import Path

import pytest

from wild_find_pipeline import hints as h

# The owner's graded spot-check: each item's hint, quote, and y / n / o verdict.
GRADED = Path(__file__).resolve().parents[2] / "docs/results/day-4/hints_spotcheck.md"
ITEM = re.compile(
    r"## (\d+)\. \*.+?\* \((\w+), model\)\n\n- \*\*Hint:\*\* (.*)\n- \*\*Quote:\*\* (.*)\n.*\n- \*\*Verdict:\*\* (\w+)"
)
# The check that must reject each item. Eight `y` items break an S72 rule by design (owner decision, Oct 10): 1, 19,
# 31, 42 name a biome, 18 and 36 soil a kid can't see, 17 and 37 only habitats most plants share.
RULE = {
    1: "biome",
    4: "garden care",
    7: "garden care",
    17: h.GENERIC,
    18: "unseen soil",
    19: "biome",
    20: "garden care",
    23: h.GENERIC,
    26: "hedged",
    27: "biome",
    28: "height",
    31: "biome",
    32: "named region",
    33: "drops the wet",
    36: "unseen soil",
    37: h.GENERIC,
    39: "unseen soil",
    42: "biome",
    46: "unseen soil",
    49: "biome",
    50: "biome",
}
SPOTCHECK = [
    (int(n), verdict, aspect, hint, quote, RULE.get(int(n)))
    for n, aspect, hint, quote, verdict in ITEM.findall(GRADED.read_text())
]
# Woods and grass passed the cut in the Oct 9 run (34% and 19% of playable rows).
COMMON = {"woods", "grass"}


def fails(aspect, hint, quote):
    found = h.text_issues(aspect, hint, quote)
    if (groups := h.habitats(hint)) is not None and groups <= COMMON:
        found.append(h.GENERIC)
    return found


@pytest.mark.parametrize(("item", "verdict", "aspect", "hint", "quote", "rule"), SPOTCHECK)
def test_every_graded_miss_is_rejected_by_its_rule_and_every_other_good_hint_passes(
    item, verdict, aspect, hint, quote, rule
):
    found = fails(aspect, hint, quote)

    if rule is None:
        assert verdict == "y" and found == []
    else:
        assert rule in found


def test_the_spotcheck_counts_match_the_grades():
    bad = [i for i in SPOTCHECK if i[1] != "y"]
    pinned = [i for i in SPOTCHECK if i[1] == "y" and i[5]]

    assert len(SPOTCHECK) == 50 and len(bad) == 13 and all(i[5] for i in bad)
    assert [i[0] for i in pinned] == [1, 17, 18, 19, 31, 36, 37, 42]


def test_care_quotes_fail_unless_the_plant_escaped_cultivation():
    assert "garden care" in h.text_issues("place", "Look in shady spots.", "It is grown in cultivation in shade.")
    assert "garden care" not in h.text_issues("place", "Look in fields.", "It escaped cultivation into fields.")
    assert "garden care" in h.text_issues("ground", "Keep it damp.", "It grows in damp soil.")


def test_a_hedge_fails_only_when_the_hint_comes_after_it():
    quote = "It grows on dry slopes, but occasionally in shady ravines."
    assert h.hedged("Look on dry slopes.", quote) is False
    assert h.hedged("Look in shady spots.", quote) is True
    # "Also" names a second main habitat, not a rare one.
    assert h.hedged("Look along fence lines.", "It grows in woods and also along fence lines.") is False
    assert h.hedged("It sometimes grows in shade.", "It grows in shade.") is True


def test_biome_words_fail_in_every_spelling():
    for text in ("Look in oak savannas.", "Look in a savannah.", "Look in montane scrub.", "Look on heathland."):
        assert "biome" in h.text_issues("place", text, "q")


def test_a_place_name_fails_but_a_plant_named_for_one_passes():
    quote = "It grows in shade."
    assert "named region" in h.text_issues("place", "Look in Texas.", quote)
    assert "named region" not in h.text_issues("nearby", "It grows with Virginia creeper.", quote)
    assert "named region" in h.text_issues("place", "Look in shade.", "It grows (for example in Ohio) in shade.")


def test_soil_words_fail_on_ground_hints_but_deep_shade_is_light():
    assert "unseen soil" in h.text_issues("ground", "It likes lime.", "It likes lime.")
    assert "unseen soil" not in h.text_issues("light", "It grows in deep shade.", "It grows in deep shade.")
    assert "unseen soil" not in h.text_issues("ground", "Look on wet, sandy ground.", "It grows on wet sand.")


def test_a_sand_hint_may_keep_the_wet_the_quote_leads_with():
    quote = "It grows in damp, often sandy soil."
    assert h.dropped_wet("Look on sandy ground.", quote) is True
    assert h.dropped_wet("Look on damp, sandy ground.", quote) is False
    assert h.dropped_wet("Look on sandy ground.", "It grows in sandy soil that is sometimes damp.") is False


def test_habitats_names_groups_only_when_they_are_all_the_hint_says():
    assert h.habitats("You can find it in woodlands or fields.") == {"woods", "grass"}
    assert h.habitats("Look in damp woods.") is None
    assert h.habitats("It likes sun.") is None


def _model(text, issues=()):
    return {"aspect": "place", "text": text, "evidence": "q", "source": "model", "issues": list(issues)}


def test_flag_generic_cuts_habitats_shared_past_the_share_and_reapplies_cleanly():
    # Each R row has a narrower place hint too, so its generic one isn't the last-resort fallback.
    species = {f"R {i}": {"hints": [_model("Look in woods."), _model("Look in damp hollows.")]} for i in range(3)}
    species["R 3"] = {"hints": [_model("Look in meadows.")], "article": None}
    species.update({f"S {i}": {"hints": [_model("Look in damp spots.")]} for i in range(6)})

    shares = h.flag_generic(species)

    assert shares["woods"] == 0.3 and shares["grass"] == 0.1
    assert species["R 0"]["hints"][0]["issues"] == [h.GENERIC]
    assert species["R 3"]["hints"][0]["issues"] == []
    # A rerun on a batch where woods turned rare drops the stale flag, and the flag never counts itself out.
    del species["R 1"], species["R 2"]
    species.update({f"T {i}": {"hints": [_model("Look in damp spots.")]} for i in range(4)})
    h.flag_generic(species)
    assert species["R 0"]["hints"][0]["issues"] == []


def test_a_generic_place_hint_ships_when_the_plant_has_no_other_place_hint():
    species = {f"R {i}": {"hints": [_model("Look in woods.")]} for i in range(5)}
    species["R 0"]["hints"].append(_model("Look in damp hollows."))

    h.flag_generic(species)

    assert species["R 0"]["hints"][0]["issues"] == [h.GENERIC]
    assert species["R 1"]["hints"][0]["issues"] == []
