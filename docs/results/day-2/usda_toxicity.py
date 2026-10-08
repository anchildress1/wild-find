"""Day-2 probe: USDA PLANTS HumanLivestockToxicity for the 25+ October species, beside the Wikipedia keyword flag.

Run from the repo root after toxicity_flag.py: uv --project pipeline run python -I docs/results/day-2/usda_toxicity.py <dir>
<dir> holds the extracted usda_plant_traits.tar.gz from https://zenodo.org/records/17903503 (Dec 11, 2025).
Writes usda_toxicity.csv beside this file.
"""

import collections
import csv
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent
# USDA's toxicity values as the EOL export encodes them.
LEVELS = {
    "http://purl.bioontology.org/ontology/SNOMEDCT/260413007": "none",
    "http://purl.obolibrary.org/obo/PATO_0000394": "slight",
    "http://purl.obolibrary.org/obo/PATO_0000395": "moderate",
    "http://purl.obolibrary.org/obo/PATO_0000396": "severe",
}


def rows(path: Path) -> csv.DictReader:
    """Tab-separated DwC-A rows."""
    return csv.DictReader(path.open(newline=""), delimiter="\t", quoting=csv.QUOTE_NONE)


def main() -> int:
    """Join USDA toxicity to each species by binomial and write it next to the Wikipedia flag."""
    src = Path(sys.argv[1])
    csv.field_size_limit(10**9)
    tox = {r["occurrenceID"]: LEVELS.get(r["measurementValue"], r["measurementValue"])
           for r in rows(src / "measurement_or_fact_specific.tab")
           if r["measurementType"].endswith("HumanLivestockToxicity")}  # fmt: skip
    occ = {r["occurrenceID"]: r["taxonID"] for r in rows(src / "occurrence_specific.tab")}
    names = {r["taxonID"]: " ".join(r["scientificName"].split()[:2]) for r in rows(src / "taxon.tab")}
    usda = {names.get(occ.get(o, ""), ""): level for o, level in tox.items()}
    print(f"USDA toxicity rows: {len(tox)}, {collections.Counter(tox.values()).most_common()}")
    out = []
    for r in csv.DictReader((OUT / "toxicity_flag.csv").open()):
        out.append({"count": r["count"], "name": r["name"], "common": r["common"],
                    "usda": usda.get(r["name"], "missing"), "wikipedia_toxic": r["toxic"]})  # fmt: skip
    with (OUT / "usda_toxicity.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)
    print(f"{len(out)} species: {collections.Counter(r['usda'] for r in out).most_common()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
