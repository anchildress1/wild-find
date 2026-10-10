"""Build-time "where to look" hints: Gemma 4 26b over each playable row's Wikipedia article, run locally with Ollama.

The model must quote the article sentence each hint comes from, or leave the kind empty. Automatic checks drop made-up
quotes, banned words, the plant's name, long hints, hints that send a kid to water, traffic, or a drop, places a kid
can't use (a country, a biome, a named region), soil a kid can't see, garden-care and hedged quotes, and habitats so
common across the batch that they narrow nothing. A clear USDA shade or wet-soil rating fills a light or ground kind
the model left empty, and hint_traits adds size, season, and sign hints. hint_rank then picks the best three plus
every season hint.

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
HEIGHT = re.compile(
    r"\b(?:cliffs?|bluffs?|ledges?|canyon walls?|crags?|ravines?|gorges?|roofs?|rooftops?|top of houses)\b", re.I
)
ROAD = re.compile(r"\b(?:roads?|roadsides?|highways?|interstates?|streets?|traffic|railroads?|railways?)\b", re.I)
# A place that is a country or region is range, not a spot a kid can walk to.
REGION = re.compile(
    r"\b(?:united states|north america|america|country|states|southeast\w*|northeast\w*|southwest\w*|northwest\w*|"
    r"eastern|western|northern|southern|asia|europe|china|japan|korea|africa|world)\b",
    re.I,
)
# The checks below come from the misses in the owner's Oct 10 spot-check (docs/results/day-4/hints_spotcheck.md).
# Advice on growing the plant read as where it lives: "Seedlings must always be kept moist" became "Keep the soil
# moist". A quote that says the plant escaped cultivation is about the wild, so it stays.
CARE = re.compile(
    r"\b(?:cultivat\w*|plantings?|restor\w*|revegetat\w*|propagat\w*|seedlings?|kept|horticultur\w*|landscap\w*|"
    r"ornamental\w*|gardeners?|suitable for|erosion control)\b",
    re.I,
)
ESCAPED = re.compile(r"\b(?:escap\w*|naturali[sz]\w*)\b", re.I)
# A habitat the quote calls rare: "occasionally in woodland" is not where to look first. "Also grows in" usually names
# a second main habitat, so it passes: rejecting it cost 194 hints on Oct 10.
HEDGE = re.compile(
    r"\b(?:occasionally|rarely|sometimes|infrequently|seldom|sporadically|less (?:commonly|often|frequently))\b",
    re.I,
)
# Biome words a kid can't match to the spot they stand in.
BIOME = re.compile(
    r"\b(?:chaparral|fynbos|renosterveld|strandveld|montane|subalpine|lowlands?|uplands?|deserts?|savann?ah?s?|"
    r"heath(?:s|lands?)?|scrub\w*|shrublands?|steppes?|tundra|taiga|maquis|garrigue|mallee|karoo|pampas|cerrado|"
    r"bosques?|matorral|moors?|moorlands?|sclerophyll\w*|mesic|xeric|littoral|flatwoods|barrens|biomes?|"
    r"ecoregions?|elevations?|altitudes?)\b",
    re.I,
)
# Named regions, from the ones in Oct 9 hints. Case-sensitive, and a name followed by a lowercase word is a plant
# ("Virginia creeper", "Pacific madrone"), so it passes; demonyms stay out for the same reason ("American beech").
PLACE_NAME = re.compile(
    r"\b(?:Alabama|Alaska|Arizona|Arkansas|California|Colorado|Connecticut|Delaware|Florida|Hawaii|Idaho|Illinois|"
    r"Indiana|Iowa|Kansas|Kentucky|Louisiana|Maine|Maryland|Massachusetts|Michigan|Minnesota|Mississippi|Missouri|"
    r"Montana|Nebraska|Nevada|New Hampshire|New Jersey|New Mexico|New York|North Carolina|North Dakota|Ohio|"
    r"Oklahoma|Oregon|Pennsylvania|Rhode Island|South Carolina|South Dakota|Tennessee|Texas|Utah|Vermont|Virginia|"
    r"West Virginia|Wisconsin|Wyoming|Ontario|Quebec|British Columbia|Alberta|Manitoba|Saskatchewan|Nova Scotia|"
    r"Canada|Mexico|Baja|Australia|New Zealand|England|Britain|UK|Scotland|Ireland|Wales|Sonoran|Mojave|"
    r"Chihuahuan|Great Basin|Arctic|Ardennes|Mediterranean|Alps|Pyrenees|Appalachian\w*|Rocky Mountains|"
    r"Sierra Nevada|Cape|Atlantic|Pacific|Himalayas|Andes|Amazon|Georgia|Washington)\b(?!\s+[a-z])"
)
# A quote scoped to somewhere else: "It can be found (for instance in California) in chaparral".
SCOPED = re.compile(r"\b(?:for instance|for example|e\.g\.)\s*,?\s*in\b", re.I)
# Soil a kid can't see or feel; checked on ground hints and any hint that says "soil". "Deep shade" stays a light hint.
SOIL = re.compile(
    r"\b(?:acid\w*|alkalin\w*|calcareous|lim(?:e|y)|loam\w*|deep|permeable|productive|ph|neutral|humus|humic|"
    r"alluvial|friable|serpentine)\b",
    re.I,
)
SAND = re.compile(r"\b(?:sand\w*|gravel\w*|rock\w*|ston\w*)\b", re.I)
WET = re.compile(r"\b(?:damp|wet|moist|soggy|boggy|poorly drained)\b", re.I)
# Stems (plural s dropped) that say nothing about the place, so "You can find it in woodlands" is only "woodland".
FILLER = re.compile(
    r"^(?:"
    r"a|all|along|among|an|and|are|area|around|as|at|be|beside|both|but|by|can|check|common|commonly|different|"
    r"find|for|found|from|grow|growing|habitat|in|into|is|it|its|kind|live|look|many|may|might|most|mostly|near|"
    r"of|often|on|or|other|place|search|see|site|sort|spot|such|the|them|these|they|to|type|under|usually|"
    r"well|where|with|you"
    r")$"
)
# Habitat groups counted across the batch. A hint naming only groups that most rows share narrows nothing.
HABITATS = {
    "woods": r"forests?|woods?|woodlands?|wooded",
    "grass": r"grasslands?|meadows?|fields?|pastures?|prairies?",
    "scrub": r"thickets?|brush",
    "mountains": r"mountains?|mountainous|hills?",
    "gardens": r"gardens?|parks?|lawns?|yards?",
    "open": r"open",
}
HABITAT_WORD = {g: re.compile(rf"^(?:{p})$") for g, p in HABITATS.items()}
# Oct 10, over the Oct 9 run's clean model hints: woods sat in 34% of 2,111 playable rows and grass in 19%; the next
# group (mountains) in 7%. The cut sits in that gap.
GENERIC_SHARE = 0.15
GENERIC = "generic habitat"
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
- A "place" is a kind of habitat a child can walk to, never a country, a state, or a region.
- Pick the smallest thing the sentence names that a child can find where they are: a stream bank, shade, a field
  edge, a fence line. Never use a biome word (chaparral, fynbos, montane, lowland, desert, savanna, heath, scrub).
- A habitat that fits almost any plant (woodland, forest, field, pasture, grassland) is worth a hint only with what
  the sentence says to narrow it ("damp woods", "oak woods", "old growth forests", "forest edges").
- Use the main habitat. Skip anything the sentence says happens occasionally, rarely, or sometimes, and skip
  a habitat the sentence gives only "for instance in" one named region. A sentence that names the plant's range
  can still give a hint; leave the range out of it.
- Ground is only what a child can see or feel: wet or dry, sand, gravel, or rock. Never soil chemistry or depth
  (acid, alkaline, lime, loam, deep, pH). When the sentence says damp or wet first, keep it: "damp, often sandy"
  is damp sandy ground, not just sandy.
- When a sentence mixes something a child can use with a region, a biome word, or soil chemistry, keep the usable
  part: "neutral to acidic, well-draining soils" is ground that drains well.
- Say only what the quoted sentence says. Do not make "moist" into "wet".
- Advice about growing, planting, restoring, or caring for the plant is never where it lives. Skip it.

# Source
You get sentences from the plant's Wikipedia article. Use only what they say. Give a second hint for a kind only when
a different sentence says something different; otherwise give one. Give none only when no sentence says anything a
child could use. Never add anything from memory.

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
    "It turns up along fence lines.",
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
                {"hint": "Look along fence lines.", "evidence": "It turns up along fence lines."},
            ],
        }
    ),
)
# The negative example, on a plant not in the species table with invented sentences: garden care, a hedged and a
# region-scoped habitat, habitats every plant shares, and soil chemistry all give nothing; damp stays with sandy.
SHOT_SKIPS = (
    "Plant: Stellaria holostea\n\nArticle sentences:\n"
    "It is a low plant of hedgerows and woodland edges.\n"
    "It is grown in cottage gardens, and seedlings must be kept moist.\n"
    "It grows in damp, often sandy soil, sometimes on acidic heath.\n"
    "It is found in woodland and grassland, for instance in Wales, and occasionally in montane scrub.\n"
    "It flowers best in partial shade.",
    json.dumps(
        {
            "place": [
                {
                    "hint": "Look along hedgerows and the edges of woods.",
                    "evidence": "It is a low plant of hedgerows and woodland edges.",
                }
            ],
            "light": [{"hint": "It likes part shade.", "evidence": "It flowers best in partial shade."}],
            "ground": [
                {
                    "hint": "Look on damp, sandy ground.",
                    "evidence": "It grows in damp, often sandy soil, sometimes on acidic heath.",
                }
            ],
            "nearby": [],
            "edges": [],
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
    if any(name and name.lower() in hint.lower() for name in names):
        found.append("names the plant")
    return found + text_issues(aspect, hint, evidence)


def text_issues(aspect: str, hint: str, evidence: str) -> list[str]:
    """The checks that need only the hint and its quote, so --recheck can re-apply them without the article."""
    found = []
    lowered = hint.lower()
    if any(word in lowered for word in BANNED) or ACTIONS.search(hint):
        found.append("banned word")
    if len(hint.split()) > MAX_WORDS:
        found.append("too long")
    for name, pattern in (("water", WATER), ("road", ROAD), ("height", HEIGHT)):
        if pattern.search(hint):
            found.append(name)
    if aspect == "place" and REGION.search(hint):
        found.append("region")
    if BIOME.search(hint):
        found.append("biome")
    if PLACE_NAME.search(hint) or SCOPED.search(evidence):
        found.append("named region")
    if (aspect == "ground" or re.search(r"\bsoils?\b", hint, re.I)) and SOIL.search(hint):
        found.append("unseen soil")
    if aspect == "ground" and dropped_wet(hint, evidence):
        found.append("drops the wet")
    if re.search(r"\bkeep\b", hint, re.I) or (CARE.search(evidence) and not ESCAPED.search(evidence)):
        found.append("garden care")
    if hedged(hint, evidence):
        found.append("hedged")
    return found


def stems(text: str) -> set[str]:
    """Lowercase words with a plural s dropped, so "woodlands" meets "woodland"."""
    return {w[:-1] if len(w) > 3 and w.endswith("s") else w for w in re.findall(r"[a-z]+", text.lower())}


def content(hint: str) -> set[str]:
    """The hint's words that say something about the place, as stems."""
    return {w for w in stems(hint) if not FILLER.match(w)}


def hedged(hint: str, evidence: str) -> bool:
    """True when the hint hedges itself, or takes its place only from the quote's words after a hedge."""
    if HEDGE.search(hint):
        return True
    words = content(hint)
    for match in HEDGE.finditer(evidence):
        before, after = stems(evidence[: match.start()]), stems(evidence[match.start() :])
        if words & after and not words & before:
            return True
    return False


def dropped_wet(hint: str, evidence: str) -> bool:
    """True for a sand or rock hint that leaves out the damp or wet the quote says before it."""
    sand, wet = SAND.search(evidence), WET.search(evidence)
    return bool(SAND.search(hint) and not WET.search(hint) and sand and wet and wet.start() < sand.start())


def habitats(hint: str) -> set[str] | None:
    """The habitat groups a hint names when they are all it says, else None."""
    words = content(hint)
    groups = {g for g, word in HABITAT_WORD.items() if any(word.match(w) for w in words)}
    covered = all(any(word.match(w) for word in HABITAT_WORD.values()) for w in words)
    return groups if words and covered else None


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


def habitat_shares(species: dict[str, dict]) -> dict[str, float]:
    """Fraction of rows with a clean model hint naming each habitat group; the generic flag itself doesn't count."""
    counts = dict.fromkeys(HABITATS, 0)
    for entry in species.values():
        named = set()
        for hint in entry["hints"]:
            if hint["source"] == "model" and set(hint["issues"]) <= {GENERIC}:
                words = content(hint["text"])
                named |= {g for g, word in HABITAT_WORD.items() if any(word.match(w) for w in words)}
        for group in named:
            counts[group] += 1
    return {g: n / max(len(species), 1) for g, n in counts.items()}


def flag_generic(species: dict[str, dict]) -> dict[str, float]:
    """Flag model hints that name only habitats at least GENERIC_SHARE of rows share; returns the shares."""
    found = habitat_shares(species)
    generic = {g for g, share in found.items() if share >= GENERIC_SHARE}
    for entry in species.values():
        for hint in entry["hints"]:
            if hint["source"] != "model":
                continue
            # Recomputed on every rank, so a flag from a batch where the habitat was common doesn't stick.
            hint["issues"] = [i for i in hint["issues"] if i != GENERIC]
            if (groups := habitats(hint["text"])) is not None and groups <= generic:
                hint["issues"].append(GENERIC)
        # A generic habitat beats no place hint at all; it still ranks last, since its bucket is common.
        places = [h for h in entry["hints"] if h["source"] == "model" and h["aspect"] == "place"]
        if not any(not h["issues"] for h in places):
            fallback = next((h for h in places if h["issues"] == [GENERIC]), None)
            if fallback is not None:
                fallback["issues"] = []
    return found


def rank_all(species: dict[str, dict]) -> dict[str, float]:
    """Flag generic habitats, then mark each row's picked hints with their score, using rarity across the batch.

    Returns each habitat group's share of rows, so the run can log the cutoff it applied.
    """
    found = flag_generic(species)
    clean = {row: [h for h in entry["hints"] if not h["issues"]] for row, entry in species.items()}
    common = shares([[candidate(h) for h in hints] for hints in clean.values()])
    for row, hints in clean.items():
        picks = rank([candidate(h) for h in hints], common)
        scores = {(p["aspect"], p["text"]): p["score"] for p in picks}
        for hint in species[row]["hints"]:
            hint["score"] = scores.get((hint["aspect"], hint["text"]))
    return found


def shares_text(found: dict[str, float]) -> str:
    """Habitat shares for the log, most common first."""
    return ", ".join(f"{g} {v:.1%}" for g, v in sorted(found.items(), key=lambda kv: -kv[1]))


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


TEXT_CHECKS = frozenset(
    {"banned word", "too long", "water", "road", "height", "region", "biome", "named region", "unseen soil",
     "drops the wet", "garden care", "hedged"}
)  # fmt: skip


def refill() -> int:
    """Ask the model again for every row it gave no hint, keeping every other row as stored; then re-rank."""
    data = json.loads(HINTS.read_text())
    if not data["done"]:
        raise ValueError(f"{HINTS.name} is an unfinished checkpoint; finish make hints before --refill")
    species = data["species"]
    todo = [r for r, e in species.items() if not any(h["source"] == "model" for h in e["hints"])]
    print(f"{len(todo)} rows without a model hint", flush=True)
    pages = articles(todo)
    facts = usda_traits(usda.archive(), TRAITS + USDA_TRAITS)
    kinds = json.loads(PLANT_TYPES.read_text())["species"]
    aliases = json.loads(SYNONYMS.read_text())["species"]
    for row in todo:
        species[row] = hints_for(row, pages[row], aliases.get(row, []), kinds.get(row, {}).get("type"), facts)
    found = rank_all(species)
    save(species, done=True)
    filled = sum(any(h["source"] == "model" and not h["issues"] for h in species[r]["hints"]) for r in todo)
    print(f"OK: {HINTS}: {filled} of {len(todo)} refilled rows got a clean hint; habitats {shares_text(found)}")
    return 0


def recheck() -> int:
    """Re-apply the text, duplicate, and generic-habitat checks to the stored model hints and re-rank; no model.

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
            # Text checks are recomputed, not only added, so a loosened rule clears its old flags.
            kept = [i for i in hint["issues"] if i not in TEXT_CHECKS]
            fresh = text_issues(hint["aspect"], hint["text"], hint["evidence"])
            added += len(set(fresh) - set(hint["issues"]))
            hint["issues"] = kept + fresh
            key = hint["text"].casefold()
            if key in said and "repeats an earlier hint" not in hint["issues"]:
                hint["issues"].append("repeats an earlier hint")
                added += 1
            if not hint["issues"] or hint["issues"] == ["repeats an earlier hint"]:
                said.add(key)
    found = rank_all(species)
    save(species, done=True)
    print(f"OK: {HINTS}: {added} model hints newly flagged; habitat shares {shares_text(found)}")
    return 0


def main() -> int:
    """Write hints.json for every playable row, resuming an unfinished file; `--recheck` and `--refill` patch it."""
    if "--recheck" in sys.argv:
        return recheck()
    if "--refill" in sys.argv:
        return refill()
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
    found = rank_all(species)
    save(species, done=True)
    print(f"habitat shares {shares_text(found)}; cut at {GENERIC_SHARE:.0%}")
    failed = [r for r, e in species.items() if e.get("failed")]
    print(f"OK: {HINTS}: {len(species)} rows, {len(failed)} model replies failed: {failed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
