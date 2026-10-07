import pytest

from wild_find_pipeline.candidates import PROMPT, merge, parse_words
from wild_find_pipeline.paths import REPO


def test_parse_words_reads_a_bare_array():
    assert parse_words('[\n  "oak",\n  "fern"\n]') == ["oak", "fern"]


def test_parse_words_strips_a_json_fence():
    assert parse_words('```json\n["oak"]\n```') == ["oak"]


def test_parse_words_lowercases_and_collapses_spaces_and_drops_blanks():
    assert parse_words('["  Black  Eyed Susan ", "", " "]') == ["black eyed susan"]


@pytest.mark.parametrize("reply", ["oak, fern", '{"words": ["oak"]}', '["oak", 3]'])
def test_parse_words_rejects_anything_but_an_array_of_strings(reply):
    with pytest.raises(ValueError):
        parse_words(reply)


def test_merge_is_a_sorted_union():
    assert merge([["oak", "fern"], ["fern", "cattail"]]) == ["cattail", "fern", "oak"]


def test_prompt_is_the_prd_candidate_prompt():
    prd = (REPO / "docs/PRD.md").read_text()
    block = prd.split("**Candidate prompt**", 1)[1].split("```", 2)[1]

    assert block.strip() == PROMPT
