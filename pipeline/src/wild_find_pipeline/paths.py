"""Repo-relative paths shared by pipeline steps."""

import hashlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
MODEL_CACHE = REPO / ".models"
MANIFEST = REPO / "core/src/main/resources/models.properties"
REFERENCE_DIR = REPO / "app/src/androidTest/assets/reference"
# Bundled into the APK by app/build.gradle.kts; gitignored, rebuilt by `make assets`.
GENERATED_ASSETS = REPO / "app/generated/assets"
# Teacher text vectors for hazard species the pinned species table lacks; committed so CI never needs the teacher.
HAZARD_VECTORS = REPO / "pipeline/data/hazard_vectors.json"


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
        raise ValueError(f"{path} missing; run make fetch-models")
    if path.stat().st_size != int(pins["bytes"]) or file_sha256(path) != pins["sha256"]:
        raise ValueError(f"{path} does not match its pinned size/SHA-256; run make fetch-models")
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
        hf_hub_download(pins["repo"], pins["file"], revision=pins["revision"], local_dir=cache)
        return verified_artifact(model, cache, manifest)
