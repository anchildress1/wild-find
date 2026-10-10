import json

import pytest

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


# ---- the run itself: the model call, one row, ranking, saving, resuming ----


class _Reply:
    def __init__(self, body: dict):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, *_):
        return json.dumps(self.body).encode()


def _ollama(monkeypatch, content: str, reason: str = "stop", prompt_tokens: int = 100) -> list[dict]:
    sent = []

    def urlopen(request, timeout=None):
        sent.append(json.loads(request.data))
        return _Reply({"message": {"content": content}, "done_reason": reason, "prompt_eval_count": prompt_tokens})

    monkeypatch.setattr(h.urllib.request, "urlopen", urlopen)
    return sent


def test_chat_sends_the_system_turn_one_example_and_thinking_off_and_returns_the_reply(monkeypatch):
    sent = _ollama(monkeypatch, '{"place": []}')

    reply = h.chat("Plant: Quercus nigra")

    body = sent[0]
    assert body["model"] == h.MODEL and body["think"] is False and body["stream"] is False
    assert [m["role"] for m in body["messages"]] == ["system", "user", "assistant", "user"]
    assert body["options"]["num_ctx"] == h.NUM_CTX and body["options"]["seed"] == h.SAMPLER["seed"]
    assert reply == {"text": '{"place": []}', "cut_off": False}


def test_chat_flags_a_prompt_at_the_context_limit_and_raises_on_a_reply_that_did_not_stop(monkeypatch):
    _ollama(monkeypatch, "{}", prompt_tokens=h.NUM_CTX)
    assert h.chat("x")["cut_off"] is True

    _ollama(monkeypatch, "{", reason="length")
    with pytest.raises(ValueError, match="length"):
        h.chat("x")
    _ollama(monkeypatch, "  ", reason="stop")
    with pytest.raises(ValueError, match="no usable content"):
        h.chat("x")


PAGE = {"title": "Water oak", "revid": 7, "text": "It grows in moist woods. It also turns up along fence lines."}


def _reply(**kinds):
    return json.dumps({a: kinds.get(a, []) for a in h.ASPECTS})


def test_hints_for_keeps_every_model_hint_with_its_checks_and_fills_an_empty_kind_from_usda(monkeypatch):
    reply = _reply(
        place=[{"hint": "Look in moist woods.", "evidence": "It grows in moist woods."}],
        edges=[
            {"hint": "Look along fence lines.", "evidence": "It also turns up along fence lines."},
            {"hint": "Look along roadsides.", "evidence": "It also turns up along fence lines."},
        ],
    )
    monkeypatch.setattr(h, "chat", lambda user: {"text": reply, "cut_off": False})
    facts = {"Quercus nigra": {"ShadeTolerance": ["PATO_0002394"], "TO_0000207": ["80"]}}

    entry = h.hints_for("Quercus nigra", PAGE, [], "tree", facts)

    assert (entry["article"], entry["revid"], entry["failed"], entry["cut_off"]) == ("Water oak", 7, None, False)
    by_text = {x["text"]: x for x in entry["hints"]}
    assert by_text["Look in moist woods."]["issues"] == []
    assert by_text["Look along roadsides."]["issues"] == ["road", "repeats an earlier quote"]
    assert by_text["Look in open, sunny spots."]["source"] == "usda"
    assert any(x["source"] == "trait" and x["aspect"] == "size" for x in entry["hints"])


def test_hints_for_records_a_failed_reply_and_still_ships_what_usda_knows(monkeypatch):
    def fail(user):
        raise ValueError("gemma4:26b: reply ended 'length' with no usable content")

    monkeypatch.setattr(h, "chat", fail)
    facts = {"Quercus nigra": {"AnaerobicSoilTolerance": ["260413007"]}}

    entry = h.hints_for("Quercus nigra", PAGE, [], None, facts)

    assert "length" in entry["failed"]
    assert [x["text"] for x in entry["hints"]] == ["Look on ground that drains well, not soggy."]


def test_hints_for_a_row_without_an_article_skips_the_model(monkeypatch):
    monkeypatch.setattr(h, "chat", lambda user: pytest.fail("no article, no model call"))
    facts = {"Quercus nigra": {"ShadeTolerance": ["PATO_0002393"]}}

    entry = h.hints_for("Quercus nigra", None, [], None, facts)

    assert entry["article"] is None and [x["source"] for x in entry["hints"]] == ["usda"]


def test_candidate_calls_a_model_hint_article_backed_and_everything_else_usda_backed():
    model = {"aspect": "place", "text": "x", "source": "model", "bucket": None}
    trait = {"aspect": "season", "text": "y", "source": "trait", "bucket": "fall"}

    assert h.candidate(model)["support"] == "article"
    assert h.candidate(trait) == {"aspect": "season", "text": "y", "support": "usda", "bucket": "fall"}


def test_playable_skips_toxic_and_hazard_rows_and_sorts(tmp_path, monkeypatch):
    flags = tmp_path / "toxicity.json"
    flags.write_text(
        json.dumps(
            {
                "species": {
                    "Quercus nigra": {"toxic": False},
                    "Abies alba": {"toxic": False},
                    "Conium maculatum": {"toxic": True},
                    "Toxicodendron radicans": {"toxic": False},
                }
            }
        )
    )
    monkeypatch.setattr(h, "TOXICITY", flags)

    assert h.playable() == ["Abies alba", "Quercus nigra"]


def _stored(tmp_path, monkeypatch, species: dict, done: bool = True):
    path = tmp_path / "hints.json"
    monkeypatch.setattr(h, "HINTS", path)
    h.save(species, done=done)
    return path


def test_recheck_flags_stored_hints_that_now_fail_and_reranks_without_the_model(tmp_path, monkeypatch):
    monkeypatch.setattr(h, "chat", lambda user: pytest.fail("recheck never calls the model"))
    entry = {
        "hints": [
            {"aspect": "place", "text": "Look on rocky cliffs.", "evidence": "q", "source": "model", "issues": []},
            {"aspect": "place", "text": "Look in woods.", "evidence": "q", "source": "model", "issues": []},
            {"aspect": "size", "text": "It is a tall tree.", "source": "trait", "issues": [], "bucket": "big"},
        ]
    }
    path = _stored(tmp_path, monkeypatch, {"A a": entry}, done=True)

    assert h.recheck() == 0

    saved = json.loads(path.read_text())
    assert saved["done"] is True
    cliff, woods, size = saved["species"]["A a"]["hints"]
    assert cliff["issues"] == ["height"] and cliff["score"] is None
    # A size bucket every plant in the batch shares tells a kid nothing, so rarity scores it out.
    assert woods["score"] == 3.0 and size["score"] is None


def test_recheck_flags_a_repeated_hint_but_keeps_the_first_and_refuses_an_unfinished_file(tmp_path, monkeypatch):
    twice = [
        {"aspect": "place", "text": "Look in woods.", "evidence": "a", "source": "model", "issues": []},
        {"aspect": "ground", "text": "look in woods.", "evidence": "b", "source": "model", "issues": []},
    ]
    path = _stored(tmp_path, monkeypatch, {"A a": {"hints": twice}}, done=True)

    assert h.recheck() == 0

    first, second = json.loads(path.read_text())["species"]["A a"]["hints"]
    assert first["issues"] == [] and second["issues"] == ["repeats an earlier hint"]
    _stored(tmp_path, monkeypatch, {"A a": {"hints": twice}}, done=False)
    with pytest.raises(ValueError, match="unfinished"):
        h.recheck()


def test_hints_for_flags_the_same_hint_said_twice_even_under_another_kind(monkeypatch):
    reply = _reply(
        place=[{"hint": "Look in moist woods.", "evidence": "It grows in moist woods."}],
        ground=[{"hint": "Look in moist woods.", "evidence": "It also turns up along fence lines."}],
    )
    monkeypatch.setattr(h, "chat", lambda user: {"text": reply, "cut_off": False})

    entry = h.hints_for("Quercus nigra", PAGE, [], None, {})

    place, ground = [x for x in entry["hints"] if x["source"] == "model"]
    assert place["issues"] == [] and ground["issues"] == ["repeats an earlier hint"]


def test_main_resumes_from_a_partial_file_and_finishes_with_ranked_hints(tmp_path, monkeypatch, capsys):
    flags = tmp_path / "toxicity.json"
    flags.write_text(json.dumps({"species": {"A a": {"toxic": False}, "B b": {"toxic": False}}}))
    types, synonyms = tmp_path / "types.json", tmp_path / "synonyms.json"
    types.write_text(json.dumps({"species": {"A a": {"type": "tree"}, "B b": {"type": None}}}))
    synonyms.write_text(json.dumps({"species": {"A a": [], "B b": ["B c"]}}))
    done = {"article": "A", "revid": 1, "cut_off": False, "failed": None, "hints": []}
    path = _stored(tmp_path, monkeypatch, {"A a": done}, done=False)
    for name, value in (("TOXICITY", flags), ("PLANT_TYPES", types), ("SYNONYMS", synonyms)):
        monkeypatch.setattr(h, name, value)
    asked = []
    monkeypatch.setattr(h, "articles", lambda names: asked.extend(names) or {n: PAGE for n in names})
    monkeypatch.setattr(h.usda, "archive", lambda: b"")
    monkeypatch.setattr(h, "usda_traits", lambda archive, wanted: {})
    monkeypatch.setattr(
        h,
        "chat",
        lambda user: {
            "text": _reply(place=[{"hint": "Look in moist woods.", "evidence": PAGE["text"][:25]}]),
            "cut_off": False,
        },
    )
    monkeypatch.setattr(h.sys, "argv", ["hints"])
    monkeypatch.setattr(h, "CHECKPOINT", 1)

    assert h.main() == 0

    assert asked == ["B b"]
    saved = json.loads(path.read_text())
    assert saved["done"] is True and set(saved["species"]) == {"A a", "B b"}
    assert saved["species"]["B b"]["hints"][0]["score"] == 3.0
    assert "0 model replies failed" in capsys.readouterr().out


def test_main_rebuilds_a_finished_file_from_scratch(tmp_path, monkeypatch):
    flags = tmp_path / "toxicity.json"
    flags.write_text(json.dumps({"species": {"A a": {"toxic": False}}}))
    types, synonyms = tmp_path / "types.json", tmp_path / "synonyms.json"
    types.write_text(json.dumps({"species": {"A a": {"type": "tree"}}}))
    synonyms.write_text(json.dumps({"species": {"A a": []}}))
    old = {"article": "old", "revid": 1, "cut_off": False, "failed": None, "hints": []}
    path = _stored(tmp_path, monkeypatch, {"A a": old}, done=True)
    for name, value in (("TOXICITY", flags), ("PLANT_TYPES", types), ("SYNONYMS", synonyms)):
        monkeypatch.setattr(h, name, value)
    monkeypatch.setattr(h, "articles", lambda names: {n: PAGE for n in names})
    monkeypatch.setattr(h.usda, "archive", lambda: b"")
    monkeypatch.setattr(h, "usda_traits", lambda archive, wanted: {})
    monkeypatch.setattr(h, "chat", lambda user: {"text": _reply(), "cut_off": False})
    monkeypatch.setattr(h.sys, "argv", ["hints"])

    assert h.main() == 0

    assert json.loads(path.read_text())["species"]["A a"]["article"] == "Water oak"


def test_main_with_recheck_only_reapplies_the_checks(tmp_path, monkeypatch):
    _stored(tmp_path, monkeypatch, {"A a": {"hints": []}})
    monkeypatch.setattr(h.sys, "argv", ["hints", "--recheck"])
    monkeypatch.setattr(h, "playable", lambda: pytest.fail("recheck does not rebuild the row list"))

    assert h.main() == 0
