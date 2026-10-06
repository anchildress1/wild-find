"""Repo-relative paths shared by pipeline steps."""

import hashlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
MODEL_CACHE = REPO / ".models"
MANIFEST = REPO / "core/src/main/resources/models.properties"
REFERENCE_DIR = REPO / "app/src/androidTest/assets/reference"


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


def verified_artifact(model: str, cache: Path = MODEL_CACHE, manifest: Path = MANIFEST) -> Path:
    """Return the cached file for `model` after checking its pinned byte count and SHA-256.

    Raises ValueError when the file is missing or doesn't match its pins.
    """
    pins = pin(model, manifest)
    path = cache / pins["file"]
    if not path.is_file():
        raise ValueError(f"{path} missing; run make fetch-models")
    if path.stat().st_size != int(pins["bytes"]) or hashlib.sha256(path.read_bytes()).hexdigest() != pins["sha256"]:
        raise ValueError(f"{path} does not match its pinned size/SHA-256; run make fetch-models")
    return path
