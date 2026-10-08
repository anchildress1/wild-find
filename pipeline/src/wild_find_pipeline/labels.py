"""Label sets and the text template the BioCLIP text encoder embeds."""

from wild_find_pipeline.paths import pin

# Plant labels embed by scientific name: common names share words ("oak" in "poison oak"), and on Day 1 a
# white oak photo scored "poison oak" top-1 on both BioCLIP models until labels switched to scientific names.
HAZARDS = {
    "poison ivy": "Toxicodendron radicans",
    "poison oak": "Toxicodendron pubescens",
    "poison sumac": "Toxicodendron vernix",
    "pokeweed": "Phytolacca americana",
    "Carolina horsenettle": "Solanum carolinense",
}
GRASS = "Poaceae"
# PRD R3's fixed grass-tutorial label set, exactly as Day 1 measured it.
TUTORIAL = (GRASS, "Quercus", "Polypodiopsida", "Trifolium", "Pinus", "Taraxacum", *HAZARDS.values())
# Day-1 plant-gate prompts, exact strings with no trailing period; the gate's verdicts were measured on these.
GATE_PLANT = tuple(f"a photo of {x}" for x in ("a plant", "leaves", "a tree", "grass", "a flower", "moss", "a fern"))
GATE_OTHER = tuple(
    f"a photo of {x}"
    for x in (
        "a person",
        "a child",
        "a screen",
        "a phone",
        "a road",
        "a sidewalk",
        "a car",
        "a dog",
        "a room",
        "a building",
    )
)
SCENES = ("lawn", "field", "weedy garden bed", "pavement", "person", "screen")


def prompt(text: str) -> str:
    """Wrap a scientific name or scene word in the embedding template."""
    return f"a photo of {text}."


# Packages whose version changes the teacher's text vectors; hazard_vectors.json records them.
EMBEDDING_PACKAGES = ("open-clip-torch", "torch")


def teacher_model() -> dict[str, str]:
    """The pinned teacher's repo and revision, as every committed text-vector file records it."""
    teacher = pin("teacher")
    return {"repo": teacher["repo"], "revision": teacher["revision"]}


def embedding_versions() -> dict[str, str]:
    """Installed EMBEDDING_PACKAGES versions without local labels, so Linux "2.14.1+cpu" equals macOS "2.14.1"."""
    from importlib.metadata import version

    return {name: version(name).split("+")[0] for name in EMBEDDING_PACKAGES}


def lacking_hazards(names: list[str] | set[str]) -> list[str]:
    """PRD hazard species missing from a species list, in HAZARDS order."""
    return [taxon for taxon in HAZARDS.values() if taxon not in names]


def is_hazard(scientific: str) -> bool:
    """True for every Toxicodendron species and each other PRD hazard species."""
    return scientific.startswith("Toxicodendron ") or scientific in HAZARDS.values()
