"""Regenerate every Day-1 laptop experiment as raw CSVs.

Run from the repo root (downloads photos listed in docs/results/day-1-photos.tsv into .models/day1-photos):

    uv run --project pipeline --group reference --with transformers python docs/results/day-1/run_experiments.py

BioCLIP scores here equal the phone's: S03 measured phone-vs-laptop cosine 0.9999999988.
"""

import csv
import urllib.request
from pathlib import Path

import numpy as np
import open_clip
import torch
from huggingface_hub import snapshot_download
from PIL import Image
from transformers import CLIPModel, CLIPProcessor
from wild_find_pipeline.labels import HAZARDS, SCENES, prompt
from wild_find_pipeline.paths import MODEL_CACHE, REFERENCE_DIR, pin, verified_artifact
from wild_find_pipeline.reference import embed_image, image_input, square_fixture

OUT = Path(__file__).parent
MANIFEST = OUT.parent / "day-1-photos.tsv"
PHOTOS = MODEL_CACHE / "day1-photos"
USER_AGENT = "wild-find-pipeline/0.1 (+https://github.com/anchildress1/wild-find)"

PLANTS = {
    "grass": "Poaceae",
    "oak": "Quercus",
    "fern": "Polypodiopsida",
    "clover": "Trifolium",
    "pine": "Pinus",
    "dandelion": "Taraxacum",
}
TINYCLIP_MODELS = {
    "wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M": "a2a8c6eaa2549ad66eb7c31b85022bf58273a26c",
    "wkcn/TinyCLIP-ViT-39M-16-Text-19M-YFCC15M": None,
    "wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M": None,
}
GATE_PLANT = [f"a photo of {x}" for x in ("a plant", "leaves", "a tree", "grass", "a flower", "moss", "a fern")]
GATE_OTHER = [
    f"a photo of {x}"
    for x in (
        "a person",
        "a child",
        "a screen",
        "a phone",
        "a road",
        "a sidewalk",
        "a car",
        "a dog",
        "a room",
        "a building",
    )
]

# Label-format experiment: (common, scientific, taxonomic string) per label.
FORMAT_LABELS = {
    "oak": (
        "oak",
        "Quercus",
        "Plantae Tracheophyta Magnoliopsida Fagales Fagaceae Quercus",
    ),
    "poison oak": (
        "poison oak",
        "Toxicodendron pubescens",
        "Plantae Tracheophyta Magnoliopsida Sapindales Anacardiaceae Toxicodendron pubescens",
    ),
    "poison ivy": (
        "poison ivy",
        "Toxicodendron radicans",
        "Plantae Tracheophyta Magnoliopsida Sapindales Anacardiaceae Toxicodendron radicans",
    ),
    "poison sumac": (
        "poison sumac",
        "Toxicodendron vernix",
        "Plantae Tracheophyta Magnoliopsida Sapindales Anacardiaceae Toxicodendron vernix",
    ),
    "pokeweed": (
        "pokeweed",
        "Phytolacca americana",
        "Plantae Tracheophyta Magnoliopsida Caryophyllales Phytolaccaceae Phytolacca americana",
    ),
    "Carolina horsenettle": (
        "Carolina horsenettle",
        "Solanum carolinense",
        "Plantae Tracheophyta Magnoliopsida Solanales Solanaceae Solanum carolinense",
    ),
}
FORMATS = {
    "common": lambda c, s, t: f"a photo of {c}.",
    "scientific": lambda c, s, t: f"a photo of {s}.",
    "taxonomic": lambda c, s, t: f"a photo of {t}.",
    "taxonomic+common": lambda c, s, t: f"a photo of {t} with common name {c}.",
}


def photos() -> list[dict[str, str]]:
    """Download any manifest photos missing from the cache and return the manifest rows."""
    PHOTOS.mkdir(parents=True, exist_ok=True)
    rows = list(csv.DictReader(MANIFEST.open(), delimiter="\t"))
    for row in rows:
        path = PHOTOS / row["file"]
        if not path.exists():
            request = urllib.request.Request(row["photo_url"], headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=30) as response:
                path.write_bytes(response.read())
    return rows


def load(row: dict[str, str]) -> Image.Image:
    """Open a manifest photo as RGB."""
    # The parity fixture is the committed 224 x 224 PNG the phone test reads.
    if row["set"] == "fixture":
        return Image.open(REFERENCE_DIR / "fixture.png").convert("RGB")
    return Image.open(PHOTOS / row["file"]).convert("RGB")


def reticle(img: Image.Image) -> Image.Image:
    """Center 60% square crop resized to 224, standing in for the app's reticle."""
    side = int(min(img.size) * 0.6)
    left, top = (img.width - side) // 2, (img.height - side) // 2
    return img.crop((left, top, left + side, top + side)).resize((224, 224), Image.Resampling.BICUBIC)


def teacher():
    """Load the pinned BioCLIP 2.5 ViT-H teacher, its preprocessing, and tokenizer."""
    t = pin("teacher")
    local = snapshot_download(
        t["repo"],
        revision=t["revision"],
        cache_dir=MODEL_CACHE / "hf",
        allow_patterns=[
            "open_clip_config.json",
            "open_clip_model.safetensors",
            "*.txt",
            "tokenizer*",
            "vocab.json",
        ],
    )
    model, preprocess = open_clip.create_model_from_pretrained(f"local-dir:{local}")
    return model.eval(), preprocess, open_clip.get_tokenizer(f"local-dir:{local}")


def encode_text(model, tokenizer, texts: list[str]) -> np.ndarray:
    """Unit text embeddings, one row per text."""
    with torch.no_grad():
        return torch.nn.functional.normalize(model.encode_text(tokenizer(texts)), dim=-1).numpy()


def bioclip_scores(rows, model, tokenizer) -> None:
    """Write every BioCLIP Mobile score for every photo, region, and label to bioclip_scores.csv."""
    labels = (
        [(name, "plant", taxon) for name, taxon in PLANTS.items()]
        + [(name, "hazard", taxon) for name, taxon in HAZARDS.items()]
        + [(scene, "scene", scene) for scene in SCENES]
    )
    vectors = encode_text(model, tokenizer, [prompt(text) for _, _, text in labels])
    onnx = verified_artifact("bioclip")
    with (OUT / "bioclip_scores.csv").open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["set", "photo", "region", "label", "kind", "prompt", "score"])
        for row in rows:
            img = load(row)
            for region, crop in (
                ("full", square_fixture(img)),
                ("reticle", reticle(img)),
            ):
                scores = vectors @ embed_image(onnx, image_input(crop))
                for (label, kind, text), score in zip(labels, scores, strict=True):
                    w.writerow(
                        [
                            row["set"],
                            row["file"],
                            region,
                            label,
                            kind,
                            prompt(text),
                            f"{score:.6f}",
                        ]
                    )


def tinyclip_scores(rows) -> None:
    """Write every TinyCLIP plant-gate probability for each model, photo, and region to tinyclip_scores.csv."""
    texts = GATE_PLANT + GATE_OTHER
    kinds = ["plant"] * len(GATE_PLANT) + ["not-plant"] * len(GATE_OTHER)
    with (OUT / "tinyclip_scores.csv").open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(
            [
                "model",
                "logit_scale_exp",
                "set",
                "photo",
                "region",
                "label",
                "kind",
                "probability",
            ]
        )
        for name, revision in TINYCLIP_MODELS.items():
            model = CLIPModel.from_pretrained(name, revision=revision, cache_dir=MODEL_CACHE / "hf").eval()
            proc = CLIPProcessor.from_pretrained(name, revision=revision, cache_dir=MODEL_CACHE / "hf")
            text_inputs = proc(text=texts, return_tensors="pt", padding=True)
            scale = float(model.logit_scale.exp())
            for row in rows:
                img = load(row)
                # "full": the processor's own resize and center crop of the whole photo.
                for region, crop in (("full", img), ("reticle", reticle(img))):
                    with torch.no_grad():
                        pixels = proc(images=crop, return_tensors="pt")["pixel_values"]
                        probs = model(**text_inputs, pixel_values=pixels).logits_per_image.softmax(-1)[0]
                    for text_label, kind, p in zip(texts, kinds, probs.tolist(), strict=True):
                        w.writerow(
                            [
                                name,
                                f"{scale:.4f}",
                                row["set"],
                                row["file"],
                                region,
                                text_label,
                                kind,
                                f"{p:.6f}",
                            ]
                        )


def label_formats(rows, model, preprocess, tokenizer) -> None:
    """Write the label-format experiment (four prompt formats, mobile vs teacher) to label_format.csv."""
    row = next(r for r in rows if r["set"] == "label-format")
    img = square_fixture(load(row))
    student = embed_image(verified_artifact("bioclip"), image_input(img))
    with torch.no_grad():
        teacher_img = torch.nn.functional.normalize(model.encode_image(preprocess(img)[None]), dim=-1)[0].numpy()
    with (OUT / "label_format.csv").open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(
            [
                "photo",
                "format",
                "model",
                "label",
                "prompt",
                "score",
                "student_teacher_image_cosine",
            ]
        )
        for fmt, build in FORMATS.items():
            prompts = [build(*parts) for parts in FORMAT_LABELS.values()]
            vectors = encode_text(model, tokenizer, prompts)
            for who, emb in (("mobile", student), ("teacher", teacher_img)):
                for label, text, score in zip(FORMAT_LABELS, prompts, vectors @ emb, strict=True):
                    w.writerow(
                        [
                            row["file"],
                            fmt,
                            who,
                            label,
                            text,
                            f"{score:.6f}",
                            f"{student @ teacher_img:.6f}",
                        ]
                    )


def main() -> None:
    """Run every experiment."""
    rows = photos()
    model, preprocess, tokenizer = teacher()
    bioclip_scores(rows, model, tokenizer)
    label_formats(rows, model, preprocess, tokenizer)
    tinyclip_scores(rows)
    print("wrote", ", ".join(p.name for p in sorted(OUT.glob("*.csv"))))


if __name__ == "__main__":
    main()
