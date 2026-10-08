"""Day-2 probe: can Gemma 4 E2B describe a plant for an 8-year-old from its scientific name alone?

Run from the repo root (litert-lm-api comes in for this run only; the pipeline doesn't depend on it):

    uv --project pipeline run --group reference --with litert-lm-api==0.18.0 python -I docs/results/day-2/gemma_describe.py

Uses the pinned .litertlm the phone ran (PIN below; downloaded into .models and SHA-checked), on the laptop CPU, with Gemma 4's recommended
sampler, a system instruction, and two few-shot examples. Targets are the 20 most-seen West Georgia October species
that would be hunt targets: in the species table and not toxic-flagged. Writes gemma_describe.csv.
"""

import csv
import json
import sys
import time
from pathlib import Path

import litert_lm
from wild_find_pipeline.paths import MODEL_CACHE, TOXICITY, file_sha256

OUT = Path(__file__).resolve().parent
# The Gemma pin the app shipped until Oct 7, when Gemma left models.properties; the probe keeps its own copy to rerun.
PIN = {
    "repo": "litert-community/gemma-4-E2B-it-litert-lm",
    "revision": "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1",
    "file": "gemma-4-E2B-it.litertlm",
    "bytes": 2588147712,
    "sha256": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
}
TARGETS = 20
SEED = 1
SAMPLER = {"temperature": 1.0, "top_p": 0.95, "top_k": 64}
SYSTEM = """# Task
Describe a plant so a child, age 8, can recognize it outdoors.

# Rules
- One or two sentences, 25 words or fewer, in easy words.
- Only what the child can see: leaf shape, bark, flowers, fruit, size, where it grows.
- Never write the plant's name. Never mention eating, touching, or picking.

# Output
The description only."""
# Few-shot pairs, outside the test set, showing the exact output shape.
EXAMPLES = [
    ("Taraxacum officinale", "A low plant with jagged leaves in a flat circle and bright yellow flowers that turn into white fluffy puffballs."),
    ("Trifolium repens", "A tiny lawn plant with three round leaves on each stem and small white flower balls."),
]  # fmt: skip


def targets() -> list[dict]:
    """The most-seen local October species that are in the species table and not toxic-flagged."""
    flags = json.loads(TOXICITY.read_text())["species"]
    rows = [r for r in csv.DictReader((OUT / "inat_species_oct.csv").open()) if r["rank"] == "species"]
    rows = [r for r in rows if r["name"] in flags and not flags[r["name"]]["toxic"]]
    return sorted(rows, key=lambda r: -int(r["count"]))[:TARGETS]


def gemma() -> Path:
    """The pinned Gemma file in .models, downloaded when missing, checked against its size and SHA-256."""
    path = MODEL_CACHE / PIN["file"]
    if not path.is_file() or path.stat().st_size != PIN["bytes"]:
        from huggingface_hub import hf_hub_download

        hf_hub_download(PIN["repo"], PIN["file"], revision=PIN["revision"], local_dir=MODEL_CACHE)
    if path.stat().st_size != PIN["bytes"] or file_sha256(path) != PIN["sha256"]:
        raise ValueError(f"{path} does not match the pinned Gemma")
    return path


def main() -> int:
    """Ask Gemma for each target's description and record the reply and its time."""
    model = gemma()
    messages = [{"role": "system", "content": SYSTEM}]
    for name, description in EXAMPLES:
        messages += [{"role": "user", "content": name}, {"role": "model", "content": description}]
    out = []
    with litert_lm.Engine(str(model), backend=litert_lm.Backend.CPU()) as engine:
        for target in targets():
            start = time.perf_counter()
            sampler = litert_lm.SamplerConfig(seed=SEED, **SAMPLER)
            with engine.create_conversation(messages=messages, sampler_config=sampler, max_output_tokens=128) as chat:
                reply = chat.send_message(target["name"])["content"][0]["text"].strip()
            out.append({"count": target["count"], "scientific": target["name"], "common": target["common"],
                        "seconds": round(time.perf_counter() - start, 2), "description": reply})  # fmt: skip
            print(f"{target['name']} ({target['common']}): {reply}")
    with (OUT / "gemma_describe.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
