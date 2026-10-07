"""Day-2 probe: can Gemma 4 E2B describe a plant for an 8-year-old from its scientific name alone?

Run from the repo root (litert-lm-api comes in for this run only; the pipeline doesn't depend on it):

    uv --project pipeline run --with litert-lm-api==0.18.0 python -I docs/results/day-2/gemma_describe.py

Uses the pinned .litertlm the phone runs (make fetch-models), on the laptop CPU, with Gemma 4's recommended
sampler, a system instruction, and two few-shot examples. Targets are the 20 most-seen West Georgia October species
that would be hunt targets: in the species table and not toxic-flagged. Writes gemma_describe.csv.

Gemma was dropped on Oct 7; uncomment the gemma lines in models.properties to rerun.
"""

import csv
import json
import sys
import time
from pathlib import Path

import litert_lm
from wild_find_pipeline.paths import MODEL_CACHE, TOXICITY, pin

OUT = Path(__file__).resolve().parent
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


def main() -> int:
    """Ask Gemma for each target's description and record the reply and its time."""
    model = MODEL_CACHE / pin("gemma")["file"]
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
