"""Label sets and the text template the BioCLIP text encoder embeds."""

# Plant labels embed by scientific name: common names share words ("oak" in "poison oak"), and on Day 1 a
# white oak photo scored "poison oak" top-1 on both BioCLIP models until labels switched to scientific names.
HAZARDS = {
    "poison ivy": "Toxicodendron radicans",
    "poison oak": "Toxicodendron pubescens",
    "poison sumac": "Toxicodendron vernix",
    "pokeweed": "Phytolacca americana",
    "Carolina horsenettle": "Solanum carolinense",
}
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


def is_hazard(scientific: str) -> bool:
    """True for every Toxicodendron species and each other PRD hazard species."""
    return scientific.startswith("Toxicodendron ") or scientific in HAZARDS.values()
