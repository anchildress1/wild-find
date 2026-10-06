"""Bundled APK assets: BioCLIP Mobile, the species table with hazard flags, and the TinyCLIP plant gate.

Writes into the gitignored app/generated/assets. Needs no BioCLIP teacher: appended hazard rows come from the
committed hazard_vectors.json (make hazard-vectors), so CI can run it.
"""

import hashlib
import json
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np

from wild_find_pipeline.labels import GATE_OTHER, GATE_PLANT, is_hazard, lacking_hazards
from wild_find_pipeline.paths import GENERATED_ASSETS, HAZARD_VECTORS, MODEL_CACHE, ensure_artifact, pin

# CLIP's ImageNet-style normalization, baked into plant_gate.onnx so the phone feeds plain 0..1 RGB like BioCLIP.
CLIP_MEAN = (0.48145466, 0.4578275, 0.40821073)
CLIP_STD = (0.26862954, 0.26130258, 0.27577711)


def plant_share(embedding: np.ndarray, vectors: np.ndarray, plant: np.ndarray, scale: float) -> float:
    """Combined softmax probability of the plant labels over scale x cosine; a frame is a plant above 0.5."""
    logits = scale * (vectors @ embedding)
    probs = np.exp(logits - logits.max())
    probs /= probs.sum()
    return float(probs[plant].sum())


def species_table(table: np.ndarray, names: list[str], extra: dict[str, np.ndarray]) -> tuple[np.ndarray, list[dict]]:
    """Append the [extra] rows after the table's own; return the table and per-row labels with hazard flags."""
    if extra:
        table = np.vstack([table, np.stack(list(extra.values()))])
    labels = [{"scientific": name, "hazard": is_hazard(name)} for name in [*names, *extra]]
    return table.astype(np.float32), labels


def tinyclip_dir() -> Path:
    """Download the pinned TinyCLIP snapshot and check its weights against the pinned size and SHA-256."""
    from huggingface_hub import snapshot_download

    pins = pin("tinyclip")
    local = Path(
        snapshot_download(
            pins["repo"],
            revision=pins["revision"],
            cache_dir=MODEL_CACHE / "hf",
            allow_patterns=["config.json", "preprocessor_config.json", "tokenizer.json", pins["file"]],
        )
    )
    weights = (local / pins["file"]).read_bytes()
    if str(len(weights)) != pins["bytes"] or hashlib.sha256(weights).hexdigest() != pins["sha256"]:
        raise ValueError(f"{pins['repo']} {pins['file']} does not match its pinned size/SHA-256")
    return local


def export_plant_gate(local: Path, out: Path) -> tuple[np.ndarray, float]:
    """Export TinyCLIP's image encoder to fp32 ONNX; return the gate text vectors and the learned logit scale."""
    import torch
    from transformers import CLIPModel, CLIPTokenizerFast

    model = CLIPModel.from_pretrained(local).eval()

    class ImageEncoder(torch.nn.Module):
        """0..1 RGB in, unit image embedding out."""

        def __init__(self):
            super().__init__()
            self.register_buffer("mean", torch.tensor(CLIP_MEAN).view(1, 3, 1, 1))
            self.register_buffer("std", torch.tensor(CLIP_STD).view(1, 3, 1, 1))

        def forward(self, image):
            pooled = model.vision_model(pixel_values=(image - self.mean) / self.std).pooler_output
            features = model.visual_projection(pooled)
            return torch.nn.functional.normalize(features, dim=-1)

    torch.onnx.export(
        ImageEncoder().eval(),
        (torch.zeros(1, 3, 224, 224),),
        str(out),
        input_names=["image"],
        output_names=["unit_embedding"],
        dynamo=True,
        external_data=False,
    )
    tokenizer = CLIPTokenizerFast.from_pretrained(local)
    with torch.no_grad():
        tokens = tokenizer(list(GATE_PLANT + GATE_OTHER), padding=True, return_tensors="pt")
        text = model.text_projection(model.text_model(**tokens).pooler_output)
    vectors = torch.nn.functional.normalize(text, dim=-1).numpy()
    return vectors, float(model.logit_scale.detach().exp())


def hazard_vectors(names: list[str]) -> dict[str, np.ndarray]:
    """Committed teacher vectors for every hazard species the pinned table lacks; raises when one is missing."""
    stored = json.loads(HAZARD_VECTORS.read_text())["species"]
    lacking = lacking_hazards(names)
    if missing := [taxon for taxon in lacking if taxon not in stored]:
        raise ValueError(f"no stored vector for {missing}; run make hazard-vectors")
    return {taxon: np.array(stored[taxon], dtype=np.float32) for taxon in lacking}


def main() -> int:
    """Build every bundled asset into a temp dir, then replace app/generated/assets in one step."""
    with tempfile.TemporaryDirectory() as tmp:
        staging = Path(tmp)

        bioclip = ensure_artifact("bioclip")
        shutil.copyfile(bioclip, staging / bioclip.name)

        names = [entry["scientific"] for entry in json.loads(ensure_artifact("taxa_labels").read_text())]
        extra = hazard_vectors(names)
        table, labels = species_table(np.load(ensure_artifact("taxa")), names, extra)
        np.save(staging / "species_table.npy", table)
        (staging / "species_labels.json").write_text(json.dumps(labels, indent=1) + "\n")

        vectors, scale = export_plant_gate(tinyclip_dir(), staging / "plant_gate.onnx")
        plant = [True] * len(GATE_PLANT) + [False] * len(GATE_OTHER)
        gate = {
            "logit_scale": scale,
            "labels": [
                {"text": text, "plant": is_plant, "vector": vector.astype(float).tolist()}
                for text, is_plant, vector in zip(GATE_PLANT + GATE_OTHER, plant, vectors, strict=True)
            ],
        }
        (staging / "plant_gate.json").write_text(json.dumps(gate) + "\n")

        if GENERATED_ASSETS.exists():
            shutil.rmtree(GENERATED_ASSETS)
        shutil.copytree(staging, GENERATED_ASSETS)
    hazards = sum(entry["hazard"] for entry in labels)
    print(
        f"OK: {GENERATED_ASSETS}: {len(labels)} species ({hazards} hazards, appended {list(extra)}), scale {scale:.4f}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
