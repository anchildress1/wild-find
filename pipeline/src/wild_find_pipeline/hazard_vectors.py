"""Embed hazard species missing from BioCLIP Mobile's pinned species table with the pinned BioCLIP teacher."""

import json
import sys

from wild_find_pipeline.labels import embedding_versions, lacking_hazards, prompt, teacher_model
from wild_find_pipeline.paths import HAZARD_VECTORS, ensure_artifact, pin
from wild_find_pipeline.reference import embed_texts


def main() -> int:
    """Write hazard_vectors.json: one unit teacher vector per hazard species the table lacks."""
    names = {entry["scientific"] for entry in json.loads(ensure_artifact("taxa_labels").read_text())}
    lacking = lacking_hazards(names)
    vectors = embed_texts([prompt(taxon) for taxon in lacking]) if lacking else []
    HAZARD_VECTORS.parent.mkdir(parents=True, exist_ok=True)
    HAZARD_VECTORS.write_text(
        json.dumps(
            {
                "text_model": teacher_model(),
                "taxa_labels_sha256": pin("taxa_labels")["sha256"],
                "prompts": {taxon: prompt(taxon) for taxon in lacking},
                "packages": embedding_versions(),
                "species": {
                    taxon: vector.astype(float).tolist() for taxon, vector in zip(lacking, vectors, strict=True)
                },
            },
            indent=1,
        )
        + "\n"
    )
    print(f"OK: wrote {HAZARD_VECTORS} for {lacking}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
