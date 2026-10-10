"""Day-4 probe 3: probe 2 plus the checks it showed were missing, and a worked example in the prompt.

Same 20 targets, excerpts, sampler, model (gemma4:26b), and two hints per aspect as gemma_hints_2.py. Changes:
  - automatic checks for roads (roadside, highway, interstate), swamps and wetlands, and a place hint that names only a
    country or region (probe 2 let 3 unsafe and 2 range hints through)
  - one worked example in the user/assistant turns, as Google's Gemma 4 guidance suggests for strict formats, on a
    plant outside the test set; it shows a null aspect, two distinct hints, and "moist" staying "moist"
  - a rule that a place is a kind of habitat, never a country or region
Prompt shape follows Google's Gemma 4 prompt-formatting guide: one consolidated system turn, thinking off
(think: false), and Google's recommended sampler (unchanged, so runs stay comparable).

Run from the repo root with Ollama serving gemma4:26b:

    uv --project pipeline run python -I docs/results/day-4/gemma_hints_3.py

Writes gemma_hints_3.csv (one row per hint) and gemma_hints_3.log next to this file. Grading each hint against its
quote goes in gemma_hints_3_grades.csv.
"""

import csv
import importlib.util
import json
import platform
import re
import sys
from datetime import datetime
from pathlib import Path
from statistics import median

from wild_find_pipeline import usda
from wild_find_pipeline.hint_rank import rank, shares
from wild_find_pipeline.hint_traits import TRAITS, usda_traits
from wild_find_pipeline.hint_traits import candidates as trait_hints
from wild_find_pipeline.paths import PLANT_TYPES, SYNONYMS
from wild_find_pipeline.toxicity import articles

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("gemma_hints", HERE / "gemma_hints.py")
one = importlib.util.module_from_spec(spec)
spec.loader.exec_module(one)

MODEL = "gemma4:26b"
ASPECTS = ("place", "light", "ground", "nearby", "edges")
PER_ASPECT = 2
# Hints that point a kid at moving or standing water or its banks; probe 1 graded these unsafe.
WATER = re.compile(
    r"\b(?:streams?|creeks?|brooks?|rivers?|riverbanks?|riparian|ponds?|lakes?|lakeshores?|swamp\w*|marsh\w*|bogs?|"
    r"wetlands?|shores?|banks?|waterways?|water|waters|ditch(?:es)?)\b",
    re.I,
)

ROAD = re.compile(r"\b(?:roads?|roadsides?|highways?|interstates?|streets?|traffic|railroads?|railways?)\b", re.I)
# A place that is a country or region is range, not a spot a kid can walk to.
REGION = re.compile(
    r"\b(?:united states|north america|america|country|states|southeast\\w*|northeast\\w*|southwest\\w*|northwest\\w*|"
    r"eastern|western|northern|southern|asia|europe|china|japan|korea|africa|world)\b",
    re.I,
)

RULES = """# Task
Tell a child, age 8, where to look outdoors to find a plant. Give hints in five kinds, up to two per kind.

# Kinds
- "place": what kind of place (woods, lawn, roadside, field edge).
- "light": how much sun (full sun, part shade, deep shade).
- "ground": wet or dry ground, and the soil if it matters.
- "nearby": what it grows next to (a kind of tree, a fence).
- "edges": a spot people made where it turns up (mowed lawn, fence line, trail edge).

# Rules
- Each hint is one sentence, 15 words or fewer, in easy words.
- Never write the plant's name. Never mention eating, touching, or picking.
- A "place" is a kind of habitat a child can walk to, never a country or a region.
- Say only what the quoted sentence says. Do not make "moist" into "wet", or turn advice about growing the plant
  into where it lives."""
GROUNDED = (
    RULES
    + """

# Source
You get sentences from the plant's Wikipedia article. Use only what they say. Give a second hint for a kind only when
a different sentence says something different; otherwise give one, or none. Never add anything from memory.

# Output
JSON with the five kinds. Each is a list of up to two objects with "hint" (your sentence) and "evidence" (one
sentence copied word for word from the article sentences). An empty list means the article does not say."""
)
SCHEMA = {
    "type": "object",
    "properties": {
        a: {
            "type": "array",
            "maxItems": PER_ASPECT,
            "items": {
                "type": "object",
                "properties": {"hint": {"type": "string"}, "evidence": {"type": "string"}},
                "required": ["hint", "evidence"],
            },
        }
        for a in ASPECTS
    },
    "required": list(ASPECTS),
}
# One worked example on a plant outside the test set: a null aspect, two distinct hints, "moist" kept as "moist".
SHOT = (
    "Plant: Trifolium repens\n\nArticle sentences:\n"
    "White clover is a low plant that is common in lawns and pastures.\n"
    "It grows best in full sun to light shade and in moist soil.\n"
    "Bees visit the white flower heads along trail edges.\n"
    "It also turns up along fence lines.",
    json.dumps(
        {
            "place": [
                {
                    "hint": "Look in lawns and pastures.",
                    "evidence": "White clover is a low plant that is common in lawns and pastures.",
                }
            ],
            "light": [
                {
                    "hint": "It likes sun or light shade.",
                    "evidence": "It grows best in full sun to light shade and in moist soil.",
                }
            ],
            "ground": [
                {
                    "hint": "It likes moist soil.",
                    "evidence": "It grows best in full sun to light shade and in moist soil.",
                }
            ],
            "nearby": [],
            "edges": [
                {
                    "hint": "Look along trail edges.",
                    "evidence": "Bees visit the white flower heads along trail edges.",
                },
                {
                    "hint": "Look along fence lines.",
                    "evidence": "It also turns up along fence lines.",
                },
            ],
        }
    ),
)
FIELDS = ["model", "scientific", "common", "aspect", "slot", "hint", "evidence", "source", "issues", "picked"]


def parse(text: str) -> dict[str, list[tuple[str, str]]]:
    """Reply JSON to {aspect: [(hint, evidence)]}, at most two each; an unreadable reply gives empty lists."""
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return {a: [] for a in ASPECTS}
    out = {}
    for aspect in ASPECTS:
        items = data.get(aspect) if isinstance(data, dict) else None
        items = items if isinstance(items, list) else []
        out[aspect] = [
            (i["hint"], i.get("evidence") or "")
            for i in items[:PER_ASPECT]
            if isinstance(i, dict) and isinstance(i.get("hint"), str) and i["hint"].strip()
        ]
    return out


def ask(target: dict, article: dict | None, known: tuple[list[str], dict, str | None]) -> tuple[dict, list[dict], dict]:
    """One target: the reply's stats, its hint rows with checks, and its rankable candidates (keyed to their rows)."""
    shown = one.excerpt(article["text"]) if article else ""
    user = f"Plant: {target['name']}\n\nArticle sentences:\n{shown or '(no article found)'}"
    reply = one.chat(one.BASE_URL, MODEL, GROUNDED, [SHOT], user, SCHEMA)
    stats = {
        "seconds": reply["seconds"],
        "cut_off": reply["prompt_tokens"] >= one.NUM_CTX - 8,
        "revid": article["revid"] if article else "",
    }
    names, facts, kind = known
    rows, candidates = [], []
    for aspect, hints in parse(reply["text"]).items():
        used = []
        for slot, (hint, evidence) in enumerate(hints, 1):
            found = one.issues(hint, evidence, article["text"] if article else None, target, True)
            if WATER.search(hint):
                found.append("water")
            if ROAD.search(hint):
                found.append("road")
            if aspect == "place" and REGION.search(hint):
                found.append("region")
            if evidence in used:
                found.append("repeats an earlier quote")
            used.append(evidence)
            rows.append(
                {
                    "aspect": aspect,
                    "slot": slot,
                    "hint": hint,
                    "evidence": evidence,
                    "source": "model",
                    "issues": "; ".join(found),
                }
            )
        if not hints and (fallback := one.usda_fallback(aspect, names, facts)):
            hint, evidence, bucket = fallback
            rows.append(
                {
                    "aspect": aspect,
                    "slot": 1,
                    "hint": hint,
                    "evidence": evidence,
                    "source": "usda",
                    "issues": "",
                    "bucket": bucket,
                }
            )
    for row in rows:
        if not row["issues"]:
            candidates.append(
                {
                    "aspect": row["aspect"],
                    "text": row["hint"],
                    "support": "usda" if row["source"] == "usda" else "article",
                    "bucket": row.get("bucket"),
                    "row": row,
                }
            )
    traits = next((facts[n] for n in names if n in facts), {})
    candidates += trait_hints(kind, traits)
    return stats, rows, candidates


def main() -> int:
    """Run the 20 targets, rank, and write the CSV and log."""
    installed = one.digests(one.BASE_URL)
    if MODEL not in installed:
        print(f"not pulled: {MODEL}; installed: {sorted(installed)}", file=sys.stderr)
        return 1
    chosen = one.targets(20)
    wiki = articles([t["name"] for t in chosen])
    facts = usda_traits(usda.archive(), TRAITS + one.USDA_TRAITS)
    kinds = json.loads(PLANT_TYPES.read_text())["species"]
    aliases = json.loads(SYNONYMS.read_text())["species"]
    log = [
        f"# {datetime.now().astimezone():%Y-%m-%d %H:%M %Z}, {one.chip()}, {platform.platform()}, "
        f"Ollama at {one.BASE_URL}, model {MODEL} @ {installed[MODEL]}, "
        f"{', '.join(f'{k} {v}' for k, v in one.SAMPLER.items())}, num_ctx {one.NUM_CTX}, "
        f"up to {PER_ASPECT} per aspect",
        f"# Wikipedia revisions: {', '.join(f'{n} {a["revid"]}' for n, a in wiki.items() if a)}",
        f"# No article: {', '.join(n for n, a in wiki.items() if not a) or 'none'}",
    ]
    asked = []
    for target in chosen:
        names = [target["name"], *aliases.get(target["name"], [])]
        known = (names, facts, kinds.get(target["name"], {}).get("type"))
        asked.append((target, *ask(target, wiki[target["name"]], known)))
        print(f"asked {target['name']}", flush=True)
    common = shares([c for _, _, _, c in asked])
    out, clean_counts, seconds = [], [], []
    for target, stats, rows, candidates in asked:
        picks = rank([{k: v for k, v in c.items() if k != "row"} for c in candidates], common)
        picked = {(p["aspect"], p["text"]) for p in picks}
        seconds.append(stats["seconds"])
        block = [f"{MODEL} {target['name']} ({target['common']}):"]
        for r in rows:
            r["picked"] = (r["aspect"], r["hint"]) in picked
            out.append(
                {"model": MODEL, "scientific": target["name"], "common": target["common"]}
                | {k: r[k] for k in FIELDS[3:]}
            )
            flag = f"  [{r['issues']}]" if r["issues"] else ""
            tag = " (USDA)" if r["source"] == "usda" else ""
            block.append(f"    {r['aspect']:<6} {r['slot']} {r['hint']}{tag}{flag}")
        if not rows:
            block.append("    (no hints)")
        block += [f"    pick {p['score']:<5} {p['aspect']:<6} {p['text']}" for p in picks]
        clean_counts.append(sum(1 for r in rows if not r["issues"]))
        log += block
        print("\n".join(block), flush=True)
    written = [r for r in out if r["source"] == "model"]
    flagged = issue_counts(written)
    summary = (
        f"{MODEL} targets {len(asked)}, hints {len(written)} written, {sum(not r['issues'] for r in written)} clean, "
        f"flagged {flagged}, plants with 3+ clean hints {sum(c >= 3 for c in clean_counts)}, "
        f"with none {sum(c == 0 for c in clean_counts)}, "
        f"median {median(seconds):.1f}s"
    )
    log += ["", summary, "# Grades go in gemma_hints_3_grades.csv: each hint judged against its quoted sentence."]
    (HERE / "gemma_hints_3.log").write_text("\n".join(log) + "\n")
    with (HERE / "gemma_hints_3.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["model", "scientific", "common", *FIELDS[3:]])
        writer.writeheader()
        writer.writerows(out)
    print(summary)
    return 0


def issue_counts(rows: list[dict]) -> str:
    """Issue name to count across [rows], as 'name n, ...'."""
    counts: dict[str, int] = {}
    for r in rows:
        for issue in filter(None, r["issues"].split("; ")):
            counts[issue] = counts.get(issue, 0) + 1
    return ", ".join(f"{k} {v}" for k, v in sorted(counts.items())) or "none"


if __name__ == "__main__":
    sys.exit(main())
