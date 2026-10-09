"""Size, season, and sign hints a kid can check by eye, templated from USDA PLANTS traits; no model, no network.

These used to live in the description sentence. The description is now generic (descriptions.py), so the specifics
come out here as hint candidates for hint_rank. Only traits USDA states are used: size, flower color and season, fruit
color and season, fall color, and a leaf color other than plain green.
"""

import csv
import io
import sys
import tarfile
from pathlib import Path

from wild_find_pipeline.descriptions import NOUNS, article

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
# Fall things lead, since the game ships in October; a plant keeps at most this many features.
FALL = "fall"
MAX_FEATURES = 2


def usda_traits(archive: bytes, wanted: tuple[str, ...] = TRAITS) -> dict[str, dict[str, list[str]]]:
    """Binomial to {trait: sorted values} for `wanted`, from its species row if it has any, else its infraspecific rows.

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
            if trait in wanted:
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


def size(kind: str | None, traits: dict[str, list[str]]) -> str | None:
    """A size word from the tallest USDA height in feet, for the types where height says something; vines get none."""
    if kind not in SIZES or HEIGHT not in traits:
        return None
    feet = max(float(v) for v in traits[HEIGHT])
    small, low, big, high = SIZES[kind]
    return small if feet <= low else big if feet >= high else None


def fruit_season(begin: list[str], end: list[str]) -> str | None:
    """Fall when the Fruit/Seed Period from begin to end covers it, else None: the hint then names no season.

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


def candidates(kind: str | None, traits: dict[str, list[str]]) -> list[dict]:
    """Size, season, and sign hint candidates for hint_rank, one per aspect at most, each backed by a USDA trait."""
    found = {}
    if big := size(kind, traits):
        noun = f"{big} {NOUNS.get(kind, 'plant')}"
        found["size"] = (f"It is {article(noun).lower()} {noun}.", big)
    for phrase, when in features(kind, traits):
        if when and "season" not in found:
            found["season"] = (f"Look for {phrase} in {when}.", when)
        elif not when and "sign" not in found:
            found["sign"] = (f"Look for {phrase}.", None)
    return [{"aspect": a, "text": t, "support": "usda", "bucket": b} for a, (t, b) in found.items()]
