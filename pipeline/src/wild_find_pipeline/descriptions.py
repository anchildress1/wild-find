"""A short kid-level description per species-table row, templated from USDA PLANTS traits; no model, no network.

Writes the committed pipeline/data/descriptions.json. Reads the pinned USDA archive (make toxicity caches it), the
committed plant_types.json for each row's type, and synonyms.json for the names a row matches, as make plant-types
does. Only traits USDA states are used, and only the ones a kid can see: size, flower color and season, fruit color
and season, fall color, and a leaf color other than plain green. A row with none of them gets no description, and
the app falls back to the type label.
"""

import csv
import io
import json
import sys
import tarfile
from datetime import date
from pathlib import Path

from wild_find_pipeline.labels import lacking_hazards
from wild_find_pipeline.paths import DESCRIPTIONS, PLANT_TYPES, SYNONYMS, ensure_artifact
from wild_find_pipeline.toxicity import USDA_SHA256, USDA_URL, usda_archive

# USDA trait terms, by the last segment of their measurementType IRI.
HEIGHT = "TO_0000207"
FLOWER_COLOR = "TO_0000537"
LEAF_COLOR = "TO_0000326"
BLOOM = "BloomPeriod"
SEED_BEGIN = "SeedPeriodBegin"
SEED_END = "SeedPeriodEnd"
LEAF_RETENTION = "PATO_0001729"
FRUIT_COLOR = "FruitSeedColor"
SHOWY = "humanAgriculture.owl#Horticulture"
TRAITS = (HEIGHT, FLOWER_COLOR, LEAF_COLOR, BLOOM, SEED_BEGIN, SEED_END, FRUIT_COLOR, LEAF_RETENTION, SHOWY)
# Leaf Retention: "Does the tree, shrub, or sub-shrub retain its leaves year round?" (PATO evergreen, plant).
EVERGREEN = "PATO_0001733"
# Types USDA scores Leaf Retention for (vines climb on woody stems too); only these can claim fall leaves.
WOODY = ("tree", "shrub", "vine")
# Seasons in the order a year runs, for the fruit/seed period.
YEAR = ("spring", "summer", "fall", "winter")

# PATO and USDA color terms, labels checked against the EBI Ontology Lookup Service on Oct 8.
COLORS = {
    "PATO_0000317": "black",
    "PATO_0000318": "blue",
    "PATO_0000320": "green",
    "PATO_0000322": "red",
    "PATO_0000323": "white",
    "PATO_0000324": "yellow",
    "PATO_0000951": "purple",
    "PATO_0000952": "brown",
    "PATO_0000953": "orange",
    "grayGreen": "gray-green",
    "whiteGrey": "silvery",
    "yellowGreen": "yellow-green",
}
# NCI Thesaurus seasons (C94730-C94733) and USDA's early/mid/late periods, folded into four kid seasons.
SEASONS = {
    "Thesaurus.owl#C94730": "winter",
    "Thesaurus.owl#C94731": "spring",
    "Thesaurus.owl#C94732": "summer",
    "Thesaurus.owl#C94733": "fall",
    "lateWinter": "winter",
    "earlySpring": "spring",
    "midSpring": "spring",
    "lateSpring": "spring",
    "earlySummer": "summer",
    "midSummer": "summer",
    "lateSummer": "summer",
}
# What a kid calls each plant type; "herb" means nothing to an 8-year-old.
NOUNS = {
    "tree": "tree",
    "shrub": "bush",
    "vine": "vine",
    "herb": "plant",
    "grass": "grass",
    "fern": "fern",
    "moss": "moss",
    "conifer": "evergreen",
    None: "plant",
}
# (type, feet at or below which it is "small", feet at or above which it is "tall"); between, no size word.
SIZES = {
    "tree": ("small", 25, "tall", 70),
    "conifer": ("small", 25, "tall", 70),
    "shrub": ("small", 3, "big", 12),
    "herb": ("low", 1.5, "tall", 5),
    "grass": ("low", 1.5, "tall", 5),
    "fern": ("low", 1, "tall", 3),
    None: ("low", 1.5, "tall", 5),
}
# Flower rules by type: these show any flower color they have; these never mention flowers.
UNSHOWY_OK = ("herb", "shrub", "vine", None)
NO_FLOWERS = ("conifer", "fern", "grass", "moss")
# Fall things lead, since the game ships in October; a description keeps at most this many features.
FALL = "fall"
MAX_FEATURES = 2
# Copy rule for every kid-facing string; a generated sentence that hits one fails the build.
BANNED = ("safe", "harmless", "not poisonous", "okay to touch", "ok to touch")


def usda_traits(archive: bytes) -> dict[str, dict[str, list[str]]]:
    """Binomial to {trait: sorted values} for TRAITS, from its species row when it has any, else its infraspecific rows.

    Heights keep their numbers; every other value is the last IRI segment.
    """
    csv.field_size_limit(sys.maxsize)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        members = {Path(m.name).name: m for m in tar.getmembers()}

        def rows(member: str):
            text = tar.extractfile(members[member]).read().decode()
            return csv.DictReader(io.StringIO(text), delimiter="\t", quoting=csv.QUOTE_NONE)

        facts: dict[str, list[tuple[str, str]]] = {}
        for r in rows("measurement_or_fact_specific.tab"):
            trait = r["measurementType"].rsplit("/", 1)[-1]
            if trait in TRAITS:
                facts.setdefault(r["occurrenceID"], []).append((trait, r["measurementValue"].rsplit("/", 1)[-1]))
        by_taxon: dict[str, list[tuple[str, str]]] = {}
        for r in rows("occurrence_specific.tab"):
            if r["occurrenceID"] in facts:
                by_taxon.setdefault(r["taxonID"], []).extend(facts[r["occurrenceID"]])
        taxa = {r["taxonID"]: r for r in rows("taxon.tab")}
    species: dict[str, dict[str, set[str]]] = {}
    infra: dict[str, dict[str, set[str]]] = {}
    for taxon, found in by_taxon.items():
        row = taxa.get(taxon)
        if row is None or row["taxonRank"] == "genus":
            continue
        name = " ".join(row["scientificName"].split()[:2])
        into = (species if row["taxonRank"] == "species" else infra).setdefault(name, {})
        for trait, value in found:
            into.setdefault(trait, set()).add(value)
    return {name: {t: sorted(v) for t, v in traits.items()} for name, traits in (infra | species).items()}


def article(word: str) -> str:
    """'An' before a vowel sound, else 'A'."""
    return "An" if word[0] in "aeiou" else "A"


def size(kind: str | None, traits: dict[str, list[str]]) -> str | None:
    """A size word from the tallest USDA height in feet, for the types where height says something; vines get none."""
    if kind not in SIZES or HEIGHT not in traits:
        return None
    feet = max(float(v) for v in traits[HEIGHT])
    small, low, big, high = SIZES[kind]
    return small if feet <= low else big if feet >= high else None


def fruit_season(begin: list[str], end: list[str]) -> str | None:
    """Fall when the Fruit/Seed Period from begin to end covers it, else None: the sentence then names no season.

    USDA defines begin and end as the seasons the earliest and latest fruit is visually obvious, so the begin season
    alone can be months early (oaks begin in summer). A period that misses fall is left unsaid rather than trusted:
    on Oct 8 every such tree target (water oak, tuliptree, southern magnolia, witch-hazel) read summer for fruit a
    kid sees in fall (docs/results/day-3/descriptions.log). Unknown or year-round also says no season.
    """
    first, last = season(begin), season(end)
    if first is None or last is None:
        return None
    start, stop = YEAR.index(first), YEAR.index(last)
    covered = {YEAR[(start + i) % len(YEAR)] for i in range((stop - start) % len(YEAR) + 1)}
    return FALL if FALL in covered else None


def season(values: list[str]) -> str | None:
    """The one season [values] fold into, preferring fall when it is among them; None when unknown."""
    seasons = sorted({SEASONS[v] for v in values if v in SEASONS})
    return FALL if FALL in seasons else seasons[0] if len(seasons) == 1 else None


def color(values: list[str]) -> str | None:
    """The color word when USDA gives exactly one known color, else None, so nothing is guessed."""
    colors = {COLORS[v] for v in values if v in COLORS}
    return colors.pop() if len(colors) == 1 else None


def features(kind: str | None, traits: dict[str, list[str]]) -> list[tuple[str, str | None]]:
    """(phrase, season or None) for each feature a kid can see; fall ones first, at most MAX_FEATURES."""
    showy = set(traits.get(SHOWY, []))
    found: list[tuple[str, str | None]] = []
    # Fall Conspicuous asks whether the leaves *or fruits* stand out in autumn, so it says leaves only when the plant
    # isn't evergreen and its fruit isn't the showy part; otherwise it would be a guess.
    # Leaf Retention is scored only for trees, shrubs, and subshrubs, so other types can't rule out a non-leaf show.
    leaves_show = "fallConspicuousYes" in showy and "fruitSeedConspicuousYes" not in showy and kind in WOODY
    if leaves_show and EVERGREEN not in traits.get(LEAF_RETENTION, []):
        found.append(("bright leaves", FALL))
    flower = color(traits.get(FLOWER_COLOR, []))
    # Small plants' flower color counts even unshowy (blue mistflower's blue is how kids spot it); a tree's must be
    # showy, or every oak gets "yellow flowers" for its catkins; conifers, ferns, and grasses have none to see.
    showy_flowers = "flowerConspicuousYes" in showy or kind in UNSHOWY_OK
    if flower and flower != "green" and showy_flowers and kind not in NO_FLOWERS:
        found.append((f"{flower} flowers", season(traits.get(BLOOM, []))))
    fruit = color(traits.get(FRUIT_COLOR, []))
    if "fruitSeedConspicuousYes" in showy and fruit:
        noun = "seeds" if fruit == "brown" else "fruit"
        found.append((f"{fruit} {noun}", fruit_season(traits.get(SEED_BEGIN, []), traits.get(SEED_END, []))))
    leaf = color(traits.get(LEAF_COLOR, []))
    if leaf and leaf not in ("green", "red"):
        found.append((f"{leaf} leaves", None))
    found.sort(key=lambda f: f[1] != FALL)
    return found[:MAX_FEATURES]


def sentence(kind: str | None, traits: dict[str, list[str]]) -> str | None:
    """One short sentence from [traits], or None when USDA gives nothing a kid can see."""
    big = size(kind, traits)
    seen = features(kind, traits)
    if not big and not seen:
        return None
    noun = f"{big} {NOUNS.get(kind, 'plant')}" if big else NOUNS.get(kind, "plant")
    parts = []
    for i, (phrase, when) in enumerate(seen):
        # "bright leaves and orange fruit in fall", not "bright leaves in fall and orange fruit in fall".
        later = seen[i + 1][1] if i + 1 < len(seen) else None
        parts.append(phrase if when is None or when == later else f"{phrase} in {when}")
    text = f"{article(noun)} {noun}" + (f" with {' and '.join(parts)}" if parts else "") + "."
    if any(word in text.lower() for word in BANNED):
        raise ValueError(f"banned kid word in {text!r}")
    return text


def describe(kind: str | None, usda: list[tuple[str, dict[str, list[str]]]]) -> dict:
    """The description and the USDA name and traits behind it, from the first matched name that yields one."""
    for name, traits in usda:
        if text := sentence(kind, traits):
            used = {t: traits[t] for t in TRAITS if t in traits}
            return {"description": text, "usda": name, "traits": used}
    return {"description": None, "usda": None, "traits": {}}


def main() -> int:
    """Write descriptions.json: one entry per species-table row, plus the rules that made them."""
    names = [e["scientific"] for e in json.loads(ensure_artifact("taxa_labels").read_text())]
    rows = names + lacking_hazards(names)
    aliases = json.loads(SYNONYMS.read_text())["species"]
    types = json.loads(PLANT_TYPES.read_text())["species"]
    if missing := [row for row in rows if row not in aliases or row not in types]:
        raise ValueError(f"synonyms or plant types lack {len(missing)} rows, e.g. {missing[:3]}")
    traits = usda_traits(usda_archive())
    species = {}
    for row in sorted(rows):
        usda = [(name, traits[name]) for name in [row, *aliases[row]] if name in traits]
        species[row] = describe(types[row]["type"], usda)
    DESCRIPTIONS.write_text(
        json.dumps(
            {
                "built": date.today().isoformat(),
                "source": {"usda": {"url": USDA_URL, "sha256": USDA_SHA256}},
                "rule": {
                    "usda_names": "row name, then its synonyms.json aliases in order; first that yields a sentence",
                    "noun_by_type": {str(k): v for k, v in NOUNS.items()},
                    "size_by_type_feet": {str(k): v for k, v in SIZES.items()},
                    "features": [
                        "bright leaves in fall: tree, shrub, or vine; fallConspicuousYes, fruit not showy, and not "
                        "evergreen (Fall Conspicuous covers leaves or fruits)",
                        "<color> flowers [in <bloom season>]: one non-green flower color; trees only when showy; "
                        "never on conifers, ferns, grasses, or mosses",
                        "<color> fruit, or brown seeds, [in fall when the Fruit/Seed Period Begin-End span covers "
                        "fall, else no season]: fruitSeedConspicuousYes and one fruit color",
                        "<color> leaves: one leaf color other than green or red",
                    ],
                    "order": f"fall features first; at most {MAX_FEATURES}",
                    "colors": COLORS,
                    "seasons": SEASONS,
                    "banned": list(BANNED),
                },
                "species": species,
            },
            indent=1,
            ensure_ascii=False,
        )
        + "\n"
    )
    described = sum(1 for e in species.values() if e["description"])
    print(f"OK: {DESCRIPTIONS}: {described} of {len(species)} rows described")
    return 0


if __name__ == "__main__":
    sys.exit(main())
