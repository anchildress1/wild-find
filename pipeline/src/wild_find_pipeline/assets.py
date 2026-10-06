"""Bundled APK assets: BioCLIP Mobile, the species table with hazard flags, and the TinyCLIP plant gate.

Writes into the gitignored app/generated/assets. Needs no BioCLIP teacher: appended hazard rows come from the
committed hazard_vectors.json (make hazard-vectors), so CI can run it.
"""

import hashlib
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np

from wild_find_pipeline.labels import (
    GATE_OTHER,
    GATE_PLANT,
    embedding_versions,
    is_hazard,
    lacking_hazards,
    prompt,
)
from wild_find_pipeline.paths import (
    GENERATED_ASSETS,
    GENERATED_STAMP,
    HAZARD_VECTORS,
    MANIFEST,
    MODEL_CACHE,
    REPO,
    ensure_artifact,
    file_sha256,
    pin,
)

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
    stored_file = json.loads(HAZARD_VECTORS.read_text())
    teacher = pin("teacher")
    # Rows from another teacher revision, or picked against another species list, embed in a different
    # space or miss a hazard, and nothing downstream would notice.
    if (
        stored_file["text_model"] != {"repo": teacher["repo"], "revision": teacher["revision"]}
        or stored_file.get("taxa_labels_sha256") != pin("taxa_labels")["sha256"]
    ):
        raise ValueError(f"{HAZARD_VECTORS.name} predates the current teacher or taxa pins; run make hazard-vectors")
    if stored_file.get("packages") != embedding_versions():
        raise ValueError(f"{HAZARD_VECTORS.name} came from other embedding package versions; run make hazard-vectors")
    stored = stored_file["species"]
    lacking = lacking_hazards(names)
    prompts = stored_file.get("prompts", {})
    if changed := [taxon for taxon in lacking if prompts.get(taxon) != prompt(taxon)]:
        raise ValueError(f"the label prompt changed for {changed}; run make hazard-vectors")
    if missing := [taxon for taxon in lacking if taxon not in stored]:
        raise ValueError(f"no stored vector for {missing}; run make hazard-vectors")
    return {taxon: np.array(stored[taxon], dtype=np.float32) for taxon in lacking}


# Everything whose change alters the bundled assets; the stamp records their SHA-256 for the Gradle check.
INPUTS = (
    MANIFEST,
    HAZARD_VECTORS,
    REPO / "pipeline/uv.lock",
    *(REPO / "pipeline/src/wild_find_pipeline" / name for name in ("assets.py", "labels.py", "paths.py")),
)


def publish(staging: Path, target: Path = GENERATED_ASSETS, stamp: Path = GENERATED_STAMP) -> None:
    """Swap staging in for target by rename, then write the input stamp last, so a crash never leaves a stamped mix."""
    stamp.unlink(missing_ok=True)
    fresh, old = target.with_name(target.name + ".new"), target.with_name(target.name + ".old")
    for leftover in (fresh, old):
        shutil.rmtree(leftover, ignore_errors=True)
    shutil.copytree(staging, fresh)
    if target.exists():
        target.rename(old)
    fresh.rename(target)
    shutil.rmtree(old, ignore_errors=True)
    hashes = {path.relative_to(REPO).as_posix(): file_sha256(path) for path in INPUTS}
    pending = stamp.with_suffix(".tmp")
    pending.write_text(json.dumps(hashes, indent=1, sort_keys=True) + "\n")
    os.replace(pending, stamp)


def main() -> int:
    """Build every bundled asset into a temp dir, then swap it into app/generated/assets and stamp its inputs."""
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

        publish(staging)
    hazards = sum(entry["hazard"] for entry in labels)
    print(
        f"OK: {GENERATED_ASSETS}: {len(labels)} species ({hazards} hazards, appended {list(extra)}), scale {scale:.4f}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
