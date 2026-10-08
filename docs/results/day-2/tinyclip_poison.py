"""Day-2 probe: can the shipped TinyCLIP plant gate tell poisonous plants from others by prompt alone?

Run from the repo root after Day 1's photos are cached (docs/results/day-1/run_experiments.py downloads them):

    uv run --project pipeline --group reference python -I docs/results/day-2/tinyclip_poison.py

Scores every Day-1 plant photo (full frame) against poisonous vs harmless prompt pairs with the pinned
TinyCLIP ViT-8M, writes tinyclip_poison.csv, and prints the hazard-vs-other AUC per prompt pair.
"""

import csv
import hashlib
import re
import sys
from pathlib import Path

import torch
from PIL import Image
from transformers import CLIPModel, CLIPProcessor
from wild_find_pipeline.paths import MODEL_CACHE, pin

OUT = Path(__file__).resolve().parent
MANIFEST = OUT.parent / "day-1-photos.tsv"
PHOTOS = MODEL_CACHE / "day1-photos"
# The PRD hazard list; every other Day-1 plant photo is the comparison set.
HAZARD_SUBJECTS = {"poison_ivy", "poisonivy", "poison_oak", "poison_sumac", "pokeweed", "horsenettle"}
PAIRS = {
    "poisonous/harmless": ("a photo of a poisonous plant", "a photo of a harmless plant"),
    "toxic/safe": ("a photo of a toxic plant", "a photo of a safe plant"),
    "poisonous/edible": ("a photo of a poisonous plant", "a photo of an edible plant"),
}


def auc(positive: list[float], negative: list[float]) -> float:
    """Probability a random hazard photo outscores a random other photo (ties count half)."""
    wins = sum((p > n) + 0.5 * (p == n) for p in positive for n in negative)
    return wins / (len(positive) * len(negative))


def main() -> int:
    """Score every plant photo and report AUC per prompt pair."""
    tiny = pin("tinyclip")
    model = CLIPModel.from_pretrained(tiny["repo"], revision=tiny["revision"], cache_dir=MODEL_CACHE / "hf").eval()
    proc = CLIPProcessor.from_pretrained(tiny["repo"], revision=tiny["revision"], cache_dir=MODEL_CACHE / "hf")
    texts = [t for pair in PAIRS.values() for t in pair]
    text_inputs = proc(text=texts, return_tensors="pt", padding=True)
    rows = []
    for row in csv.DictReader(MANIFEST.open(), delimiter="\t"):
        if row["set"] != "plant":
            continue
        path = PHOTOS / row["file"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != row["sha256"]:
            raise ValueError(f"{path} does not match its manifest SHA-256")
        subject = re.sub(r"_\d+\.\w+$", "", row["file"])
        with torch.no_grad():
            pixels = proc(images=Image.open(path).convert("RGB"), return_tensors="pt")["pixel_values"]
            logits = model(**text_inputs, pixel_values=pixels).logits_per_image[0]
        out = {"photo": row["file"], "subject": subject, "hazard": subject in HAZARD_SUBJECTS}
        for i, name in enumerate(PAIRS):
            out[name] = round(float(logits[2 * i : 2 * i + 2].softmax(-1)[0]), 6)
        rows.append(out)
    with (OUT / "tinyclip_poison.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    hazards = [r for r in rows if r["hazard"]]
    others = [r for r in rows if not r["hazard"]]
    print(f"{tiny['repo']} @ {tiny['revision'][:7]}: {len(hazards)} hazard photos, {len(others)} other plant photos")
    for name in PAIRS:
        print(f"{name}: AUC {auc([r[name] for r in hazards], [r[name] for r in others]):.3f} (0.5 = coin flip)")
        by_subject = {}
        for r in rows:
            by_subject.setdefault(r["subject"], []).append(r[name])
        ranked = sorted(by_subject.items(), key=lambda kv: -sum(kv[1]) / len(kv[1]))
        print("  mean P(poisonous) by subject: " + ", ".join(f"{s} {sum(v) / len(v):.2f}" for s, v in ranked))
    return 0


if __name__ == "__main__":
    sys.exit(main())
