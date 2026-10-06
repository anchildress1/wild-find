# Plant labels embed by scientific name: common names share words ("oak" in "poison oak"), and on Day 1 a
# white oak photo scored "poison oak" top-1 on both BioCLIP models until labels switched to scientific names.
HAZARDS = {
    "poison ivy": "Toxicodendron radicans",
    "poison oak": "Toxicodendron pubescens",
    "poison sumac": "Toxicodendron vernix",
    "pokeweed": "Phytolacca americana",
    "Carolina horsenettle": "Solanum carolinense",
}
SCENES = ("lawn", "field", "weedy garden bed", "pavement", "person", "screen")


def prompt(text: str) -> str:
    return f"a photo of {text}."
