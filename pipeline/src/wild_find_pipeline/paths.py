"""Repo-relative paths shared by pipeline steps."""

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
