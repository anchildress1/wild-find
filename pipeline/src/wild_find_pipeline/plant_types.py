"""Build step 3: one plant type per species-table row from GBIF taxonomy and USDA PLANTS growth habit.

Writes the committed pipeline/data/plant_types.json. Reads the pinned USDA archive (make toxicity caches it) and the
GBIF species/match replies cached in .models/gbif by make synonyms; a cache miss is fetched once and cached.
Rule, first hit wins: fern, moss, or conifer from GBIF class or phylum; else the USDA growth habit of the row's name
or, failing that, its first shipped GBIF alias (synonyms.json); else grass for Poaceae; else null. Taxonomy goes
first because USDA calls every fern a forb/herb and every pine a tree, which would leave those types unused.
"""

import json
import sys
from collections.abc import Callable
from datetime import date

from wild_find_pipeline import usda
from wild_find_pipeline.gbif import GBIF, match_url
from wild_find_pipeline.labels import table_rows
from wild_find_pipeline.paths import PLANT_TYPES, SYNONYMS
from wild_find_pipeline.synonyms import cached_get

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
    found, taxa = usda.measurements(archive, lambda r: r["measurementType"] == HABIT)
    habits = usda.by_binomial(((taxon, r["measurementValue"].rsplit("/", 1)[-1]) for taxon, r in found), taxa)
    return {name: sorted(set(values)) for name, values in habits.items()}


def habit_type(habits: list[str]) -> str | None:
    """The plant type of a USDA habit list under HABIT_PRECEDENCE, else None."""
    return next((kind for habit, kind in HABIT_PRECEDENCE if habit in habits), None)


def taxonomy(name: str, fetch: Callable[[str], dict] = cached_get) -> dict:
    """GBIF species/match reply for a plant name: the same strict query make synonyms cached."""
    return fetch(match_url(name))


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
    rows = table_rows()
    aliases = json.loads(SYNONYMS.read_text())["species"]
    if missing := [row for row in rows if row not in aliases]:
        raise ValueError(f"{SYNONYMS.name} lacks {len(missing)} species, e.g. {missing[:3]}; run make synonyms")
    habits = usda_habits(usda.archive())
    species = {}
    for row in sorted(rows):
        matched = [(name, habits[name]) for name in [row, *aliases[row]] if name in habits]
        species[row] = plant_type(taxonomy(row), matched)
    PLANT_TYPES.write_text(
        json.dumps(
            {
                "built": date.today().isoformat(),
                "source": {"usda": {"url": usda.USDA_URL, "sha256": usda.USDA_SHA256}, "gbif": GBIF},
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
