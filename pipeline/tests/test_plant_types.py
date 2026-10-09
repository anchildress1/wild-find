import io
import tarfile
import urllib.parse

import pytest

from wild_find_pipeline.plant_types import HABIT, habit_type, plant_type, taxonomy, usda_habits
from wild_find_pipeline.toxicity import GBIF

TERMS = "http://eol.org/schema/terms/"


def _tab(rows: list[list[str]]) -> bytes:
    return ("\n".join("\t".join(r) for r in rows) + "\n").encode()


def _archive(files: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        for name, data in files.items():
            # The published archive stores members as ./name.tab.
            info = tarfile.TarInfo(f"./{name}")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def test_usda_habits_groups_by_binomial_and_prefers_the_species_row():
    archive = _archive(
        {
            "measurement_or_fact_specific.tab": _tab(
                [
                    ["occurrenceID", "measurementType", "measurementValue"],
                    ["O1", HABIT, f"{TERMS}tree"],
                    ["O2", HABIT, f"{TERMS}shrub"],
                    ["O3", HABIT, f"{TERMS}vine"],
                    ["O4", f"{TERMS}HumanLivestockToxicity", "http://purl.obolibrary.org/obo/PATO_0000396"],
                    ["O5", HABIT, f"{TERMS}forbHerb"],
                    ["O6", HABIT, f"{TERMS}subshrub"],
                    ["O7", HABIT, f"{TERMS}forbHerb"],
                ]
            ),
            "occurrence_specific.tab": _tab(
                [
                    ["occurrenceID", "taxonID"],
                    ["O1", "CECA4"],
                    ["O2", "CECA4"],
                    ["O3", "CECAC"],
                    ["O4", "COMA2"],
                    ["O5", "ERHIH"],
                    ["O6", "ERHIM"],
                    ["O7", "CERCI"],
                ]
            ),
            "taxon.tab": _tab(
                [
                    ["taxonID", "scientificName", "taxonRank"],
                    ["CECA4", "Cercis canadensis L.", "species"],
                    ["CECAC", "Cercis canadensis L. var. canadensis", "variety"],
                    ["COMA2", "Conium maculatum L.", "species"],
                    ["ERHIH", "Erechtites hieraciifolius (L.) Raf. var. hieraciifolius", "variety"],
                    ["ERHIM", "Erechtites hieraciifolius (L.) Raf. var. megalocarpus", "variety"],
                    ["CERCI", "Cercis L.", "genus"],
                ]
            ),
        }
    )

    assert usda_habits(archive) == {
        "Cercis canadensis": ["shrub", "tree"],
        "Erechtites hieraciifolius": ["forbHerb", "subshrub"],
    }


@pytest.mark.parametrize(
    ("habits", "kind"),
    [
        (["shrub", "tree"], "tree"),
        (["forbHerb", "shrub", "subshrub", "vine"], "vine"),
        (["graminoid", "shrub", "subshrub"], "grass"),
        (["forbHerb", "subshrub"], "herb"),
        (["subshrub"], "shrub"),
        (["nonvascular"], None),
        (["lichenous"], None),
        ([], None),
    ],
)
def test_habit_type_follows_the_fixed_precedence(habits, kind):
    assert habit_type(habits) == kind


def test_plant_type_puts_fern_moss_and_conifer_taxonomy_before_usda():
    assert plant_type({"class": "Polypodiopsida"}, [("Polystichum acrostichoides", ["forbHerb"])]) == {
        "type": "fern",
        "source": "gbif class: Polypodiopsida",
    }
    assert plant_type({"class": "Lycopodiopsida"}, [])["type"] == "fern"
    assert plant_type({"phylum": "Marchantiophyta"}, [])["type"] == "moss"
    assert plant_type({"class": "Pinopsida"}, [("Pinus taeda", ["tree"])])["type"] == "conifer"


def test_plant_type_takes_the_first_usda_name_with_a_mapped_habit():
    usda = [("Quercus nigra", ["nonvascular"]), ("Quercus aquatica", ["tree"]), ("Quercus uliginosa", ["shrub"])]

    assert plant_type({"class": "Magnoliopsida"}, usda) == {"type": "tree", "source": "usda Quercus aquatica: tree"}


def test_plant_type_falls_back_to_poaceae_then_none():
    assert plant_type({"family": "Poaceae"}, []) == {"type": "grass", "source": "gbif family: Poaceae"}
    assert plant_type({"family": "Poaceae"}, [("Bambusa vulgaris", ["shrub", "tree"])])["type"] == "tree"
    assert plant_type({"family": "Vitaceae"}, []) == {"type": None, "source": None}
    assert plant_type({}, []) == {"type": None, "source": None}


def test_taxonomy_sends_the_same_strict_plant_match_synonyms_caches():
    seen = []

    def fetch(url: str) -> dict:
        seen.append(url)
        return {"class": "Pinopsida"}

    assert taxonomy("Pinus taeda", fetch) == {"class": "Pinopsida"}
    parsed = urllib.parse.urlparse(seen[0])
    assert f"{parsed.scheme}://{parsed.netloc}{parsed.path}" == f"{GBIF}/species/match"
    assert urllib.parse.parse_qs(parsed.query) == {"name": ["Pinus taeda"], "kingdom": ["Plantae"], "strict": ["true"]}
