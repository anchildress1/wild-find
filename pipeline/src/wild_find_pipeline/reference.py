"""Day-1 reference: one fixture image embedding plus label text embeddings for the Android parity check."""

import hashlib
import io
import json
import sys
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image

from wild_find_pipeline.labels import HAZARDS, SCENES, prompt
from wild_find_pipeline.paths import MODEL_CACHE, REFERENCE_DIR, pin

SIZE = 224
FIXTURE_WORD = "oak"
FIXTURE_TAXON = "Quercus"
FIXTURE_URL = "https://inaturalist-open-data.s3.amazonaws.com/photos/664105168/medium.jpg"
FIXTURE_SOURCE = "https://www.inaturalist.org/observations/363799243 (CC0)"
TEACHER_REPO = "imageomics/bioclip-2.5-vith14"
TEACHER_REVISION = "6e3d04e3d6522012c88181085c5ae666e14c45cd"
USER_AGENT = "wild-find-pipeline/0.1 (+https://github.com/anchildress1/wild-find)"


def square_fixture(img: Image.Image) -> Image.Image:
    """Center-crop to a square and resize to the model's input size."""
    img = img.convert("RGB")
    side = min(img.size)
    left, top = (img.width - side) // 2, (img.height - side) // 2
    return img.crop((left, top, left + side, top + side)).resize((SIZE, SIZE), Image.Resampling.BICUBIC)


def image_input(img: Image.Image) -> np.ndarray:
    """RGB 224x224 image → [1, 3, 224, 224] float32 in 0..1; the ONNX graph applies normalization itself."""
    if img.mode != "RGB" or img.size != (SIZE, SIZE):
        raise ValueError(f"expected RGB {SIZE}x{SIZE}, got {img.mode} {img.size}")
    return (np.asarray(img, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]


def ranked(scores: dict[str, float]) -> list[tuple[str, float]]:
    return sorted(scores.items(), key=lambda kv: kv[1], reverse=True)


def embed_image(onnx_path: Path, x: np.ndarray) -> np.ndarray:
    import onnxruntime as ort

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    return session.run(None, {"image": x})[0][0]


def embed_texts(texts: list[str]) -> np.ndarray:
    import open_clip
    import torch
    from huggingface_hub import snapshot_download

    local = snapshot_download(
        TEACHER_REPO,
        revision=TEACHER_REVISION,
        cache_dir=MODEL_CACHE / "hf",
        allow_patterns=["open_clip_config.json", "open_clip_model.safetensors", "*.txt", "tokenizer*", "vocab.json"],
    )
    model, _ = open_clip.create_model_from_pretrained(f"local-dir:{local}")
    tokenizer = open_clip.get_tokenizer(f"local-dir:{local}")
    model.eval()
    with torch.no_grad():
        vectors = model.encode_text(tokenizer(texts))
    return torch.nn.functional.normalize(vectors, dim=-1).numpy()


def main() -> int:
    bioclip = pin("bioclip")
    request = urllib.request.Request(FIXTURE_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        fixture = square_fixture(Image.open(io.BytesIO(response.read())))

    REFERENCE_DIR.mkdir(parents=True, exist_ok=True)
    fixture_path = REFERENCE_DIR / "fixture.png"
    fixture.save(fixture_path)
    # Embed the saved lossless PNG so Android reads byte-identical pixels.
    image_vector = embed_image(MODEL_CACHE / bioclip["file"], image_input(Image.open(fixture_path)))

    labels = [
        (FIXTURE_WORD, "word", FIXTURE_TAXON),
        *((name, "hazard", taxon) for name, taxon in HAZARDS.items()),
        *((scene, "scene", scene) for scene in SCENES),
    ]
    text_vectors = embed_texts([prompt(text) for _, _, text in labels])
    scores = {label: float(text_vectors[i] @ image_vector) for i, (label, _, _) in enumerate(labels)}

    reference = {
        "fixture": {
            "file": fixture_path.name,
            "sha256": hashlib.sha256(fixture_path.read_bytes()).hexdigest(),
            "source": FIXTURE_SOURCE,
            "word": FIXTURE_WORD,
        },
        "image_model": {"file": bioclip["file"], "sha256": bioclip["sha256"]},
        "text_model": {"repo": TEACHER_REPO, "revision": TEACHER_REVISION},
        "image_embedding": image_vector.astype(float).tolist(),
        "labels": [
            {"id": label, "kind": kind, "text": prompt(text), "vector": text_vectors[i].astype(float).tolist()}
            for i, (label, kind, text) in enumerate(labels)
        ],
        "scores": scores,
    }
    (REFERENCE_DIR / "reference.json").write_text(json.dumps(reference, indent=1) + "\n")

    order = ranked(scores)
    for label, score in order:
        print(f"{score:.4f}  {label}")
    (top, top_score), (runner_up, runner_score) = order[0], order[1]
    if top != FIXTURE_WORD:
        print(f"FAIL: top-1 is {top!r}, expected {FIXTURE_WORD!r}", file=sys.stderr)
        return 1
    print(f"OK: {FIXTURE_WORD!r} is top-1, margin {top_score - runner_score:.4f} over {runner_up!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
