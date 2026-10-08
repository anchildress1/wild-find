"""labels.npy and labels.json: teacher text vectors for the fixed grass-tutorial labels (R3)."""

import json
import sys
from pathlib import Path

import numpy as np

from wild_find_pipeline.labels import TUTORIAL, embedding_versions, prompt, teacher_model
from wild_find_pipeline.paths import LABELS_DIR

SCHEMA_VERSION = 2
UNIT_TOLERANCE = 1e-4


def label_rows(taxa: tuple[str, ...] = TUTORIAL) -> list[dict[str, str]]:
    """One row per tutorial label, in labels.npy order."""
    return [{"scientific": taxon, "prompt": prompt(taxon)} for taxon in taxa]


def check_vectors(vectors: np.ndarray, count: int) -> np.ndarray:
    """Return `vectors` as little-endian float32 after checking shape, finiteness, and unit length.

    Raises ValueError on any failure, so a broken teacher never ships.
    """
    if vectors.ndim != 2 or vectors.shape[0] != count:
        raise ValueError(f"expected {count} rows, got shape {vectors.shape}")
    if not np.isfinite(vectors).all():
        raise ValueError("non-finite text vector")
    norms = np.linalg.norm(vectors, axis=1)
    if not np.allclose(norms, 1.0, atol=UNIT_TOLERANCE):
        raise ValueError(f"text vectors are not unit length: {norms}")
    return np.ascontiguousarray(vectors, dtype="<f4")


def write(out: Path, rows: list[dict[str, str]], vectors: np.ndarray) -> None:
    """Write labels.npy and labels.json under `out`."""
    out.mkdir(parents=True, exist_ok=True)
    np.save(out / "labels.npy", check_vectors(vectors, len(rows)))
    (out / "labels.json").write_text(
        json.dumps(
            {
                "schema_version": SCHEMA_VERSION,
                "text_model": teacher_model(),
                "packages": embedding_versions(),
                "labels": rows,
            },
            indent=1,
        )
        + "\n"
    )


def check_committed(out: Path = LABELS_DIR, taxa: tuple[str, ...] = TUTORIAL) -> None:
    """Raise ValueError unless the committed labels match the label lists, prompt, teacher pin, and packages.

    They need the teacher to rebuild, so nothing else would notice a changed tutorial row or prompt.
    """
    meta = json.loads((out / "labels.json").read_text())
    expected = label_rows(taxa)
    if meta.get("schema_version") != SCHEMA_VERSION or meta.get("text_model") != teacher_model():
        raise ValueError("labels.json predates the current schema or teacher pin; run make labels")
    if meta.get("packages") != embedding_versions():
        raise ValueError("labels.json came from other embedding package versions; run make labels")
    if meta.get("labels") != expected:
        raise ValueError("labels.json rows or prompts differ from the label lists; run make labels")
    check_vectors(np.load(out / "labels.npy"), len(expected))


def main() -> int:
    """Embed the tutorial labels with the pinned teacher."""
    from wild_find_pipeline.reference import embed_texts

    rows = label_rows()
    write(LABELS_DIR, rows, embed_texts([row["prompt"] for row in rows]))
    print(f"OK: wrote {len(rows)} labels to {LABELS_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
