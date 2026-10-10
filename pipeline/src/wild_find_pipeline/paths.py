"""Repo-relative paths shared by pipeline steps."""

import hashlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
MODEL_CACHE = REPO / ".models"
MANIFEST = REPO / "core/src/main/resources/models.properties"
REFERENCE_DIR = REPO / "app/src/androidTest/assets/reference"
# Pillow crops and resizes the JVM tests must reproduce pixel for pixel.
CROP_REFERENCE_DIR = REPO / "core/src/test/resources/crops"
# Committed menu-word and tutorial text vectors; they need the teacher, so CI never rebuilds them.
LABELS_DIR = REPO / "app/src/main/assets"
# Bundled into the APK by app/build.gradle.kts; gitignored, rebuilt by `make assets`.
GENERATED_ASSETS = REPO / "app/generated/assets"
# Input hashes of the last good make assets; app/build.gradle.kts refuses to package assets whose inputs changed.
GENERATED_STAMP = REPO / "app/generated/inputs.json"
# Teacher text vectors for hazard species the pinned species table lacks; committed so CI never needs the teacher.
HAZARD_VECTORS = REPO / "pipeline/data/hazard_vectors.json"
# Toxicity flag per species-table row; committed because it needs ~300 network requests (make toxicity).
TOXICITY = REPO / "pipeline/data/toxicity.json"
# GBIF aliases per species-table row; committed because it needs ~13,000 network requests (make synonyms).
SYNONYMS = REPO / "pipeline/data/synonyms.json"
# Plant type per species-table row; committed because it reads the USDA archive and GBIF cache (make plant-types).
PLANT_TYPES = REPO / "pipeline/data/plant_types.json"
# Kid-level description per species-table row, templated from USDA traits; committed (make descriptions).
DESCRIPTIONS = REPO / "pipeline/data/descriptions.json"
# Graded-later "where to look" hints per playable row, from a local Gemma 4 run; committed (build step hints).
HINTS = REPO / "pipeline/data/hints.json"
# Contact-hazard verdict per species-table row, from a local Gemma 4 run; committed (make contact-hazards).
CONTACT_HAZARDS = REPO / "pipeline/data/contact_hazards.json"
# Hand decisions on the contact-hazard rows the checks could not clear; only owner-approved rows ship.
CONTACT_REVIEW = REPO / "pipeline/data/contact_hazards_review.json"
# Hand-kept rows the hunt never picks as a target, each with its measured reason (day-5 target pass study).
NO_TARGET = REPO / "pipeline/data/no_target.json"


def pin(model: str, manifest: Path = MANIFEST) -> dict[str, str]:
    """Return the `<model>.*` keys of the pinned-model manifest, prefix stripped."""
    prefix = f"{model}."
    pins = {}
    for line in manifest.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and key.startswith(prefix):
            pins[key.removeprefix(prefix)] = value
    if not pins:
        raise KeyError(f"no pins for {model!r} in {manifest}")
    return pins


def file_sha256(path: Path) -> str:
    """Hex SHA-256 of a file, streamed so multi-GB models never load into memory."""
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def verified_artifact(model: str, cache: Path = MODEL_CACHE, manifest: Path = MANIFEST) -> Path:
    """Return the cached file for `model` after checking its pinned byte count and SHA-256.

    Raises ValueError when the file is missing or doesn't match its pins.
    """
    pins = pin(model, manifest)
    path = cache / pins["file"]
    if not path.is_file():
        raise ValueError(f"{path} missing")
    if path.stat().st_size != int(pins["bytes"]) or file_sha256(path) != pins["sha256"]:
        raise ValueError(f"{path} does not match its pinned size/SHA-256")
    return path


def ensure_artifact(model: str, cache: Path = MODEL_CACHE, manifest: Path = MANIFEST) -> Path:
    """Return the verified cached file for `model`, downloading its pinned revision first when missing or wrong.

    Raises ValueError when the downloaded bytes don't match the pins.
    """
    try:
        return verified_artifact(model, cache, manifest)
    except ValueError:
        from huggingface_hub import hf_hub_download

        pins = pin(model, manifest)
        # The start URL is pinned (repo, revision, file); trust comes from size + SHA-256, never the CDN host.
        # force_download: a corrupt file whose local metadata matches the pinned commit is otherwise kept as is.
        hf_hub_download(pins["repo"], pins["file"], revision=pins["revision"], local_dir=cache, force_download=True)
        return verified_artifact(model, cache, manifest)
