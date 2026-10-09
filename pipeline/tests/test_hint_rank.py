from wild_find_pipeline import hint_rank as h


def hint(aspect, support="article", bucket=None, text="x"):
    return {"aspect": aspect, "text": text, "support": support, "bucket": bucket}


def test_unsupported_and_unknown_candidates_are_dropped_not_ranked_last():
    ranked = h.rank([hint("place", support="model"), hint("mood"), hint("size", support="usda")], {})

    assert [c["aspect"] for c in ranked] == ["size"]


def test_a_quoted_article_hint_beats_a_usda_hint_of_the_same_aspect():
    ranked = h.rank([hint("light", "usda"), hint("light", "article")], {})

    assert [c["support"] for c in ranked] == ["article", "usda"]


def test_a_hint_most_plants_share_ranks_below_a_rarer_one_of_the_same_tier():
    batch = [
        [hint("light", "usda", "sun"), hint("ground", "usda", "dry")],
        [hint("light", "usda", "sun")],
        [hint("light", "usda", "sun")],
        [hint("ground", "usda", "wet")],
    ]
    common = h.shares(batch)

    assert common[("light", "sun")] == 0.75
    assert [c["aspect"] for c in h.rank(batch[0], common)] == ["ground", "light"]


def test_tier_orders_aspects_when_support_and_rarity_match():
    ranked = h.rank([hint("range"), hint("nearby"), hint("place")], {})

    assert [c["aspect"] for c in ranked] == ["place", "nearby", "range"]


def test_only_the_top_picks_come_back_and_ties_keep_table_order():
    ranked = h.rank([hint(a) for a in ("light", "ground", "place", "season")], {})

    assert [c["aspect"] for c in ranked] == ["place", "ground", "light"]


def test_free_text_hints_have_no_bucket_and_are_never_counted_as_common():
    assert h.shares([[hint("place")], [hint("place")]]) == {}
