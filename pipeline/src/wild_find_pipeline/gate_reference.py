"""Plant-gate parity reference: checks the bundled export against the Day-1 path, then writes the phone reference."""

import hashlib
import json
import math
import sys

import numpy as np
from PIL import Image

from wild_find_pipeline.assets import plant_share, tinyclip_dir
from wild_find_pipeline.labels import GATE_OTHER, GATE_PLANT
from wild_find_pipeline.paths import GENERATED_ASSETS, REFERENCE_DIR
from wild_find_pipeline.reference import embed_image, image_input


def torch_plant_share(fixture: Image.Image) -> float:
    """The Day-1 measurement path: Hugging Face processor plus CLIPModel logits, softmaxed."""
    import torch
    from transformers import CLIPModel, CLIPProcessor

    local = tinyclip_dir()
    model = CLIPModel.from_pretrained(local).eval()
    processor = CLIPProcessor.from_pretrained(local)
    inputs = processor(text=list(GATE_PLANT + GATE_OTHER), images=fixture, return_tensors="pt", padding=True)
    with torch.no_grad():
        probs = model(**inputs).logits_per_image.softmax(-1)[0].numpy()
    return float(probs[: len(GATE_PLANT)].sum())


def main() -> int:
    """Write plant_gate_reference.json only when the export matches the Day-1 path on the fixture."""
    fixture_png = REFERENCE_DIR / "fixture.png"
    fixture = Image.open(fixture_png).convert("RGB")
    gate = json.loads((GENERATED_ASSETS / "plant_gate.json").read_text())
    vectors = np.array([label["vector"] for label in gate["labels"]])
    plant = np.array([label["plant"] for label in gate["labels"]])

    embedding = embed_image(GENERATED_ASSETS / "plant_gate.onnx", image_input(fixture))
    share = plant_share(embedding, vectors, plant, gate["logit_scale"])
    expected = torch_plant_share(fixture)
    print(f"plant share on the fixture: export {share:.6f}, Day-1 path {expected:.6f}")
    if not math.isfinite(share) or abs(share - expected) > 1e-3 or share <= 0.5:
        print("FAIL: exported plant gate disagrees with the Day-1 path. Reference left untouched.", file=sys.stderr)
        return 1

    reference = {
        "fixture": {"file": fixture_png.name, "sha256": hashlib.sha256(fixture_png.read_bytes()).hexdigest()},
        "image_model": {"file": "plant_gate.onnx"},
        "image_embedding": embedding.astype(float).tolist(),
        "plant_share": share,
    }
    (REFERENCE_DIR / "plant_gate_reference.json").write_text(json.dumps(reference, indent=1) + "\n")
    print("OK: wrote the plant-gate parity reference")
    return 0


if __name__ == "__main__":
    sys.exit(main())
