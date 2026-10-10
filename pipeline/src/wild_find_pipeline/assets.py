"""Bundled APK assets: BioCLIP Mobile, the species table with its flags, the TinyCLIP plant gate, and the world map.

Writes into the gitignored app/generated/assets. Needs no BioCLIP teacher: appended hazard rows come from the
committed hazard_vectors.json (make hazard-vectors), toxicity flags from toxicity.json (make toxicity), name
aliases from synonyms.json (make synonyms), plant types from plant_types.json (make plant-types), and kid-level
descriptions from descriptions.json (make descriptions), contact hazards and their card lines from
contact_hazards.json and its review file (make contact-hazards), "where to look" hints from hints.json (make hints),
and the hand-kept no-target rows from no_target.json, so CI can run it. Also checks the committed labels.npy and
labels.json (make labels) against the current label lists and pins.
"""

import hashlib
import json
import os
import shutil
import sys
import tempfile
from collections.abc import Callable
from pathlib import Path
from typing import Any

import numpy as np

from wild_find_pipeline import contact_hazards, label_vectors, places, world_map
from wild_find_pipeline.hint_traits import YEAR
from wild_find_pipeline.labels import (
    GATE_OTHER,
    GATE_PLANT,
    embedding_versions,
    is_hazard,
    lacking_hazards,
    prompt,
    taxa_names,
)
from wild_find_pipeline.paths import (
    CONTACT_HAZARDS,
    CONTACT_REVIEW,
    DESCRIPTIONS,
    GENERATED_ASSETS,
    GENERATED_STAMP,
    HAZARD_VECTORS,
    HINTS,
    LABELS_DIR,
    MANIFEST,
    MODEL_CACHE,
    NO_TARGET,
    PLANT_TYPES,
    REPO,
    SYNONYMS,
    TOXICITY,
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
    # The table and its labels are pinned separately; a mismatched pair would shift every name onto the wrong row.
    if table.shape[0] != len(names):
        raise ValueError(f"species table has {table.shape[0]} rows but {len(names)} labels")
    if extra:
        table = np.vstack([table, np.stack(list(extra.values()))])
    labels = [{"scientific": name, "hazard": is_hazard(name)} for name in [*names, *extra]]
    return table.astype(np.float32), labels


def merged(labels: list[dict], found: dict, source: Path, step: str, add: Callable[[dict, Any], dict]) -> list[dict]:
    """Each row with add(row, its committed entry) merged in; raises when a row has no entry in [source]."""
    missing = [entry["scientific"] for entry in labels if entry["scientific"] not in found]
    if missing:
        raise ValueError(f"{source.name} lacks {len(missing)} species, e.g. {missing[:3]}; run make {step}")
    return [{**entry, **add(entry, found[entry["scientific"]])} for entry in labels]


def with_toxicity(labels: list[dict], flags: dict[str, dict]) -> list[dict]:
    """Add each row's genus and committed toxicity flag; raises when a row has no flag."""
    return merged(
        labels,
        flags,
        TOXICITY,
        "toxicity",
        lambda entry, found: {"genus": entry["scientific"].split()[0], "toxic": found["toxic"]},
    )


def with_contact_hazards(labels: list[dict], data: dict, review: dict) -> list[dict]:
    """Mark shipped contact-hazard rows as hazards, flag the fixed-rule ones as the floor, and add each card line.

    `hazard_floor` rows warn everywhere; other hazards warn only where the region's sightings include them. A row with
    no card line gets None, and the card falls back to generic text.

    Raises when the file is a partial checkpoint or lacks a row.
    """
    shipped = contact_hazards.listed(data, review)
    return merged(
        labels,
        data["species"],
        CONTACT_HAZARDS,
        "contact-hazards",
        lambda entry, _: {
            "hazard": entry["hazard"] or entry["scientific"] in shipped,
            "hazard_floor": is_hazard(entry["scientific"]),
            "hazard_line": shipped.get(entry["scientific"]),
        },
    )


def with_synonyms(labels: list[dict], aliases: dict[str, list[str]]) -> list[dict]:
    """Add each row's committed GBIF aliases; raises when a row has no entry."""
    return merged(labels, aliases, SYNONYMS, "synonyms", lambda _, found: {"synonyms": found})


def with_plant_types(labels: list[dict], types: dict[str, dict]) -> list[dict]:
    """Add each row's committed plant type (a PlantType key or None); raises when a row has no entry."""
    return merged(labels, types, PLANT_TYPES, "plant-types", lambda _, found: {"type": found["type"]})


def with_descriptions(labels: list[dict], found: dict[str, dict]) -> list[dict]:
    """Add each row's committed kid-level description, or None; raises when a row has no entry."""
    return merged(labels, found, DESCRIPTIONS, "descriptions", lambda _, entry: {"description": entry["description"]})


def with_targets(labels: list[dict], data: dict) -> list[dict]:
    """Add each row's `target` flag: false for the rows no_target.json lists, true for the rest.

    Raises when the file names a species the table lacks, so a typo can't leave a row pickable unnoticed.
    """
    names = {entry["scientific"] for entry in labels}
    if unknown := sorted(set(data["species"]) - names):
        raise ValueError(f"{NO_TARGET.name} names {unknown}, not in the species table")
    return [{**entry, "target": entry["scientific"] not in data["species"]} for entry in labels]


def with_hints(labels: list[dict], data: dict) -> list[dict]:
    """Add each row's shipped hints: the picked ones that failed no check, best first, `season` only on season hints.

    Toxic and hazard rows get none, even when the file holds hints from before a row joined the contact list. Raises
    when the file is a partial checkpoint, when a playable row has no entry, or when a season hint carries a name the
    app does not know.
    """
    if not data["done"]:
        raise ValueError(f"{HINTS.name} is a partial checkpoint; run make hints to finish it")
    found = data["species"]
    playable = [e["scientific"] for e in labels if not e["toxic"] and not e["hazard"]]
    if missing := [row for row in playable if row not in found]:
        raise ValueError(f"{HINTS.name} lacks {len(missing)} playable species, e.g. {missing[:3]}; run make hints")
    out = []
    for entry in labels:
        if entry["toxic"] or entry["hazard"]:
            out.append({**entry, "hints": []})
            continue
        picked = [h for h in found[entry["scientific"]]["hints"] if h.get("score") and not h["issues"]]
        bad = [h["bucket"] for h in picked if h["aspect"] == "season" and h["bucket"] not in YEAR]
        if bad:
            raise ValueError(f"{entry['scientific']} has season hints named {bad}, not one of {YEAR}")
        shipped = [
            {"text": h["text"], **({"season": h["bucket"]} if h["aspect"] == "season" else {})}
            for h in sorted(picked, key=lambda h: -h["score"])
        ]
        out.append({**entry, "hints": shipped})
    return out


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
    TOXICITY,
    SYNONYMS,
    PLANT_TYPES,
    DESCRIPTIONS,
    HINTS,
    CONTACT_HAZARDS,
    CONTACT_REVIEW,
    NO_TARGET,
    REPO / "pipeline/uv.lock",
    LABELS_DIR / "labels.json",
    LABELS_DIR / "labels.npy",
    *(
        REPO / "pipeline/src/wild_find_pipeline" / name
        for name in (
            "assets.py",
            "contact_hazards.py",
            "labels.py",
            "label_vectors.py",
            "paths.py",
            "places.py",
            "world_map.py",
        )
    ),
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
    label_vectors.check_committed()
    with tempfile.TemporaryDirectory() as tmp:
        staging = Path(tmp)

        bioclip = ensure_artifact("bioclip")
        shutil.copyfile(bioclip, staging / bioclip.name)

        names = taxa_names()
        extra = hazard_vectors(names)
        table, labels = species_table(np.load(ensure_artifact("taxa")), names, extra)
        labels = with_contact_hazards(
            labels, json.loads(CONTACT_HAZARDS.read_text()), json.loads(CONTACT_REVIEW.read_text())
        )
        labels = with_toxicity(labels, json.loads(TOXICITY.read_text())["species"])
        labels = with_synonyms(labels, json.loads(SYNONYMS.read_text())["species"])
        labels = with_plant_types(labels, json.loads(PLANT_TYPES.read_text())["species"])
        labels = with_descriptions(labels, json.loads(DESCRIPTIONS.read_text())["species"])
        labels = with_hints(labels, json.loads(HINTS.read_text()))
        labels = with_targets(labels, json.loads(NO_TARGET.read_text()))
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
        map_bytes = world_map.write(staging / "map.bin")
        place_bytes = places.write(staging / "places.bin")

        publish(staging)
    hazards = sum(entry["hazard"] for entry in labels)
    lines = sum(entry["hazard_line"] is not None for entry in labels)
    toxic = sum(entry["toxic"] for entry in labels)
    held = sum(not entry["target"] for entry in labels)
    print(
        f"OK: {GENERATED_ASSETS}: {len(labels)} species ({hazards} hazards, {lines} with a card line, {toxic} toxic, "
        f"{held} never targets, appended {list(extra)}), "
        f"scale {scale:.4f}, map {map_bytes} bytes, places {place_bytes} bytes"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
