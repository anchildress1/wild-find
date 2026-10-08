import json
import urllib.parse

import pytest

from wild_find_pipeline import synonyms
from wild_find_pipeline.synonyms import aliases, backbone_names, binomial, cached_get, epithet_stem
from wild_find_pipeline.toxicity import GBIF


def fake_gbif(matches: dict[str, dict], usages: dict[int, dict], pages: dict[int, list[list[dict]]]):
    """A fetch that answers match, usage, and paginated synonym URLs from tables, failing on anything else."""

    def fetch(url: str) -> dict:
        parsed = urllib.parse.urlparse(url)
        path = parsed.path.removeprefix(urllib.parse.urlparse(GBIF).path)
        query = urllib.parse.parse_qs(parsed.query)
        if path == "/species/match":
            assert query["kingdom"] == ["Plantae"] and query["strict"] == ["true"]
            return matches[query["name"][0]]
        parts = path.split("/")
        if len(parts) == 3:
            return usages[int(parts[2])]
        assert parts[3] == "synonyms"
        chunks = pages[int(parts[2])]
        index = int(query["offset"][0]) // int(query["limit"][0])
        return {"results": chunks[index], "endOfRecords": index == len(chunks) - 1}

    return fetch


def usage(name: str, rank: str = "SPECIES") -> dict:
    return {"canonicalName": name, "rank": rank}


def synonym_of(key: int) -> dict:
    return {"matchType": "EXACT", "rank": "SPECIES", "usageKey": key + 100, "acceptedUsageKey": key}


def accepted(key: int) -> dict:
    return {"matchType": "EXACT", "rank": "SPECIES", "usageKey": key}


def test_backbone_names_follows_a_synonym_to_its_accepted_name_and_pages_through_synonyms(monkeypatch):
    monkeypatch.setattr(synonyms, "PAGE", 2)
    fetch = fake_gbif(
        {
            "Berberis bealei": synonym_of(1),
            "Mahonia bealei": accepted(1),
            "Ilex bealei": synonym_of(1),
            "Odostemon bealeus": synonym_of(1),
        },
        {1: usage("Mahonia bealei")},
        {
            1: [
                [usage("Berberis bealei"), usage("Mahonia japonica bealei", "VARIETY")],
                [usage("Ilex bealei"), usage("Mahonia")],
                [usage("Odostemon bealeus")],
            ]
        },
    )

    assert backbone_names("Berberis bealei", fetch) == ["Ilex bealei", "Mahonia bealei"]


def test_backbone_names_drops_homonyms_and_other_epithets():
    fetch = fake_gbif(
        # GBIF lists Quercus lyrata Spreng. under valley oak, but the name alone resolves to overcup oak.
        {"Quercus lobata": accepted(1), "Quercus lyrata": accepted(2), "Quercus lobata-alba": synonym_of(1)},
        {1: usage("Quercus lobata")},
        {1: [[usage("Quercus lyrata"), usage("Quercus hindsii"), usage("Quercus lobata-alba")]]},
    )

    assert backbone_names("Quercus lobata", fetch) == []


def test_epithet_stem_ignores_gender_endings():
    assert epithet_stem("Nephroia carolina") == epithet_stem("Cocculus carolinus")
    assert epithet_stem("Asarum arifolium") == epithet_stem("Hexastylis arifolia")
    assert epithet_stem("Quercus lyrata") != epithet_stem("Quercus lobata")


@pytest.mark.parametrize(
    "match",
    [
        {"matchType": "FUZZY", "rank": "SPECIES", "usageKey": 1},
        {"matchType": "EXACT", "rank": "GENUS", "usageKey": 1},
        {"matchType": "NONE"},
    ],
)
def test_backbone_names_refuses_anything_but_an_exact_species_match(match):
    assert backbone_names("Quercus nigrra", fake_gbif({"Quercus nigrra": match}, {}, {})) is None


def test_binomial_keeps_species_canonical_names_only():
    assert binomial(usage("Quercus nigra")) == "Quercus nigra"
    assert binomial(usage("Quercus nigra nigra", "SUBSPECIES")) is None
    assert binomial(usage("Quercus", "GENUS")) is None
    assert binomial({"rank": "SPECIES"}) is None


def test_aliases_drop_ambiguous_names_and_other_rows_table_names():
    found = {
        "Berberis bealei": ["Berberis bealei", "Mahonia bealei"],
        "Carya alba": ["Carya alba", "Carya tomentosa", "Hicoria alba"],
        "Carya tomentosa": ["Carya tomentosa", "Hicoria alba"],
        "Quercus nigra": [],
    }

    species, ambiguous, taken = aliases(found)

    assert species == {
        "Berberis bealei": ["Mahonia bealei"],
        "Carya alba": [],
        "Carya tomentosa": [],
        "Quercus nigra": [],
    }
    assert ambiguous == ["Hicoria alba"]
    assert taken == ["Carya tomentosa"]


def test_cached_get_reads_the_disk_cache_before_the_network(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(synonyms, "get", lambda url: calls.append(url) or {"n": len(calls)})

    assert cached_get("https://x/1", tmp_path) == {"n": 1}
    assert cached_get("https://x/1", tmp_path) == {"n": 1}
    assert calls == ["https://x/1"]
    assert [json.loads(p.read_text()) for p in tmp_path.iterdir()] == [{"n": 1}]
