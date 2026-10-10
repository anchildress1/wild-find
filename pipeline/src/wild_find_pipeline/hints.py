"""Build-time "where to look" hints: Gemma 4 26b over each playable row's Wikipedia article, run locally with Ollama.

The model must quote the article sentence each hint comes from, or leave the kind empty. Automatic checks drop made-up
quotes, banned words, the plant's name, long hints, hints that send a kid to water or traffic, and a place that is
only a country or region. A clear USDA shade or wet-soil rating fills a light or ground kind the model left empty, and
hint_traits adds size, season, and sign hints. hint_rank then picks the best three plus every season hint.

Writes pipeline/data/hints.json. An interrupted run resumes from its unfinished file and loses at most one checkpoint;
a finished file is rebuilt from scratch.
"""

import json
import re
import sys
import time
from datetime import date

from wild_find_pipeline import gemma, usda
from wild_find_pipeline.contact_hazards import listed
from wild_find_pipeline.descriptions import ACTIONS, BANNED
from wild_find_pipeline.gemma import MODEL, NUM_CTX, SAMPLER, squash
from wild_find_pipeline.hint_rank import rank, shares
from wild_find_pipeline.hint_traits import TRAITS, usda_traits
from wild_find_pipeline.hint_traits import candidates as trait_hints
from wild_find_pipeline.labels import is_hazard
from wild_find_pipeline.paths import HINTS, PLANT_TYPES, SYNONYMS, TOXICITY
from wild_find_pipeline.toxicity import SENTENCE, articles

MAX_NEW_TOKENS = 700
ASPECTS = ("place", "light", "ground", "nearby", "edges")
PER_ASPECT = 2
LEAD_SENTENCES = 2
MAX_EXCERPT_SENTENCES = 30
MAX_WORDS = 20
CHECKPOINT = 25
# Sentences that can say where a plant grows; the excerpt keeps the first MAX_EXCERPT_SENTENCES in article order.
SETTING = re.compile(
    r"\b(?:grow\w*|habitat\w*|found|occurs?|native|range|prefers?|thrives?|inhabit\w*|forest\w*|woodland\w*|"
    r"wetland\w*|swamp\w*|marsh\w*|meadow\w*|prairie\w*|stream\w*|river\w*|lake\w*|shore\w*|moist|damp|wet|dry|"
    r"sunny|sun|shade\w*|soils?|sandy|clay|rocky|disturbed|roadside\w*|field\w*|lawn\w*|garden\w*|edge\w*|"
    r"slope\w*|floodplain\w*|bluff\w*|thicket\w*|open)\b",
    re.I,
)
# Hints that point a kid at water or its banks, or at traffic; graded unsafe in the Day-4 probes.
WATER = re.compile(
    r"\b(?:streams?|creeks?|brooks?|rivers?|riverbanks?|riparian|ponds?|lakes?|lakeshores?|swamp\w*|marsh\w*|bogs?|"
    r"wetlands?|shores?|shorelines?|banks?|waterways?|water|waters|freshwaters?|flood\w*|ditch(?:es)?)\b",
    re.I,
)
# Hints that point a kid at a drop or a roof; the Oct 9 full-run sample found cliffs, bluffs, and canyon walls.
HEIGHT = re.compile(r"\b(?:cliffs?|bluffs?|ledges?|canyon walls?|crags?|roofs?|rooftops?|top of houses)\b", re.I)
ROAD = re.compile(r"\b(?:roads?|roadsides?|highways?|interstates?|streets?|traffic|railroads?|railways?)\b", re.I)
# A place that is a country or region is range, not a spot a kid can walk to.
REGION = re.compile(
    r"\b(?:united states|north america|america|country|states|southeast\w*|northeast\w*|southwest\w*|northwest\w*|"
    r"eastern|western|northern|southern|asia|europe|china|japan|korea|africa|world)\b",
    re.I,
)
# USDA ratings fill a kind the article left empty. The value codes were decoded on Oct 9 from species with known
# answers, because the ontology lookup was unreachable: shade high = Cornus florida, low = Pinus taeda and Salix nigra;
# wet soil high = Typha latifolia and Taxodium distichum, none = Taraxacum officinale. Only those clear ends are used,
# and "tolerates shade" never becomes "lives in shade", so the high-shade sentence says "can grow".
USDA_TRAITS = ("ShadeTolerance", "AnaerobicSoilTolerance")
USDA_TRAIT_OF = {"light": "ShadeTolerance", "ground": "AnaerobicSoilTolerance"}
# (aspect, value code) -> (hint, bucket); the bucket is what hint_rank counts to tell common hints from rare ones.
USDA_HINTS = {
    ("light", "PATO_0002394"): ("Look in open, sunny spots.", "sun"),
    ("light", "PATO_0002393"): ("It can grow in shade, like under trees.", "shade"),
    ("ground", "PATO_0002393"): ("Look where the ground is wet or soggy.", "wet"),
    ("ground", "260413007"): ("Look on ground that drains well, not soggy.", "dry"),
}

SYSTEM = """# Task
Tell a child, age 8, where to look outdoors to find a plant. Give hints in five kinds, up to two per kind.

# Kinds
- "place": what kind of place (woods, lawn, field edge).
- "light": how much sun (full sun, part shade, deep shade).
- "ground": wet or dry ground, and the soil if it matters.
- "nearby": what it grows next to (a kind of tree, a fence).
- "edges": a spot people made where it turns up (mowed lawn, fence line, trail edge).

# Rules
- Each hint is one sentence, 15 words or fewer, in easy words.
- Never write the plant's name. Never mention eating, touching, or picking.
- A "place" is a kind of habitat a child can walk to, never a country or a region.
- Say only what the quoted sentence says. Do not make "moist" into "wet", or turn advice about growing the plant
  into where it lives.

# Source
You get sentences from the plant's Wikipedia article. Use only what they say. Give a second hint for a kind only when
a different sentence says something different; otherwise give one, or none. Never add anything from memory.

# Output
JSON with the five kinds. Each is a list of up to two objects with "hint" (your sentence) and "evidence" (one
sentence copied word for word from the article sentences). An empty list means the article does not say."""
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
# One worked example on a plant that is not a target: an empty kind, two distinct hints, "moist" kept as "moist".
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
                {"hint": "Look along trail edges.", "evidence": "Bees visit the white flower heads along trail edges."},
                {"hint": "Look along fence lines.", "evidence": "It also turns up along fence lines."},
            ],
        }
    ),
)


def excerpt(text: str) -> str:
    """The article's lead plus the first sentences that can say where the plant grows, in article order."""
    sentences = [" ".join(s.split()) for s in SENTENCE.findall(text)]
    sentences = [s for s in sentences if len(s) > 20]
    lead, rest = sentences[:LEAD_SENTENCES], sentences[LEAD_SENTENCES:]
    return "\n".join(lead + [s for s in rest if SETTING.search(s)][:MAX_EXCERPT_SENTENCES])


def issues(aspect: str, hint: str, evidence: str, article: str, names: list[str]) -> list[str]:
    """Every automatic check a model hint fails; empty when it passes."""
    found = []
    if not evidence or squash(evidence) not in squash(article):
        found.append("evidence not in article")
    lowered = hint.lower()
    if any(name and name.lower() in lowered for name in names):
        found.append("names the plant")
    if any(word in lowered for word in BANNED) or ACTIONS.search(hint):
        found.append("banned word")
    if len(hint.split()) > MAX_WORDS:
        found.append("too long")
    if WATER.search(hint):
        found.append("water")
    if ROAD.search(hint):
        found.append("road")
    if HEIGHT.search(hint):
        found.append("height")
    if aspect == "place" and REGION.search(hint):
        found.append("region")
    return found


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
            (i["hint"].strip(), (i.get("evidence") or "").strip())
            for i in items[:PER_ASPECT]
            if isinstance(i, dict) and isinstance(i.get("hint"), str) and i["hint"].strip()
        ]
    return out


def chat(user: str) -> dict:
    """One hints turn to the local Gemma; raises on a reply that is cut short or empty."""
    return gemma.chat(SYSTEM, (SHOT,), SCHEMA, user, MAX_NEW_TOKENS)


def usda_fill(aspect: str, names: list[str], facts: dict[str, dict[str, list[str]]]) -> dict | None:
    """A light or ground hint from the first name with one clear USDA rating, else None."""
    trait = USDA_TRAIT_OF.get(aspect)
    for name in names:
        values = facts.get(name, {}).get(trait, [])
        if len(values) == 1 and (aspect, values[0]) in USDA_HINTS:
            text, bucket = USDA_HINTS[aspect, values[0]]
            return {"aspect": aspect, "text": text, "evidence": f"USDA {trait} {values[0]} ({name})", "bucket": bucket}
    return None


def hints_for(row: str, page: dict | None, aliases: list[str], kind: str | None, facts: dict) -> dict:
    """Every candidate hint for one row, each with its source and the checks it failed; ranking comes later."""
    names = [row, *aliases]
    found: list[dict] = []
    said: set[str] = set()
    cut = False
    failed = None
    if page:
        words = [page["title"], *row.split()[:2]]
        shown = excerpt(page["text"])
        try:
            reply = chat(f"Plant: {row}\n\nArticle sentences:\n{shown or '(no article found)'}")
        except ValueError as error:
            # One runaway reply must not end a 90-minute run; the row keeps no model hints and says why.
            failed = str(error)
            reply = {"text": "{}", "cut_off": False}
        cut = reply["cut_off"]
        for aspect, items in parse(reply["text"]).items():
            used: list[str] = []
            for hint, evidence in items:
                bad = issues(aspect, hint, evidence, page["text"], words)
                if evidence in used:
                    bad.append("repeats an earlier quote")
                used.append(evidence)
                if hint.casefold() in said:
                    bad.append("repeats an earlier hint")
                if not bad:
                    said.add(hint.casefold())
                found.append({"aspect": aspect, "text": hint, "evidence": evidence, "source": "model", "issues": bad})
            if not items and (fill := usda_fill(aspect, names, facts)):
                found.append({**fill, "source": "usda", "issues": []})
    elif fills := [f for a in ("light", "ground") if (f := usda_fill(a, names, facts))]:
        found += [{**f, "source": "usda", "issues": []} for f in fills]
    traits = next((facts[n] for n in names if n in facts), {})
    found += [
        {
            "aspect": c["aspect"],
            "text": c["text"],
            "evidence": "",
            "source": "trait",
            "issues": [],
            "bucket": c["bucket"],
        }
        for c in trait_hints(kind, traits)
    ]
    return {
        "article": page["title"] if page else None,
        "revid": page["revid"] if page else None,
        "cut_off": cut,
        "failed": failed,
        "hints": found,
    }


def candidate(hint: dict) -> dict:
    """A clean hint as hint_rank wants it: support is the article quote, a USDA rating, or a USDA trait template."""
    support = "article" if hint["source"] == "model" else "usda"
    return {"aspect": hint["aspect"], "text": hint["text"], "support": support, "bucket": hint.get("bucket")}


def rank_all(species: dict[str, dict]) -> None:
    """Mark each row's picked hints, with their score, using rarity across the whole batch."""
    clean = {row: [h for h in entry["hints"] if not h["issues"]] for row, entry in species.items()}
    common = shares([[candidate(h) for h in hints] for hints in clean.values()])
    for row, hints in clean.items():
        picks = rank([candidate(h) for h in hints], common)
        scores = {(p["aspect"], p["text"]): p["score"] for p in picks}
        for hint in species[row]["hints"]:
            hint["score"] = scores.get((hint["aspect"], hint["text"]))


def playable() -> list[str]:
    """Every species-table row that can be a target: not toxic-flagged, not a hazard, not on the contact list."""
    flags = json.loads(TOXICITY.read_text())["species"]
    contact = listed()
    return sorted(r for r, f in flags.items() if not f["toxic"] and not is_hazard(r) and r not in contact)


def save(species: dict, done: bool) -> None:
    """Write hints.json with the rule that made it; `done` false marks a checkpoint to resume from."""
    HINTS.write_text(
        json.dumps(
            {
                "built": date.today().isoformat(),
                "done": done,
                "model": MODEL,
                "rule": {"sampler": SAMPLER, "num_ctx": NUM_CTX, "per_aspect": PER_ASPECT, "aspects": list(ASPECTS)},
                "species": species,
            },
            indent=1,
            ensure_ascii=False,
        )
        + "\n"
    )


def recheck() -> int:
    """Re-apply the pattern and duplicate checks to the stored model hints and re-rank, without the model.

    Raises on an unfinished checkpoint, since marking it done would ship a file missing most rows.
    """
    data = json.loads(HINTS.read_text())
    if not data["done"]:
        raise ValueError(f"{HINTS.name} is an unfinished checkpoint; finish make hints before --recheck")
    species = data["species"]
    added = 0
    for entry in species.values():
        said: set[str] = set()
        for hint in entry["hints"]:
            if hint["source"] != "model":
                continue
            for name, pattern in (("water", WATER), ("road", ROAD), ("height", HEIGHT)):
                if pattern.search(hint["text"]) and name not in hint["issues"]:
                    hint["issues"].append(name)
                    added += 1
            key = hint["text"].casefold()
            if key in said and "repeats an earlier hint" not in hint["issues"]:
                hint["issues"].append("repeats an earlier hint")
                added += 1
            if not hint["issues"] or hint["issues"] == ["repeats an earlier hint"]:
                said.add(key)
    rank_all(species)
    save(species, done=True)
    print(f"OK: {HINTS}: {added} model hints newly flagged")
    return 0


def main() -> int:
    """Write hints.json for every playable row, resuming an unfinished file; `--recheck` only re-applies checks."""
    if "--recheck" in sys.argv:
        return recheck()
    rows = playable()
    # Only an unfinished checkpoint resumes. A finished file is rebuilt from scratch, so a changed prompt, model,
    # source, or trait rule shows up in the output instead of being reused under a fresh build date.
    previous = json.loads(HINTS.read_text()) if HINTS.exists() else {"done": True, "species": {}}
    species = {} if previous["done"] else previous["species"]
    todo = [r for r in rows if r not in species]
    print(f"{len(rows)} playable rows, {len(species)} done, {len(todo)} to go", flush=True)
    pages = articles(todo)
    facts = usda_traits(usda.archive(), TRAITS + USDA_TRAITS)
    kinds = json.loads(PLANT_TYPES.read_text())["species"]
    aliases = json.loads(SYNONYMS.read_text())["species"]
    start = time.perf_counter()
    for n, row in enumerate(todo, 1):
        species[row] = hints_for(row, pages[row], aliases.get(row, []), kinds.get(row, {}).get("type"), facts)
        if n % CHECKPOINT == 0:
            save(species, done=False)
            print(f"{n}/{len(todo)} {time.perf_counter() - start:.0f}s", flush=True)
    rank_all(species)
    save(species, done=True)
    failed = [r for r, e in species.items() if e.get("failed")]
    print(f"OK: {HINTS}: {len(species)} rows, {len(failed)} model replies failed: {failed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
