"""Day-2 probe: can the pinned TinyCLIP text encoder flag a plant as poisonous from its name alone?

Run from the repo root after usda_toxicity.py:

    uv run --project pipeline --group reference python -I docs/results/day-2/tinyclip_poison_names.py

For each 25+ October species, scores its common and scientific names against poisonous vs harmless
prompts, writes tinyclip_poison_names.csv, and prints AUC against three labels: PRD hazards, the
Wikipedia keyword flag, and USDA slight-or-worse.
"""

import csv
import sys
from pathlib import Path

import torch
from transformers import CLIPModel, CLIPProcessor
from wild_find_pipeline.paths import MODEL_CACHE, pin

OUT = Path(__file__).resolve().parent
HAZARD_GENERA = ("Toxicodendron ", "Phytolacca americana", "Solanum carolinense")
PAIRS = {
    "poisonous/harmless": ("a poisonous plant", "a harmless plant"),
    "toxic/safe": ("a toxic plant", "a safe plant"),
    "poisonous/edible": ("a poisonous plant", "an edible plant"),
}
NAMES = {"common": "a photo of {common}", "scientific": "a photo of {name}"}


def auc(positive: list[float], negative: list[float]) -> float:
    """Probability a random positive outscores a random negative (ties count half)."""
    wins = sum((p > n) + 0.5 * (p == n) for p in positive for n in negative)
    return wins / (len(positive) * len(negative))


def embed(model, proc, texts: list[str]) -> torch.Tensor:
    """Unit TinyCLIP text vectors."""
    with torch.no_grad():
        tokens = proc(text=texts, return_tensors="pt", padding=True)
        v = model.text_projection(model.text_model(**tokens).pooler_output)
    return v / v.norm(dim=-1, keepdim=True)


def main() -> int:
    """Score every species name and report AUC per prompt pair, name form, and label."""
    tiny = pin("tinyclip")
    model = CLIPModel.from_pretrained(tiny["repo"], revision=tiny["revision"], cache_dir=MODEL_CACHE / "hf").eval()
    proc = CLIPProcessor.from_pretrained(tiny["repo"], revision=tiny["revision"], cache_dir=MODEL_CACHE / "hf")
    scale = float(model.logit_scale.detach().exp())
    species = list(csv.DictReader((OUT / "usda_toxicity.csv").open()))
    labels = {
        "hazard": [s["name"].startswith(HAZARD_GENERA) for s in species],
        "wikipedia": [s["wikipedia_toxic"] == "True" for s in species],
        "usda": [s["usda"] in ("slight", "moderate", "severe") for s in species],
    }
    rows = [{"name": s["name"], "common": s["common"], **{k: v[i] for k, v in labels.items()}} for i, s in enumerate(species)]
    for form, template in NAMES.items():
        names = embed(model, proc, [template.format(**s) for s in species])
        for pair, (bad, good) in PAIRS.items():
            prompts = embed(model, proc, [bad, good])
            p_bad = (scale * names @ prompts.T).softmax(-1)[:, 0].tolist()
            for row, p in zip(rows, p_bad, strict=True):
                row[f"{form}:{pair}"] = round(p, 6)
    with (OUT / "tinyclip_poison_names.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    print(f"{tiny['repo']} @ {tiny['revision'][:7]}, {len(rows)} species; " + ", ".join(f"{k} {sum(v)}" for k, v in labels.items()))
    for column in [c for c in rows[0] if ":" in c]:
        aucs = []
        for label in labels:
            pos = [r[column] for r in rows if r[label]]
            neg = [r[column] for r in rows if not r[label]]
            aucs.append(f"{label} {auc(pos, neg):.3f}")
        top = sorted(rows, key=lambda r: -r[column])[:8]
        print(f"{column}: AUC " + ", ".join(aucs) + " | top: " + ", ".join(r["common"] for r in top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
