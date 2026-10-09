"""Re-rank the Day-4 probe's saved hints with today's hint_rank, without calling a model.

Run from the repo root:

    uv --project pipeline run python -I docs/results/day-4/gemma_hints_rerank.py

Rebuilds each grounded row's candidates from gemma_hints.csv (clean hints only) plus the USDA trait hints, ranks them
per model with the current wild_find_pipeline.hint_rank (every season hint kept beyond the three picks), and rewrites
pick_count and top_picks in gemma_hints.csv and the pick lines and "ranked picks" counts in gemma_hints.log.
Model text, evidence, and the grades are untouched.
"""

import csv
import importlib.util
import json
import re
from pathlib import Path

from wild_find_pipeline import usda
from wild_find_pipeline.hint_rank import PICKS, rank, shares
from wild_find_pipeline.hint_traits import TRAITS, usda_traits
from wild_find_pipeline.hint_traits import candidates as trait_hints
from wild_find_pipeline.paths import PLANT_TYPES, SYNONYMS

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("gemma_hints", HERE / "gemma_hints.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

BUCKET_OF = {text: bucket for text, bucket in probe.USDA_HINTS.values()}


def candidates_of(row: dict, facts: dict, kinds: dict, aliases: dict) -> list[dict]:
    """The clean article and USDA-fill hints in a grounded row, plus its USDA trait hints."""
    found = []
    for aspect in probe.ASPECTS:
        text = row[f"{aspect}_hint"]
        if text and not row[f"{aspect}_issues"]:
            usda_sourced = row[f"{aspect}_source"] == "usda"
            found.append(
                {
                    "aspect": aspect,
                    "text": text,
                    "support": "usda" if usda_sourced else "article",
                    "bucket": BUCKET_OF.get(text) if usda_sourced else None,
                }
            )
    name = row["scientific"]
    traits = next((facts[n] for n in [name, *aliases.get(name, [])] if n in facts), {})
    return found + trait_hints(kinds.get(name, {}).get("type"), traits)


def main() -> None:
    """Rank every grounded row and rewrite the CSV and log."""
    facts = usda_traits(usda.archive(), TRAITS + probe.USDA_TRAITS)
    kinds = json.loads(PLANT_TYPES.read_text())["species"]
    aliases = json.loads(SYNONYMS.read_text())["species"]
    with (HERE / "gemma_hints.csv").open(newline="") as f:
        rows = list(csv.DictReader(f))
    picks: dict[tuple[str, str, str], list[dict]] = {}
    for model in dict.fromkeys(r["model"] for r in rows):
        part = [r for r in rows if r["model"] == model and r["arm"] == "grounded"]
        cands = {r["scientific"]: candidates_of(r, facts, kinds, aliases) for r in part}
        common = shares(list(cands.values()))
        for r in part:
            picks[model, "grounded", r["scientific"]] = rank(cands[r["scientific"]], common)
    for r in rows:
        ranked = picks.get((r["model"], r["arm"], r["scientific"]), [])
        r["pick_count"] = len(ranked)
        r["top_picks"] = " | ".join(f"{p['aspect']}: {p['text']}" for p in ranked)
    with (HERE / "gemma_hints.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    out, key, header = [], None, re.compile(r"^(\S+) (name_only|grounded) (.+?) \(.*\):$")

    def flush() -> None:
        for p in picks.get(key, []):
            out.append(f"    pick {p['score']:<5} {p['aspect']:<6} {p['text']}")

    for line in (HERE / "gemma_hints.log").read_text().splitlines():
        if line.startswith("    pick "):
            continue
        if m := header.match(line) or not line.startswith("    "):
            flush()
            key = (m.group(1), m.group(2), m.group(3)) if m and not isinstance(m, bool) else None
        out.append(line)
    flush()
    log = "\n".join(out) + "\n"
    for model in dict.fromkeys(r["model"] for r in rows):
        full = sum(1 for r in rows if r["model"] == model and r["arm"] == "grounded" and int(r["pick_count"]) >= PICKS)
        log = re.sub(
            rf"({re.escape(model)}\s+grounded\s+targets \d+, with {PICKS} ranked picks )\d+", rf"\g<1>{full}", log
        )
    (HERE / "gemma_hints.log").write_text(log)


if __name__ == "__main__":
    main()
