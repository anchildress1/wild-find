import io
import tarfile

import pytest

from wild_find_pipeline.toxicity import MIN_CHARS, flag, plain, resolve, toxic_sentence, usda_ratings

LONG = "Leaves are lobed and green. " * 80


def test_toxic_sentence_finds_the_claim():
    text = "A tall shrub. In fact, this genus of plants is considered poisonous to humans. It grows in shade."
    assert toxic_sentence(text) == "In fact, this genus of plants is considered poisonous to humans."


@pytest.mark.parametrize(
    "text",
    [
        "It often grows beside poison ivy and sassafras.",
        "It is sometimes mistaken for Toxicodendron radicans.",
        "Poison-oak and poison sumac grow nearby.",
    ],
)
def test_toxic_sentence_ignores_other_plants_and_genus_names(text):
    assert toxic_sentence(text) is None


def test_flag_prefers_the_wikipedia_sentence_as_evidence():
    assert flag(LONG + "The berries are toxic.", "severe") == (True, "wikipedia: The berries are toxic.")


def test_flag_uses_usda_when_the_text_is_silent():
    assert flag(LONG, "moderate") == (True, "usda: moderate")


def test_flag_fails_closed_on_a_missing_article_or_a_stub():
    assert flag(None, None) == (True, "no article")
    assert flag("Short.", None) == (True, "stub: 6 chars")


def test_flag_passes_a_long_silent_article():
    assert len(LONG) >= MIN_CHARS
    assert flag(LONG, None) == (False, "")


def test_plain_drops_refs_templates_and_reference_sections():
    wikitext = (
        "'''Oak''' is a tree.<ref>{{cite web|title=Toxic oaks}}</ref> {{Taxobox}}\n"
        "== Description ==\nLobed [[leaf|leaves]].\n"
        "== References ==\n* Smith, ''Poisonous plants'' (2001)\n"
    )
    text = plain(wikitext)
    assert "Oak is a tree." in text
    assert "Lobed leaves." in text
    assert "oxic" not in text and "oison" not in text


def test_resolve_follows_normalization_and_redirects():
    reply = {
        "query": {
            "normalized": [{"from": "quercus nigra", "to": "Quercus nigra"}],
            "redirects": [{"from": "Quercus nigra", "to": "Water oak"}],
            "pages": [
                {"title": "Water oak", "revisions": [{"revid": 7}]},
                {"title": "Nope nope", "missing": True},
            ],
        }
    }
    out = resolve(["quercus nigra", "Nope nope"], reply)
    assert out["quercus nigra"]["title"] == "Water oak"
    assert out["Nope nope"] is None


def _tab(rows: list[list[str]]) -> bytes:
    return ("\n".join("\t".join(r) for r in rows) + "\n").encode()


def test_usda_ratings_keeps_only_moderate_and_severe():
    files = {
        "measurement_or_fact_specific.tab": _tab(
            [
                ["occurrenceID", "measurementType", "measurementValue"],
                [
                    "O1",
                    "http://eol.org/schema/terms/HumanLivestockToxicity",
                    "http://purl.obolibrary.org/obo/PATO_0000396",
                ],
                [
                    "O2",
                    "http://eol.org/schema/terms/HumanLivestockToxicity",
                    "http://purl.obolibrary.org/obo/PATO_0000394",
                ],
                [
                    "O3",
                    "http://eol.org/schema/terms/HumanLivestockToxicity",
                    "http://purl.obolibrary.org/obo/PATO_0000395",
                ],
            ]
        ),
        "occurrence_specific.tab": _tab([["occurrenceID", "taxonID"], ["O1", "T1"], ["O2", "T2"], ["O3", "T3"]]),
        "taxon.tab": _tab(
            [
                ["taxonID", "scientificName"],
                ["T1", "Conium maculatum L."],
                ["T2", "Trifolium repens L."],
                ["T3", "Mahonia bealei (Fortune) Carrière"],
            ]
        ),
    }
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    assert usda_ratings(buffer.getvalue()) == {"Conium maculatum": "severe", "Mahonia bealei": "moderate"}
