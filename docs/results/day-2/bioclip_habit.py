"""Day-2 probe: can BioCLIP name a plant's type (tree, shrub, vine, herb, grass) from its species name alone?

Run from the repo root after make assets:

    uv run --project pipeline --group reference python -I docs/results/day-2/bioclip_habit.py <usda dir>

<usda dir> holds the extracted USDA PLANTS traits archive (zenodo.org/records/17903503). Ground truth is USDA's
PlantHabit for species-table rows with exactly one of the five habits. Each row's shipped species-table vector
("a photo of <scientific name>." from the BioCLIP 2.5 teacher) is scored against one teacher vector per habit
prompt; top-1 is the guess. Writes bioclip_habit.csv and prints accuracy and the confusion matrix.
"""

import collections
import csv
import json
import sys
from pathlib import Path

import numpy as np
from wild_find_pipeline.paths import GENERATED_ASSETS
from wild_find_pipeline.reference import embed_texts

OUT = Path(__file__).resolve().parent
HABITS = {
    "tree": "a photo of a tree.",
    "shrub": "a photo of a shrub.",
    "vine": "a photo of a vine.",
    "forbHerb": "a photo of a wildflower.",
    "graminoid": "a photo of grass.",
}


def usda_habits(src: Path) -> dict[str, str]:
    """Binomial to its single USDA PlantHabit among HABITS; species with several habits are left out."""
    csv.field_size_limit(sys.maxsize)

    def rows(name: str):
        return csv.DictReader((src / name).open(newline=""), delimiter="\t", quoting=csv.QUOTE_NONE)

    found = collections.defaultdict(set)
    for r in rows("measurement_or_fact_specific.tab"):
        if r["measurementType"].endswith("PlantHabit"):
            found[r["occurrenceID"]].add(r["measurementValue"].rsplit("/", 1)[-1])
    taxa = {r["occurrenceID"]: r["taxonID"] for r in rows("occurrence_specific.tab") if r["occurrenceID"] in found}
    names = {r["taxonID"]: " ".join(r["scientificName"].split()[:2]) for r in rows("taxon.tab")}
    habits = collections.defaultdict(set)
    for occurrence, values in found.items():
        if taxa.get(occurrence) in names:
            habits[names[taxa[occurrence]]] |= values
    return {name: next(iter(v)) for name, v in habits.items() if len(v) == 1 and next(iter(v)) in HABITS}


def main() -> int:
    """Score every table row USDA gives one habit for, and report accuracy."""
    truth = usda_habits(Path(sys.argv[1]))
    labels = json.loads((GENERATED_ASSETS / "species_labels.json").read_text())
    table = np.load(GENERATED_ASSETS / "species_table.npy")
    rows = [(i, e["scientific"]) for i, e in enumerate(labels) if e["scientific"] in truth]
    prompts = embed_texts(list(HABITS.values()))
    scores = table[[i for i, _ in rows]] @ prompts.T
    names = list(HABITS)
    out, confusion = [], collections.Counter()
    for (_, name), row in zip(rows, scores, strict=True):
        guess = names[int(row.argmax())]
        confusion[(truth[name], guess)] += 1
        out.append({"scientific": name, "usda": truth[name], "bioclip": guess,
                    **{h: round(float(s), 6) for h, s in zip(names, row, strict=True)}})  # fmt: skip
    with (OUT / "bioclip_habit.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)
    right = sum(r["usda"] == r["bioclip"] for r in out)
    base = collections.Counter(r["usda"] for r in out).most_common(1)[0]
    print(f"{len(out)} of {len(labels)} table rows have one USDA habit; BioCLIP top-1 right on {right} ({right / len(out):.1%})")
    print(f"always guessing the commonest habit ({base[0]}) would be right on {base[1]} ({base[1] / len(out):.1%})")
    print("usda \\ bioclip: " + " ".join(f"{h:>9}" for h in names))
    for t in names:
        print(f"{t:>14}: " + " ".join(f"{confusion[(t, g)]:>9}" for g in names))
    return 0


if __name__ == "__main__":
    sys.exit(main())
