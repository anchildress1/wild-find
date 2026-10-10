import json

import pytest

from wild_find_pipeline import contact_hazards as c

ARTICLE = (
    "Wild parsnip is a biennial herb of open fields. It grows in disturbed ground. "
    "Its sap contains furanocoumarins that cause phytophotodermatitis, blistering the skin in sunlight. "
    "The roots are eaten."
)
PAGE = {"title": "Wild parsnip", "revid": 7, "text": ARTICLE}
QUOTE = "Its sap contains furanocoumarins that cause phytophotodermatitis, blistering the skin in sunlight."
HAZARD = {"verdict": "hazard", "kind": "phototoxic", "evidence": QUOTE, "line": "Its sap can blister skin in sunlight."}


@pytest.fixture
def files(tmp_path, monkeypatch):
    stored, review = tmp_path / "contact_hazards.json", tmp_path / "contact_hazards_review.json"
    monkeypatch.setattr(c, "CONTACT_HAZARDS", stored)
    monkeypatch.setattr(c, "CONTACT_REVIEW", review)
    return stored, review


def _answer(**fields) -> dict:
    return {"text": json.dumps({**HAZARD, **fields}), "cut_off": False}


# ---- prefilter ----


@pytest.mark.parametrize(
    "sentence",
    [
        "It causes urushiol-induced contact dermatitis.",
        "The leaves bear stinging hairs.",
        "The milky sap can irritate the eyes.",
        "Contact with the juice on skin is painful.",
        "Handling it may cause a rash.",
    ],
)
def test_a_contact_sentence_passes_the_prefilter(sentence):
    assert c.touches(sentence)


@pytest.mark.parametrize("sentence", ["The fruit is eaten by birds.", "Its skin-like bark peels.", "Thorny stems."])
def test_other_sentences_do_not(sentence):
    assert not c.touches(sentence)


def test_excerpt_is_the_lead_plus_contact_sentences_or_none_without_one():
    shown = c.excerpt(ARTICLE).splitlines()

    assert shown == ["Wild parsnip is a biennial herb of open fields.", "It grows in disturbed ground.", QUOTE]
    assert c.excerpt("A tall tree of open woods. Birds eat the fruit.") is None


# ---- checks and status ----


def test_a_clean_hazard_passes_and_ships_automatically():
    found = c.issues(**HAZARD, names=["Wild parsnip", "Pastinaca", "sativa"])

    assert found == []
    assert c.status("hazard", found) == "auto"


def test_spines_alone_fail_but_spines_beside_sap_do_not():
    scratch = "Its long spines can puncture the skin."
    assert "spines only" in c.issues("hazard", "irritant_sap", scratch, "It scratches skin.", [])
    assert "spines only" in c.issues("unsure", None, "The glochids irritate skin.", "", [])
    assert "spines only" not in c.issues("hazard", "irritant_sap", "Spines and sap irritate skin.", "Sap hurts.", [])


@pytest.mark.parametrize(
    ("line", "issue"),
    [
        ("", "line missing"),
        (" ".join(["skin"] * 13), "line too long"),
        ("Wild parsnip sap burns skin.", "line names the plant"),
        ("Its sap is not safe for skin.", "line banned word"),
        ("Do not touch its sap.", "line banned word"),
        ("Its sap is toxic to skin.", "line scary word"),
        ("Its poisonous sap burns skin.", "line scary word"),
    ],
)
def test_kid_line_checks(line, issue):
    assert issue in c.issues("hazard", "phototoxic", QUOTE, line, ["Wild parsnip", "Pastinaca", "sativa"])


def test_a_hazard_needs_a_kind_and_a_no_needs_nothing():
    assert "no kind" in c.issues("hazard", None, QUOTE, "Its sap burns skin.", [])
    assert c.issues("no", None, "", "", []) == []


@pytest.mark.parametrize(
    ("verdict", "found", "expected"),
    [
        ("hazard", [], "auto"),
        ("hazard", ["line too long"], "review"),
        ("unsure", [], "review"),
        ("no", [], "skip"),
    ],
)
def test_status(verdict, found, expected):
    assert c.status(verdict, found) == expected


def test_parse_drops_the_kind_of_a_non_hazard_and_rejects_off_schema_replies():
    assert c.parse(json.dumps({**HAZARD, "verdict": "unsure"}))["kind"] is None
    for junk in ("not json", "[]", json.dumps({**HAZARD, "verdict": "maybe"}), json.dumps({**HAZARD, "line": 3})):
        with pytest.raises(ValueError):
            c.parse(junk)


# ---- one row ----


def test_entry_for_skips_the_model_without_an_article_or_a_contact_sentence(monkeypatch):
    monkeypatch.setattr(c, "chat", lambda user: pytest.fail("no contact sentence, no model call"))
    quiet = {"title": "White oak", "revid": 2, "text": "A tall tree of open woods. Birds eat the acorns."}

    assert c.entry_for("Quercus alba", None)["reason"] == "no article"
    entry = c.entry_for("Quercus alba", quiet)
    assert (entry["status"], entry["reason"], entry["article"]) == ("skip", "no contact sentence", "White oak")


def test_entry_for_sends_the_excerpt_and_ships_a_clean_quoted_hazard(monkeypatch):
    sent = []
    monkeypatch.setattr(c, "chat", lambda user: sent.append(user) or _answer())

    entry = c.entry_for("Pastinaca sativa", PAGE)

    assert sent[0].startswith("Plant: Pastinaca sativa\n\nArticle sentences:\n") and QUOTE in sent[0]
    assert (entry["status"], entry["kind"], entry["issues"], entry["revid"]) == ("auto", "phototoxic", [], 7)


def test_entry_for_sends_a_made_up_quote_or_a_failed_reply_to_review(monkeypatch):
    monkeypatch.setattr(c, "chat", lambda user: _answer(evidence="Its sap burns everyone who walks by."))
    entry = c.entry_for("Pastinaca sativa", PAGE)
    assert (entry["status"], entry["issues"]) == ("review", [c.QUOTE_MISSING])

    def fail(user):
        raise ValueError("gemma4:26b: reply ended 'length' with no usable content")

    monkeypatch.setattr(c, "chat", fail)
    entry = c.entry_for("Pastinaca sativa", PAGE)
    assert (entry["status"], entry["issues"]) == ("review", [c.REPLY_FAILED]) and "length" in entry["reason"]


def test_chat_sends_the_contact_schema_with_thinking_off(monkeypatch):
    sent = []

    class Reply:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def read(self, *_):
            return json.dumps({"message": {"content": "{}"}, "done_reason": "stop", "prompt_eval_count": 1}).encode()

    monkeypatch.setattr(
        c.gemma.urllib.request, "urlopen", lambda request, timeout=None: sent.append(request) or Reply()
    )

    assert c.chat("Plant: x") == {"text": "{}", "cut_off": False}
    body = json.loads(sent[0].data)
    assert body["format"] == c.SCHEMA and body["think"] is False and body["messages"][0]["content"] == c.SYSTEM
    roles = [m["role"] for m in body["messages"]]
    assert roles == ["system", "user", "assistant", "user", "assistant", "user"]
    assert json.loads(body["messages"][4]["content"])["verdict"] == "no"


# ---- what ships, and the review file ----


def test_listed_ships_auto_rows_and_owner_approved_review_rows_with_only_clean_lines():
    data = {
        "done": True,
        "species": {
            "A a": {"status": "auto", "line": "Its sap burns skin.", "issues": []},
            "B b": {"status": "review", "line": "Too many words here.", "issues": ["line too long"]},
            "C c": {"status": "review", "line": "Its hairs sting.", "issues": ["evidence not in article"]},
            "D d": {"status": "review", "line": "x", "issues": []},
            "E e": {"status": "skip", "line": "", "issues": []},
        },
    }
    review = {"B b": {"owner": True}, "C c": {"owner": True}, "D d": {"owner": False}}

    assert c.listed(data, review) == {"A a": "Its sap burns skin.", "B b": None, "C c": "Its hairs sting."}
    with pytest.raises(ValueError, match="make contact-hazards"):
        c.listed({"done": False, "species": {}}, {})


def test_an_owner_veto_drops_an_auto_row():
    data = {
        "done": True,
        "species": {
            "A a": {"status": "auto", "line": "Its sap burns skin.", "issues": []},
            "B b": {"status": "auto", "line": "Its hairs sting.", "issues": []},
        },
    }

    assert c.listed(data, {"A a": {"triage": "reject", "note": "lumber", "owner": False}}) == {
        "B b": "Its hairs sting."
    }


def test_listed_reads_the_committed_files(files):
    stored, review = files
    stored.write_text(json.dumps({"done": True, "species": {"A a": {"status": "auto", "line": "x", "issues": []}}}))
    review.write_text("{}")

    assert c.listed() == {"A a": "x"}


def test_review_entries_keep_decisions_and_drop_rows_no_longer_in_review():
    species = {"A a": {"status": "review"}, "B b": {"status": "review"}, "C c": {"status": "skip"}}
    old = {"A a": {"triage": "approve", "note": "real", "owner": True}, "C c": {"triage": "reject", "owner": False}}

    assert c.review_entries(species, old) == {
        "A a": {"triage": "approve", "note": "real", "owner": True},
        "B b": {"triage": None, "note": "", "owner": None},
    }


def test_review_entries_keep_an_owner_veto_on_an_auto_row():
    species = {"A a": {"status": "auto"}, "B b": {"status": "auto"}}
    old = {"A a": {"triage": "reject", "note": "sawdust", "owner": False}, "B b": {"triage": "reject", "owner": None}}

    assert c.review_entries(species, old) == {"A a": {"triage": "reject", "note": "sawdust", "owner": False}}


# ---- the run itself ----


def _run(tmp_path, monkeypatch, rows: list[str]) -> list[str]:
    flags = tmp_path / "toxicity.json"
    flags.write_text(json.dumps({"species": {r: {"toxic": False} for r in rows}}))
    monkeypatch.setattr(c, "TOXICITY", flags)
    monkeypatch.setattr(c.gemma, "digest", lambda: "48eb98ec778c")
    asked = []
    monkeypatch.setattr(c, "articles", lambda names: asked.extend(names) or {n: PAGE for n in names})
    monkeypatch.setattr(c, "chat", lambda user: _answer(line="Wild parsnip sap burns skin."))
    monkeypatch.setattr(c.sys, "argv", ["contact_hazards"])
    monkeypatch.setattr(c, "CHECKPOINT", 1)
    return asked


def test_main_resumes_a_checkpoint_and_writes_the_review_file_keeping_decisions(tmp_path, monkeypatch, files, capsys):
    stored, review = files
    asked = _run(tmp_path, monkeypatch, ["A a", "B b"])
    done = {"article": "A", "revid": 1, **HAZARD, "issues": [], "status": "auto"}
    stored.write_text(json.dumps({"done": False, "digest": "48eb98ec778c", "species": {"A a": done}}))
    review.write_text(json.dumps({"B b": {"triage": "approve", "note": "ok", "owner": True}}))

    assert c.main() == 0

    assert asked == ["B b"]
    saved = json.loads(stored.read_text())
    assert saved["done"] is True and saved["digest"] == "48eb98ec778c" and saved["model"] == c.MODEL
    assert saved["species"]["B b"]["issues"] == ["line names the plant"]
    assert json.loads(review.read_text()) == {"B b": {"triage": "approve", "note": "ok", "owner": True}}
    out = capsys.readouterr().out
    assert "prefilter: 1 of 1 rows" in out and "'auto': 1, 'review': 1" in out


def test_main_refuses_a_checkpoint_from_another_model_build(tmp_path, monkeypatch, files):
    stored, _ = files
    _run(tmp_path, monkeypatch, ["A a"])
    stored.write_text(json.dumps({"done": False, "digest": "000000000000", "species": {}}))

    with pytest.raises(ValueError, match="delete it to rebuild"):
        c.main()


def test_recheck_reapplies_checks_without_the_model_and_keeps_the_quote_result(monkeypatch, files):
    stored, review = files
    monkeypatch.setattr(c, "chat", lambda user: pytest.fail("recheck never calls the model"))
    monkeypatch.setattr(c.sys, "argv", ["contact_hazards", "--recheck"])
    species = {
        "A a": {"article": "A", **HAZARD, "line": "Its toxic sap burns skin.", "issues": [], "status": "auto"},
        "B b": {"article": "B", **HAZARD, "issues": [c.QUOTE_MISSING], "status": "review"},
        "C c": {"article": None, "verdict": "no", "issues": [], "status": "skip", "reason": "no article"},
    }
    stored.write_text(json.dumps({"done": True, "digest": "48eb98ec778c", "species": species}))

    assert c.main() == 0

    saved = json.loads(stored.read_text())["species"]
    assert (saved["A a"]["status"], saved["A a"]["issues"]) == ("review", ["line scary word"])
    assert (saved["B b"]["status"], saved["B b"]["issues"]) == ("review", [c.QUOTE_MISSING])
    assert set(json.loads(review.read_text())) == {"A a", "B b"}

    stored.write_text(json.dumps({"done": False, "digest": "48eb98ec778c", "species": species}))
    with pytest.raises(ValueError, match="unfinished checkpoint"):
        c.main()


def test_digest_is_the_short_digest_of_the_pulled_model_and_raises_when_missing(monkeypatch):
    tags = {
        "models": [{"name": "gemma4:12b", "digest": "c7597fc90b86aa"}, {"name": c.MODEL, "digest": "48eb98ec778cff"}]
    }

    class Reply:
        def __init__(self, body):
            self.body = body

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def read(self, *_):
            return json.dumps(self.body).encode()

    monkeypatch.setattr(c.gemma.urllib.request, "urlopen", lambda url, timeout=None: Reply(tags))
    assert c.gemma.digest() == "48eb98ec778c"

    tags["models"].pop()
    with pytest.raises(ValueError, match="not pulled"):
        c.gemma.digest()
