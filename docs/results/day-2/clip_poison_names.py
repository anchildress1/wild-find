"""Day-2 probe: do bigger CLIP text encoders flag poisonous plants from their names? TinyCLIP 8M/39M/40M and BioCLIP 2.5.

Run from the repo root after usda_toxicity.py:

    uv run --project pipeline --group reference python -I docs/results/day-2/clip_poison_names.py

Score per name = cosine to the poisonous prompt minus cosine to the harmless one (AUC ignores the logit
scale, so no softmax). Writes clip_poison_names.csv and prints AUC per model, name form, prompt pair,
and label (PRD hazards, Wikipedia keyword flag, USDA slight-or-worse).
"""

import csv
import sys
from pathlib import Path

import numpy as np
import torch
from transformers import CLIPModel, CLIPProcessor
from wild_find_pipeline.paths import MODEL_CACHE
from wild_find_pipeline.reference import embed_texts

OUT = Path(__file__).resolve().parent
# Same revisions Day 1 pinned for the gate comparison.
TINYCLIP = {
    "tinyclip-8m": ("wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M", "a2a8c6eaa2549ad66eb7c31b85022bf58273a26c"),
    "tinyclip-39m": ("wkcn/TinyCLIP-ViT-39M-16-Text-19M-YFCC15M", "07a4b0bc751cb64fecd2b661c048c1dd98d69444"),
    "tinyclip-40m": ("wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M", "95ec8197b3f2fe7f747865c61ca556cf0768b2f7"),
}
HAZARD_GENERA = ("Toxicodendron ", "Phytolacca americana", "Solanum carolinense")
PAIRS = {
    "poisonous/harmless": ("a photo of a poisonous plant.", "a photo of a harmless plant."),
    "toxic/safe": ("a photo of a toxic plant.", "a photo of a safe plant."),
    "poisonous/edible": ("a photo of a poisonous plant.", "a photo of an edible plant."),
}
NAMES = {"common": "a photo of {common}.", "scientific": "a photo of {name}."}


def auc(positive: list[float], negative: list[float]) -> float:
    """Probability a random positive outscores a random negative (ties count half)."""
    wins = sum((p > n) + 0.5 * (p == n) for p in positive for n in negative)
    return wins / (len(positive) * len(negative))


def tinyclip_encoder(repo: str, revision: str):
    """Text encoder returning unit vectors for one TinyCLIP checkpoint."""
    model = CLIPModel.from_pretrained(repo, revision=revision, cache_dir=MODEL_CACHE / "hf").eval()
    proc = CLIPProcessor.from_pretrained(repo, revision=revision, cache_dir=MODEL_CACHE / "hf")

    def encode(texts: list[str]) -> np.ndarray:
        with torch.no_grad():
            tokens = proc(text=texts, return_tensors="pt", padding=True)
            v = model.text_projection(model.text_model(**tokens).pooler_output)
        return (v / v.norm(dim=-1, keepdim=True)).numpy()

    return encode


def main() -> int:
    """Score every species name with every model and report AUCs."""
    species = list(csv.DictReader((OUT / "usda_toxicity.csv").open()))
    labels = {
        "hazard": [s["name"].startswith(HAZARD_GENERA) for s in species],
        "wikipedia": [s["wikipedia_toxic"] == "True" for s in species],
        "usda": [s["usda"] in ("slight", "moderate", "severe") for s in species],
    }
    rows = [{"name": s["name"], "common": s["common"], **{k: v[i] for k, v in labels.items()}}
            for i, s in enumerate(species)]  # fmt: skip
    encoders = {name: tinyclip_encoder(*pin) for name, pin in TINYCLIP.items()}
    encoders["bioclip-2.5-vith14"] = embed_texts
    print(f"{len(rows)} species; " + ", ".join(f"{k} {sum(v)}" for k, v in labels.items()))
    for model, encode in encoders.items():
        for form, template in NAMES.items():
            names = encode([template.format(**s) for s in species])
            for pair, (bad, good) in PAIRS.items():
                prompts = encode([bad, good])
                score = (names @ prompts.T) @ np.array([1.0, -1.0])
                column = f"{model}:{form}:{pair}"
                for row, value in zip(rows, score.tolist(), strict=True):
                    row[column] = round(value, 6)
                aucs = []
                for label in labels:
                    pos = [r[column] for r in rows if r[label]]
                    neg = [r[column] for r in rows if not r[label]]
                    aucs.append(f"{label} {auc(pos, neg):.3f}")
                top = sorted(rows, key=lambda r: -r[column])[:6]
                print(f"{column}: AUC " + ", ".join(aucs) + " | top: " + ", ".join(r["common"] for r in top))
    with (OUT / "clip_poison_names.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
