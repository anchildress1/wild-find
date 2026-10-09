"""Day-4 probe: does a larger local Gemma 4, given a Wikipedia excerpt, write "where to look" hints a kid can trust?

Run from the repo root with Ollama serving and every model already pulled (`ollama pull <tag>`; check the tags with
`ollama list`). Pass one or more model tags; each runs on the same targets, so sizes compare directly:

    uv --project pipeline run python -I docs/results/day-4/gemma_hints.py gemma4:e2b gemma4:12b

Each plant gets three hints: place (what kind of place), light (sun or shade), ground (wet or dry, soil).
Two arms per model, one set of three per target in each:
  name_only  the scientific name alone, as Day 2 did (docs/results/day-2/gemma_describe.py), now for where to look.
  grounded   the name plus sentences pulled from the plant's English Wikipedia article; for each hint the model must
             copy one sentence from them as evidence, or answer null when they don't say.

Targets are Day 2's 20 most-seen West Georgia October species that are in the species table and not toxic-flagged,
so Day 2's E2B grades still compare. Writes gemma_hints.csv and gemma_hints.log next to this file.

The automatic checks catch made-up sources, banned words, name leaks, and length. They can't tell whether a hint
follows from its evidence, so grading against the article stays a person's (or Claude's) job.
"""

import argparse
import csv
import json
import platform
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from statistics import median

from wild_find_pipeline.descriptions import BANNED
from wild_find_pipeline.toxicity import SENTENCE, TOXICITY, articles

OUT = Path(__file__).resolve().parent
DAY2 = OUT.parent / "day-2"
BASE_URL = "http://localhost:11434"
# Ollama's default context is small and silently cuts a longer prompt, so the probe sets it and checks the prompt fit.
NUM_CTX = 8192
MAX_NEW_TOKENS = 400
# Gemma 4's recommended sampler, as Day 2 used it.
SAMPLER = {"temperature": 1.0, "top_p": 0.95, "top_k": 64, "seed": 1}
LEAD_SENTENCES = 2
MAX_EXCERPT_SENTENCES = 30
MAX_WORDS = 20
ASPECTS = ("place", "light", "ground")
# Sentences that can say where a plant grows; the excerpt keeps the first MAX_EXCERPT_SENTENCES in article order.
SETTING = re.compile(
    r"\b(?:grow\w*|habitat\w*|found|occurs?|native|range|prefers?|thrives?|inhabit\w*|forest\w*|woodland\w*|"
    r"wetland\w*|swamp\w*|marsh\w*|meadow\w*|prairie\w*|stream\w*|river\w*|lake\w*|shore\w*|moist|damp|wet|dry|"
    r"sunny|sun|shade\w*|soils?|sandy|clay|rocky|disturbed|roadside\w*|field\w*|lawn\w*|garden\w*|edge\w*|"
    r"slope\w*|floodplain\w*|bluff\w*|thicket\w*|open)\b",
    re.I,
)
# What an 8-year-old shouldn't be told to do, beyond descriptions.BANNED (safety claims).
ACTIONS = re.compile(r"\b(?:eat|eating|eaten|touch|touching|pick|picking|taste|tasting)\b", re.I)

RULES = """# Task
Tell a child, age 8, where to look outdoors to find a plant. Give three hints.

# Hints
- "place": what kind of place (woods, lawn, stream bank, roadside, field edge).
- "light": how much sun (full sun, part shade, deep shade).
- "ground": wet or dry ground, and the soil if it matters.

# Rules
- Each hint is one sentence, 15 words or fewer, in easy words.
- Never write the plant's name. Never mention eating, touching, or picking."""
NAME_ONLY = (
    RULES
    + """

# Output
JSON with the fields "place", "light", "ground", each a sentence."""
)
GROUNDED = (
    RULES
    + """

# Source
You get sentences from the plant's Wikipedia article. Use only what they say. If they do not say, answer null for
that hint's sentence and its evidence. Never add anything from memory.

# Output
JSON with the fields "place", "light", "ground". Each is an object with "hint" (your sentence, or null) and
"evidence" (one sentence copied word for word from the article sentences, or null)."""
)
_TEXT = {"type": ["string", "null"]}
SCHEMA_NAME_ONLY = {
    "type": "object",
    "properties": {a: {"type": "string"} for a in ASPECTS},
    "required": list(ASPECTS),
}
SCHEMA_GROUNDED = {
    "type": "object",
    "properties": {
        a: {"type": "object", "properties": {"hint": _TEXT, "evidence": _TEXT}, "required": ["hint", "evidence"]}
        for a in ASPECTS
    },
    "required": list(ASPECTS),
}
# Few-shot pairs outside the test set, showing the output shape for the name-only arm.
EXAMPLES = [
    (
        "Taraxacum officinale",
        json.dumps(
            {
                "place": "Look in lawns, along roadsides, and in sidewalk cracks.",
                "light": "It likes open, sunny spots.",
                "ground": "It grows in dry or average soil.",
            }
        ),
    ),
    (
        "Trifolium repens",
        json.dumps(
            {
                "place": "Look in lawns and grassy parks.",
                "light": "It grows in sun or light shade.",
                "ground": "It likes ground that stays a little damp.",
            }
        ),
    ),
]


def targets(count: int) -> list[dict]:
    """The most-seen local October species that are in the species table and not toxic-flagged, as Day 2 picked them."""
    flags = json.loads(TOXICITY.read_text())["species"]
    rows = [r for r in csv.DictReader((DAY2 / "inat_species_oct.csv").open()) if r["rank"] == "species"]
    rows = [r for r in rows if r["name"] in flags and not flags[r["name"]]["toxic"]]
    return sorted(rows, key=lambda r: -int(r["count"]))[:count]


def excerpt(text: str) -> str:
    """The article's lead plus the first sentences that can say where the plant grows, in article order."""
    sentences = [" ".join(s.split()) for s in SENTENCE.findall(text)]
    sentences = [s for s in sentences if len(s) > 20]
    lead, rest = sentences[:LEAD_SENTENCES], sentences[LEAD_SENTENCES:]
    return "\n".join(lead + [s for s in rest if SETTING.search(s)][:MAX_EXCERPT_SENTENCES])


def squash(text: str) -> str:
    """Whitespace-collapsed, case-folded text, so a quote matches however the article wrapped its lines."""
    return " ".join(text.split()).casefold()


def issues(hint: str | None, evidence: str | None, article: str | None, target: dict, grounded: bool) -> list[str]:
    """Every automatic check one hint fails; empty when it passes. A null hint is an abstention, not a failure."""
    if hint is None:
        return []
    found = []
    if grounded and (not evidence or squash(evidence) not in squash(article or "")):
        found.append("evidence not in article")
    lowered = hint.lower()
    names = [target["common"].lower(), *target["name"].lower().split()[:2]]
    if any(name and name in lowered for name in names):
        found.append("names the plant")
    if any(word in lowered for word in BANNED) or ACTIONS.search(hint):
        found.append("banned word")
    if len(hint.split()) > MAX_WORDS:
        found.append("too long")
    return found


def chat(base_url: str, model: str, system: str, shots: list[tuple[str, str]], user: str, schema: dict) -> dict:
    """One Ollama chat turn (native API, so num_ctx is honored); returns the reply text, prompt tokens, and seconds."""
    messages = [{"role": "system", "content": system}]
    for question, answer in shots:
        messages += [{"role": "user", "content": question}, {"role": "assistant", "content": answer}]
    messages.append({"role": "user", "content": user})
    body = {
        "model": model,
        "messages": messages,
        "stream": False,
        "format": schema,
        "options": {**SAMPLER, "num_ctx": NUM_CTX, "num_predict": MAX_NEW_TOKENS},
    }
    request = urllib.request.Request(
        f"{base_url}/api/chat", json.dumps(body).encode(), {"Content-Type": "application/json"}
    )
    start = time.perf_counter()
    with urllib.request.urlopen(request, timeout=900) as response:
        reply = json.load(response)
    return {
        "text": reply["message"]["content"].strip(),
        "prompt_tokens": reply.get("prompt_eval_count", 0),
        "seconds": round(time.perf_counter() - start, 2),
    }


def parse(text: str, grounded: bool) -> dict[str, tuple[str | None, str | None]]:
    """Reply JSON to {aspect: (hint, evidence)}; an unreadable reply gives null hints, logged as abstained."""
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return {a: (None, None) for a in ASPECTS}
    out = {}
    for aspect in ASPECTS:
        value = data.get(aspect) if isinstance(data, dict) else None
        if grounded:
            value = value if isinstance(value, dict) else {}
            out[aspect] = (value.get("hint") or None, value.get("evidence") or None)
        else:
            out[aspect] = (value if isinstance(value, str) and value else None, None)
    return out


def ask(base_url: str, model: str, arm: str, target: dict, article: dict | None) -> dict:
    """Run one target through one arm and return its CSV row, checks included."""
    grounded = arm == "grounded"
    shown = excerpt(article["text"]) if grounded and article else ""
    if grounded:
        user = f"Plant: {target['name']}\n\nArticle sentences:\n{shown or '(no article found)'}"
        reply = chat(base_url, model, GROUNDED, [], user, SCHEMA_GROUNDED)
    else:
        reply = chat(base_url, model, NAME_ONLY, EXAMPLES, target["name"], SCHEMA_NAME_ONLY)
    row = {
        "model": model,
        "arm": arm,
        "scientific": target["name"],
        "common": target["common"],
        "seconds": reply["seconds"],
        "prompt_tokens": reply["prompt_tokens"],
        # A prompt at the context limit was cut, so a missing habitat sentence may be the cut's fault.
        "cut_off": reply["prompt_tokens"] >= NUM_CTX - 8,
        "excerpt_chars": len(shown),
        "article_revid": article["revid"] if article else "",
    }
    for aspect, (hint, evidence) in parse(reply["text"], grounded).items():
        found = issues(hint, evidence, article["text"] if article else None, target, grounded)
        row |= {
            f"{aspect}_hint": hint or "",
            f"{aspect}_evidence": evidence or "",
            f"{aspect}_abstained": hint is None,
            f"{aspect}_issues": "; ".join(found),
        }
    return row


def digests(base_url: str) -> dict[str, str]:
    """Model tag to its short weights digest from Ollama, so the log records exactly which weights ran."""
    with urllib.request.urlopen(f"{base_url}/api/tags", timeout=30) as response:
        return {m["name"]: m["digest"][:12] for m in json.load(response)["models"]}


def chip() -> str:
    """The Mac's chip name, else the platform string."""
    try:
        return subprocess.run(
            ["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True, check=True
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return platform.platform()


def hints_of(row: dict) -> list[dict]:
    """The three hints in a row as {aspect, hint, abstained, issues} dicts."""
    return [
        {
            "aspect": a,
            "hint": row[f"{a}_hint"],
            "abstained": row[f"{a}_abstained"],
            "issues": row[f"{a}_issues"],
        }
        for a in ASPECTS
    ]


def summary(rows: list[dict]) -> list[str]:
    """One line per model and arm: hints written, abstained, clean, fake evidence, and the median time per target."""
    lines = []
    for model in dict.fromkeys(r["model"] for r in rows):
        for arm in ("name_only", "grounded"):
            part = [r for r in rows if r["model"] == model and r["arm"] == arm]
            if not part:
                continue
            slots = [h for r in part for h in hints_of(r)]
            written = [h for h in slots if not h["abstained"]]
            clean = [h for h in written if not h["issues"]]
            fake = [h for h in written if "evidence not in article" in h["issues"]]
            full = [r for r in part if not any(h["abstained"] for h in hints_of(r))]
            cut = [r for r in part if r["cut_off"]]
            lines.append(
                f"{model:<14} {arm:<9} targets {len(part)}, all 3 hints {len(full)}, "
                f"hints {len(written)}/{len(slots)}, clean {len(clean)}, fake evidence {len(fake)}, "
                f"prompts cut {len(cut)}, median {median(r['seconds'] for r in part):.1f}s"
            )
    return lines


def main() -> int:
    """Run every model on both arms over the targets and write gemma_hints.csv and gemma_hints.log."""
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("models", nargs="+", help="Ollama tags, e.g. gemma4:e2b gemma4:12b")
    parser.add_argument("--base-url", default=BASE_URL)
    parser.add_argument("--targets", type=int, default=20)
    args = parser.parse_args()

    try:
        installed = digests(args.base_url)
    except (urllib.error.URLError, OSError) as e:
        print(f"Ollama isn't answering at {args.base_url}: {e}", file=sys.stderr)
        return 1
    if missing := [m for m in args.models if m not in installed]:
        print(f"not pulled: {missing}; installed: {sorted(installed)}", file=sys.stderr)
        return 1

    chosen = targets(args.targets)
    wiki = articles([t["name"] for t in chosen])
    log = [
        f"# {datetime.now().astimezone():%Y-%m-%d %H:%M %Z}, {chip()}, {platform.platform()}, "
        f"Ollama at {args.base_url}, models {', '.join(f'{m} @ {installed[m]}' for m in args.models)}, "
        f"{', '.join(f'{k} {v}' for k, v in SAMPLER.items())}, num_ctx {NUM_CTX}",
        f"# Wikipedia revisions: {', '.join(f'{n} {a["revid"]}' for n, a in wiki.items() if a)}",
        f"# No article: {', '.join(n for n, a in wiki.items() if not a) or 'none'}",
    ]
    rows = []
    for model in args.models:
        for arm in ("name_only", "grounded"):
            for target in chosen:
                row = ask(args.base_url, model, arm, target, wiki[target["name"]])
                rows.append(row)
                log.append(f"{model} {arm} {target['name']} ({target['common']}):")
                for h in hints_of(row):
                    flag = f"  [{h['issues']}]" if h["issues"] else ""
                    log.append(f"    {h['aspect']:<6} {h['hint'] or '(abstained)'}{flag}")
                print("\n".join(log[-4:]), flush=True)
    log += ["", *summary(rows)]
    log.append("# Grades go in gemma_hints_grades.csv: each hint judged against its article.")
    (OUT / "gemma_hints.log").write_text("\n".join(log) + "\n")
    with (OUT / "gemma_hints.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print("\n".join(summary(rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
