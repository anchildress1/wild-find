"""Build step 1: the pinned Gemma writes candidate kid words with the PRD candidate prompt."""

import json
import re
import sys
import time
from collections.abc import Iterable
from importlib.metadata import version
from pathlib import Path

from wild_find_pipeline.paths import REPO, pin, verified_artifact

CANDIDATES = REPO / "pipeline/menu/candidates.json"
# Exact PRD Build Pipeline text; a change here is a PRD change.
PROMPT = """List plants a US 8-year-old could recognize by sight.
Output a JSON array of strings only.
Rules:
- Kid words, 1-2 words each: "oak", "fern", "cattail".
- No mushrooms or fungi. No poisonous plants.
- No cultivar or brand names."""
# Greedy decoding returns the same 10 words every time; sampling over fixed seeds widens the pool and stays
# reproducible. Gemma's recommended sampler; the yield levels off by seed 7 (docs/results/day-2/).
SEEDS = range(1, 11)
SAMPLER = {"top_k": 64, "top_p": 0.95, "temperature": 1.0}
MAX_OUTPUT_TOKENS = 1024
FENCE = re.compile(r"^```(?:json)?\s*|\s*```$")


def parse_words(reply: str) -> list[str]:
    """Words from one Gemma reply: a JSON array of strings, optionally fenced, lowercased and trimmed.

    Raises ValueError when the reply is not a JSON array of strings.
    """
    try:
        words = json.loads(FENCE.sub("", reply.strip()))
    except json.JSONDecodeError as e:
        raise ValueError(f"reply is not JSON: {reply!r}") from e
    if not isinstance(words, list) or not all(isinstance(w, str) for w in words):
        raise ValueError(f"reply is not a JSON array of strings: {reply!r}")
    return [" ".join(w.lower().split()) for w in words if w.strip()]


def merge(replies: Iterable[list[str]]) -> list[str]:
    """Sorted union of every reply's words."""
    return sorted({w for words in replies for w in words})


def generate(model: Path) -> dict[int, list[str]]:
    """Run the candidate prompt once per seed on the CPU; returns each seed's words."""
    import litert_lm

    out = {}
    start = time.perf_counter()
    with litert_lm.Engine(str(model), backend=litert_lm.Backend.CPU()) as engine:
        print(f"load {time.perf_counter() - start:.1f} s")
        for seed in SEEDS:
            start = time.perf_counter()
            sampler = litert_lm.SamplerConfig(seed=seed, **SAMPLER)
            with engine.create_conversation(sampler_config=sampler, max_output_tokens=MAX_OUTPUT_TOKENS) as chat:
                reply = chat.send_message(PROMPT)["content"][0]["text"]
            out[seed] = parse_words(reply)
            print(f"seed {seed}: {time.perf_counter() - start:.1f} s, {out[seed]}")
    return out


def main() -> int:
    """Write candidates.json: the merged words plus the model pin, prompt, and sampler that made them."""
    gemma = pin("gemma")
    by_seed = generate(verified_artifact("gemma"))
    words = merge(by_seed.values())
    CANDIDATES.parent.mkdir(parents=True, exist_ok=True)
    record = {
        "model": {key: gemma[key] for key in ("repo", "revision", "file", "sha256")},
        "runtime": {"litert-lm-api": version("litert-lm-api"), "backend": "cpu"},
        "prompt": PROMPT,
        "sampler": {**SAMPLER, "max_output_tokens": MAX_OUTPUT_TOKENS},
        "seeds": {str(seed): seed_words for seed, seed_words in by_seed.items()},
        "words": words,
    }
    CANDIDATES.write_text(json.dumps(record, indent=1) + "\n")
    print(f"OK: wrote {len(words)} words to {CANDIDATES}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
