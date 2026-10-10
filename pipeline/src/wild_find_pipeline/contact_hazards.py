"""Build-time contact-hazard list: local Gemma 4 26b decides whether touching a species-table row hurts skin or eyes.

Only urushiol, phototoxic sap, stinging hairs, and irritant sap or latex count; spines, eating, and livestock do not.
A sentence regex picks the rows worth asking, and the model must quote the article sentence it relied on and write
one short kid line for the warning card. Rows whose answer passes every check ship; unsure rows and rows that fail a
check go to contact_hazards_review.json, where only owner-approved rows ship.

Writes pipeline/data/contact_hazards.json. An interrupted run resumes from its unfinished file and loses at most one
checkpoint; a finished file is rebuilt from scratch.
"""

import json
import re
import sys
import time
from datetime import date

from wild_find_pipeline import gemma
from wild_find_pipeline.descriptions import ACTIONS, BANNED
from wild_find_pipeline.gemma import MODEL, NUM_CTX, SAMPLER, squash
from wild_find_pipeline.paths import CONTACT_HAZARDS, CONTACT_REVIEW, TOXICITY
from wild_find_pipeline.toxicity import SENTENCE, articles

KINDS = ("urushiol", "phototoxic", "stinging", "irritant_sap")
VERDICTS = ("hazard", "no", "unsure")
MAX_NEW_TOKENS = 300
LEAD_SENTENCES = 2
MAX_EXCERPT_SENTENCES = 30
MAX_LINE_WORDS = 12
CHECKPOINT = 25
# Sentences that can say touching the plant hurts; a row with none skips the model.
CONTACT = re.compile(
    r"\b(?:urushiol\w*|phototox\w*|furanocoumarin\w*|psoralen\w*|photosensiti\w*|dermatitis|blister\w*|rash(?:es)?|"
    r"sting\w*|trichomes?|irritat\w*|latex|burn\w*|itch\w*|urticari\w*|vesicant|caustic)\b",
    re.I,
)
SKIN = re.compile(r"\bskin\b", re.I)
SAP = re.compile(r"\b(?:sap|juice|milky)\b", re.I)
# Evidence about sharp parts with nothing chemical in it is a scratch, which the owner ruled out on Oct 9.
SPINES = re.compile(r"\b(?:thorns?|thorny|spines?|spiny|spinose|prickles?|prickly|glochids?|burr?s?)\b", re.I)
CHEMICAL = re.compile(
    r"\b(?:urushiol|phototox\w*|furanocoumarin\w*|psoralen\w*|photosensiti\w*|sap|latex|juice|oils?|sting\w*|hairs?|"
    r"trichomes?|sunlight|chemicals?|toxins?|resin)\b",
    re.I,
)
# The card describes what the plant does to skin; scary words belong to the toxic flag, not a kid's warning.
SCARY = re.compile(r"\b(?:poison\w*|toxic\w*|deadly|die|dies|death|kills?)\b", re.I)
REPLY_FAILED = "model reply failed"
QUOTE_MISSING = "evidence not in article"
CUT_OFF = "prompt cut off"

SYSTEM = """# Task
Decide whether touching or handling the living plant (leaves, stems, sap, hairs, fruit) hurts anyone's skin or eyes.

# Counts
- An oil like urushiol that gives an itchy rash.
- Sap that burns or blisters skin in sunlight (phototoxic, furanocoumarins).
- Stinging hairs.
- Caustic sap or latex that blisters, burns, or inflames skin or eyes.

# Does not count; the verdict is "no"
- An allergy, or a reaction only "in some people" or "sensitive people".
- Work exposure or a processed material: sawdust, lumber, wood, pollen, essential oil, extract, or husk or burr fibers
  handled in bulk.
- A side effect of the plant used as medicine.
- Harm from eating or swallowing any part.
- Harm to livestock, pets, or other animals only.
- Stinging eyes or sinuses from cutting or grating a root.
- Spines, thorns, prickles, burs, or sharp edges that only scratch or poke.
- Pollen allergies, hay fever, or sneezing.

# Source
You get sentences from the plant's Wikipedia article. Use only what they say. Never add anything from memory.

# Output
JSON with four fields.
- "verdict": "hazard" when a sentence says touching or handling this living plant hurts skin or eyes in general;
  "unsure" when a sentence hints at that but is vague or about a relative; "no" otherwise.
- "kind": "urushiol", "phototoxic", "stinging", or "irritant_sap" for a hazard; null otherwise.
- "evidence": the one sentence you relied on, copied word for word from the article sentences; "" for "no".
- "line": for a hazard, one sentence for a child age 8, 12 words or fewer, saying what the plant does to skin. Never
  write the plant's name. Never use the words poison, toxic, safe, eat, touch, pick, or taste. "" otherwise."""
SCHEMA = {
    "type": "object",
    "properties": {
        "verdict": {"type": "string", "enum": list(VERDICTS)},
        "kind": {"type": ["string", "null"], "enum": [*KINDS, None]},
        "evidence": {"type": "string"},
        "line": {"type": "string"},
    },
    "required": ["verdict", "kind", "evidence", "line"],
}
# Two worked examples: a phototoxic plant with a livestock sentence to pass over, and a "no" whose only skin claims
# are an allergy in sensitive people and sawdust.
SHOTS = (
    (
        "Plant: Ruta graveolens\n\nArticle sentences:\n"
        "Ruta graveolens, commonly known as rue, is a species of Ruta grown as an ornamental plant and herb.\n"
        "It is native to the Balkan Peninsula.\n"
        "The sap contains furanocoumarins, and contact followed by sunlight can cause severe blistering of the skin.\n"
        "Rue is toxic to livestock in large doses.",
        json.dumps(
            {
                "verdict": "hazard",
                "kind": "phototoxic",
                "evidence": (
                    "The sap contains furanocoumarins, and contact followed by sunlight can cause severe blistering "
                    "of the skin."
                ),
                "line": "Its sap can make skin blister in sunlight.",
            }
        ),
    ),
    (
        "Plant: Robinia pseudoacacia\n\nArticle sentences:\n"
        "Robinia pseudoacacia, commonly known as black locust, is a medium-sized hardwood deciduous tree.\n"
        "It is native to the southeastern United States.\n"
        "The sawdust and shavings from the lumber can cause contact dermatitis in sensitive persons.\n"
        "Some people develop an allergic skin rash after handling the flowers.",
        json.dumps({"verdict": "no", "kind": None, "evidence": "", "line": ""}),
    ),
)


def touches(sentence: str) -> bool:
    """True when a sentence can say contact with the plant hurts."""
    return bool(CONTACT.search(sentence) or (SKIN.search(sentence) and SAP.search(sentence)))


def excerpt(text: str) -> str | None:
    """The article's lead plus its contact sentences in article order, or None when no sentence touches on contact."""
    sentences = [" ".join(s.split()) for s in SENTENCE.findall(text)]
    sentences = [s for s in sentences if s]
    hits = [s for s in sentences if touches(s)]
    if not hits:
        return None
    lead = [s for s in sentences if len(s) > 20][:LEAD_SENTENCES]
    return "\n".join(lead + [s for s in hits if s not in lead][:MAX_EXCERPT_SENTENCES])


def names_of(row: str, article: str | None) -> list[str]:
    """The words a kid line must not contain: the article title and the binomial's two parts."""
    return [n for n in (article, *row.split()[:2]) if n]


def issues(verdict: str, kind: str | None, evidence: str, line: str, names: list[str]) -> list[str]:
    """Every check a hazard or unsure answer fails, except the quote check, which needs the article; empty for "no"."""
    if verdict == "no":
        return []
    found = []
    if verdict == "hazard" and kind not in KINDS:
        found.append("no kind")
    if SPINES.search(evidence) and not CHEMICAL.search(evidence):
        found.append("spines only")
    if verdict == "hazard" and not line:
        found.append("line missing")
    lowered = line.lower()
    if len(line.split()) > MAX_LINE_WORDS:
        found.append("line too long")
    # Whole words, so a short title or epithet like "Ilex" never matches inside another word.
    if any(re.search(rf"\b{re.escape(name)}\b", line, re.I) for name in names):
        found.append("line names the plant")
    if any(word in lowered for word in BANNED) or ACTIONS.search(line):
        found.append("line banned word")
    if SCARY.search(line):
        found.append("line scary word")
    return found


def status(verdict: str, found: list[str]) -> str:
    """ "auto" for a hazard that passed every check, "skip" for a no, and "review" for the rest."""
    if verdict == "no":
        return "skip"
    return "auto" if verdict == "hazard" and not found else "review"


def parse(text: str) -> dict:
    """Reply JSON to verdict, kind, evidence, and line; raises ValueError on anything off-schema."""
    try:
        data = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError(f"unreadable reply: {text[:80]!r}") from error
    if not isinstance(data, dict) or data.get("verdict") not in VERDICTS or data.get("kind") not in (*KINDS, None):
        raise ValueError(f"off-schema reply: {text[:80]!r}")
    if not isinstance(data.get("evidence"), str) or not isinstance(data.get("line"), str):
        raise ValueError(f"off-schema reply: {text[:80]!r}")
    kind = data["kind"] if data["verdict"] == "hazard" else None
    return {
        "verdict": data["verdict"],
        "kind": kind,
        "evidence": data["evidence"].strip(),
        "line": data["line"].strip(),
    }


def chat(user: str) -> dict:
    """One contact-hazard turn to the local Gemma; raises on a reply that is cut short or empty."""
    return gemma.chat(SYSTEM, SHOTS, SCHEMA, user, MAX_NEW_TOKENS)


def entry_for(row: str, page: dict | None) -> dict:
    """One row's verdict with its quote, kid line, failed checks, and status; skips the model without a contact line."""
    base = {"article": page["title"] if page else None, "revid": page["revid"] if page else None}
    skip = {"verdict": "no", "kind": None, "evidence": "", "line": "", "issues": [], "status": "skip"}
    if page is None:
        return {**base, **skip, "reason": "no article"}
    shown = excerpt(page["text"])
    if shown is None:
        return {**base, **skip, "reason": "no contact sentence"}
    try:
        reply = chat(f"Plant: {row}\n\nArticle sentences:\n{shown}")
        answer = parse(reply["text"])
    except ValueError as error:
        # One runaway reply must not end the run; the row goes to review and says why.
        return {
            **base,
            **{"verdict": "unsure", "kind": None, "evidence": "", "line": ""},
            "issues": [REPLY_FAILED],
            "status": "review",
            "reason": str(error),
        }
    found = []
    if answer["verdict"] != "no" and (not answer["evidence"] or squash(answer["evidence"]) not in squash(page["text"])):
        found.append(QUOTE_MISSING)
    if reply["cut_off"]:
        found.append(CUT_OFF)
    found += issues(**answer, names=names_of(row, page["title"]))
    return {**base, **answer, "issues": found, "status": status(answer["verdict"], found)}


def review_entries(species: dict[str, dict], old: dict[str, dict]) -> dict[str, dict]:
    """One entry per "review" row plus every owner veto on an "auto" row, keeping decisions already made."""
    blank = {"triage": None, "note": "", "owner": None}
    return {
        row: old.get(row, blank)
        for row, e in sorted(species.items())
        if e["status"] == "review" or (e["status"] == "auto" and old.get(row, {}).get("owner") is False)
    }


def listed(data: dict | None = None, review: dict | None = None) -> dict[str, str | None]:
    """Every shipped contact-hazard row to its kid line, or None when its line failed a check.

    Ships "auto" rows the owner did not veto and "review" rows the owner approved. Reads the committed files when not
    given; raises on an unfinished checkpoint.
    """
    if data is None:
        data = json.loads(CONTACT_HAZARDS.read_text())
    if review is None:
        review = json.loads(CONTACT_REVIEW.read_text())
    if not data["done"]:
        raise ValueError(f"{CONTACT_HAZARDS.name} is a partial checkpoint; run make contact-hazards to finish it")
    out = {}
    for row, entry in data["species"].items():
        owner = review.get(row, {}).get("owner")
        if (entry["status"] == "auto" and owner is not False) or (entry["status"] == "review" and owner is True):
            clean = entry["line"] and not any(i.startswith("line ") for i in entry["issues"])
            out[row] = entry["line"] if clean else None
    return out


def save(species: dict, done: bool, digest: str) -> None:
    """Write contact_hazards.json with the rule that made it, plus the review file once done."""
    CONTACT_HAZARDS.write_text(
        json.dumps(
            {
                "built": date.today().isoformat(),
                "done": done,
                "model": MODEL,
                "digest": digest,
                "rule": {
                    "kinds": list(KINDS),
                    "sampler": SAMPLER,
                    "num_ctx": NUM_CTX,
                    "prefilter": {"contact": CONTACT.pattern, "skin": SKIN.pattern, "sap": SAP.pattern},
                },
                "species": species,
            },
            indent=1,
            ensure_ascii=False,
        )
        + "\n"
    )
    if done:
        old = json.loads(CONTACT_REVIEW.read_text()) if CONTACT_REVIEW.exists() else {}
        CONTACT_REVIEW.write_text(json.dumps(review_entries(species, old), indent=1, ensure_ascii=False) + "\n")


def counts(species: dict[str, dict]) -> dict[str, int]:
    """Rows per status."""
    out = {"auto": 0, "review": 0, "skip": 0}
    for entry in species.values():
        out[entry["status"]] += 1
    return out


def recheck() -> int:
    """Re-apply every check but the quote check to the stored answers and refresh the review file, without the model.

    Raises on an unfinished checkpoint, since marking it done would ship a file missing most rows.
    """
    data = json.loads(CONTACT_HAZARDS.read_text())
    if not data["done"]:
        raise ValueError(f"{CONTACT_HAZARDS.name} is an unfinished checkpoint; finish make contact-hazards first")
    species = data["species"]
    for row, entry in species.items():
        if "reason" in entry:
            continue
        # The quote check needs the article text, which the file does not keep; its stored result stands.
        kept = [i for i in entry["issues"] if i in (QUOTE_MISSING, CUT_OFF)]
        names = names_of(row, entry["article"])
        entry["issues"] = kept + issues(entry["verdict"], entry["kind"], entry["evidence"], entry["line"], names)
        entry["status"] = status(entry["verdict"], entry["issues"])
    save(species, done=True, digest=data["digest"])
    print(f"OK: {CONTACT_HAZARDS}: {counts(species)}")
    return 0


def main() -> int:
    """Write contact_hazards.json for every species-table row, resuming an unfinished file; `--recheck` only checks."""
    if "--recheck" in sys.argv:
        return recheck()
    rows = sorted(json.loads(TOXICITY.read_text())["species"])
    digest = gemma.digest()
    # Only an unfinished checkpoint resumes, and only on the same model build; a finished file is rebuilt from scratch.
    previous = json.loads(CONTACT_HAZARDS.read_text()) if CONTACT_HAZARDS.exists() else {"done": True}
    if not previous["done"] and previous["digest"] != digest:
        raise ValueError(f"checkpoint came from {MODEL} {previous['digest']}, now {digest}; delete it to rebuild")
    species = {} if previous["done"] else previous["species"]
    todo = [r for r in rows if r not in species]
    print(f"{MODEL} {digest}: {len(rows)} rows, {len(species)} done, {len(todo)} to go", flush=True)
    pages = articles(todo)
    asked = sum(1 for r in todo if pages[r] and excerpt(pages[r]["text"]) is not None)
    print(f"prefilter: {asked} of {len(todo)} rows have a contact sentence and go to the model", flush=True)
    start = time.perf_counter()
    for n, row in enumerate(todo, 1):
        species[row] = entry_for(row, pages[row])
        if n % CHECKPOINT == 0:
            save(species, done=False, digest=digest)
            print(f"{n}/{len(todo)} {time.perf_counter() - start:.0f}s", flush=True)
    save(species, done=True, digest=digest)
    failed = [r for r, e in species.items() if REPLY_FAILED in e["issues"]]
    print(
        f"OK: {CONTACT_HAZARDS}: {len(species)} rows {counts(species)} in {time.perf_counter() - start:.0f}s, "
        f"{len(failed)} model replies failed: {failed}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
