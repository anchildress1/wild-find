"""Build step 3: one plant type per species-table row from GBIF taxonomy and USDA PLANTS growth habit.

Writes the committed pipeline/data/plant_types.json. Reads the pinned USDA archive (make toxicity caches it) and the
GBIF species/match replies cached in .models/gbif by make synonyms; a cache miss is fetched once and cached.
Rule, first hit wins: fern, moss, or conifer from GBIF class or phylum; else the USDA growth habit of the row's name
or, failing that, its first shipped GBIF alias (synonyms.json); else grass for Poaceae; else null. Taxonomy goes
first because USDA calls every fern a forb/herb and every pine a tree, which would leave those types unused.
"""

import csv
import io
import json
import sys
import tarfile
import urllib.parse
from collections.abc import Callable
from datetime import date
from pathlib import Path

from wild_find_pipeline.labels import lacking_hazards
from wild_find_pipeline.paths import PLANT_TYPES, SYNONYMS, ensure_artifact
from wild_find_pipeline.synonyms import cached_get
from wild_find_pipeline.toxicity import GBIF, USDA_SHA256, USDA_URL, usda_archive

# GBIF backbone groups, checked before USDA. Lycophytes (clubmosses, spikemosses, quillworts) count as ferns: they
# are spore-bearing vascular plants, the old "fern allies", and not mosses.
TAXON_TYPES = (
    ("class", "Polypodiopsida", "fern"),
    ("class", "Lycopodiopsida", "fern"),
    ("phylum", "Bryophyta", "moss"),
    ("phylum", "Marchantiophyta", "moss"),
    ("phylum", "Anthocerotophyta", "moss"),
    ("class", "Pinopsida", "conifer"),
)
# USDA lists several habits in no stable order, so the type is the first of these the species has. A climber or
# grass-like habit is the most telling; tree beats shrub; subshrub counts only when nothing else is listed.
# Lichenous and nonvascular map to nothing and fall through to taxonomy.
HABIT_PRECEDENCE = (
    ("graminoid", "grass"),
    ("vine", "vine"),
    ("tree", "tree"),
    ("shrub", "shrub"),
    ("forbHerb", "herb"),
    ("subshrub", "shrub"),
)
HABIT = "http://eol.org/schema/terms/PlantHabit"


def usda_habits(archive: bytes) -> dict[str, list[str]]:
    """Binomial to its USDA growth habits, from the species row when it has any, else from its infraspecific rows."""
    csv.field_size_limit(sys.maxsize)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        members = {Path(m.name).name: m for m in tar.getmembers()}

        def rows(member: str):
            text = tar.extractfile(members[member]).read().decode()
            return csv.DictReader(io.StringIO(text), delimiter="\t", quoting=csv.QUOTE_NONE)

        habits = {
            r["occurrenceID"]: r["measurementValue"].rsplit("/", 1)[-1]
            for r in rows("measurement_or_fact_specific.tab")
            if r["measurementType"] == HABIT
        }
        by_taxon: dict[str, set[str]] = {}
        for r in rows("occurrence_specific.tab"):
            if r["occurrenceID"] in habits:
                by_taxon.setdefault(r["taxonID"], set()).add(habits[r["occurrenceID"]])
        taxa = {r["taxonID"]: r for r in rows("taxon.tab")}
    species: dict[str, set[str]] = {}
    infra: dict[str, set[str]] = {}
    for taxon, found in by_taxon.items():
        row = taxa.get(taxon)
        if row is None or row["taxonRank"] == "genus":
            continue
        name = " ".join(row["scientificName"].split()[:2])
        (species if row["taxonRank"] == "species" else infra).setdefault(name, set()).update(found)
    return {name: sorted(found) for name, found in (infra | species).items()}


def habit_type(habits: list[str]) -> str | None:
    """The plant type of a USDA habit list under HABIT_PRECEDENCE, else None."""
    return next((kind for habit, kind in HABIT_PRECEDENCE if habit in habits), None)


def taxonomy(name: str, fetch: Callable[[str], dict] = cached_get) -> dict:
    """GBIF species/match reply for a plant name: the same strict query make synonyms cached."""
    query = urllib.parse.urlencode({"name": name, "kingdom": "Plantae", "strict": "true"})
    return fetch(f"{GBIF}/species/match?{query}")


def plant_type(match: dict, usda: list[tuple[str, list[str]]]) -> dict:
    """Type and its source for one row, from its GBIF match and its USDA-matched names with their habits in order."""
    for rank, group, kind in TAXON_TYPES:
        if match.get(rank) == group:
            return {"type": kind, "source": f"gbif {rank}: {group}"}
    for name, habits in usda:
        if kind := habit_type(habits):
            return {"type": kind, "source": f"usda {name}: {', '.join(habits)}"}
    if match.get("family") == "Poaceae":
        return {"type": "grass", "source": "gbif family: Poaceae"}
    return {"type": None, "source": None}


def main() -> int:
    """Write plant_types.json: type and source for every species-table row, plus the rule."""
    names = [e["scientific"] for e in json.loads(ensure_artifact("taxa_labels").read_text())]
    rows = names + lacking_hazards(names)
    aliases = json.loads(SYNONYMS.read_text())["species"]
    if missing := [row for row in rows if row not in aliases]:
        raise ValueError(f"{SYNONYMS.name} lacks {len(missing)} species, e.g. {missing[:3]}; run make synonyms")
    habits = usda_habits(usda_archive())
    species = {}
    for row in sorted(rows):
        usda = [(name, habits[name]) for name in [row, *aliases[row]] if name in habits]
        species[row] = plant_type(taxonomy(row), usda)
    PLANT_TYPES.write_text(
        json.dumps(
            {
                "built": date.today().isoformat(),
                "source": {"usda": {"url": USDA_URL, "sha256": USDA_SHA256}, "gbif": GBIF},
                "rule": {
                    "taxonomy_first": [f"{rank} {group}: {kind}" for rank, group, kind in TAXON_TYPES],
                    "usda_names": "row name, then its synonyms.json aliases in order; first with a mapped habit",
                    "usda_habit_precedence": [f"{habit}: {kind}" for habit, kind in HABIT_PRECEDENCE],
                    "fallback": "gbif family Poaceae: grass; else null",
                },
                "species": species,
            },
            indent=1,
            ensure_ascii=False,
        )
        + "\n"
    )
    counts: dict[str, int] = {}
    for entry in species.values():
        key = f"{entry['type']} ({entry['source'].split(' ')[0] if entry['source'] else '-'})"
        counts[key] = counts.get(key, 0) + 1
    print(f"OK: {PLANT_TYPES}: {len(species)} rows {dict(sorted(counts.items()))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
