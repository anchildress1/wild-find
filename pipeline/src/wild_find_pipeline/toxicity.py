"""Build step 2: one toxicity flag per species-table row from English Wikipedia text and USDA PLANTS.

Writes the committed pipeline/data/toxicity.json; it needs about 300 network requests, so CI never runs it.
PRD rule: flag on a toxicity sentence, a USDA rating of moderate or severe, a stub, or no article at all.
"""

import contextlib
import json
import re
import sys
import time
import urllib.parse
from collections.abc import Callable
from datetime import date

from wild_find_pipeline import usda
from wild_find_pipeline.gbif import get, match_url
from wild_find_pipeline.labels import table_rows
from wild_find_pipeline.paths import TOXICITY
from wild_find_pipeline.synonyms import usages

WIKIPEDIA = "https://en.wikipedia.org/w/api.php"
USDA_TOXIC = {
    "http://purl.obolibrary.org/obo/PATO_0000395": "moderate",
    "http://purl.obolibrary.org/obo/PATO_0000396": "severe",
}
# Plain-text length under which an article is a stub; its silence on toxicity proves nothing.
MIN_CHARS = 1500
BATCH = 50
# Other plants' names that carry the keywords; removed first so a mention isn't a claim.
OTHER_PLANTS = re.compile(r"\bpoison[- ](?:ivy|ivies|oak|oaks|sumac|sumacs|hemlock)\b", re.I)
# Words that say the opposite; removed too, so "non-toxic" or "not toxic" isn't read as a toxicity claim.
NOT_TOXIC = re.compile(r"\b(?:non-?|not )(?:toxic|poisonous)\b", re.I)
# Whole words only: "Toxicodendron" must not match.
TOXIC = re.compile(r"\b(?:toxic|toxicity|toxins?|poisons?|poisonous|poisoning)\b", re.I)
SENTENCE = re.compile(r"[^.!?\n]+[.!?]?")
# Sections whose citation titles and links say "toxic" about other things.
SKIP_SECTIONS = re.compile(r"^(?:references|notes|citations|sources|further reading|external links|see also)$", re.I)


def plain(wikitext: str) -> str:
    """Readable article text: no references, templates, markup, or reference-list sections."""
    import mwparserfromhell

    code = mwparserfromhell.parse(wikitext)
    for section in code.get_sections(levels=[2]):
        heading = section.filter_headings()[0].title.strip_code().strip()
        if SKIP_SECTIONS.match(heading):
            code.remove(section)
    for tag in code.filter_tags(matches=lambda node: node.tag == "ref"):
        # A ref nested in another ref is already gone with its parent.
        with contextlib.suppress(ValueError):
            code.remove(tag)
    return code.strip_code(normalize=True, collapse=True)


def toxic_sentence(text: str) -> str | None:
    """First sentence that claims toxicity once other plants' poison names and "non-toxic" are removed, else None."""
    for sentence in SENTENCE.findall(text):
        if TOXIC.search(NOT_TOXIC.sub("", OTHER_PLANTS.sub("", sentence))):
            return " ".join(sentence.split())
    return None


def flag(text: str | None, rating: str | None) -> tuple[bool, str]:
    """PRD toxicity rule for one species; returns the flag and its evidence."""
    if text is not None and (sentence := toxic_sentence(text)):
        return True, f"wikipedia: {sentence[:300]}"
    if rating:
        return True, f"usda: {rating}"
    if text is None:
        return True, "no article"
    if len(text) < MIN_CHARS:
        return True, f"stub: {len(text)} chars"
    return False, ""


def resolve(names: list[str], reply: dict) -> dict[str, dict | None]:
    """Map each requested title through normalization and redirects to its page, or None when missing."""
    query = reply.get("query", {})
    hop = {n["from"]: n["to"] for n in query.get("normalized", [])}
    hop |= {r["from"]: r["to"] for r in query.get("redirects", [])}
    pages = {p["title"]: p for p in query.get("pages", []) if not p.get("missing") and p.get("revisions")}
    out = {}
    for name in names:
        title, seen = name, set()
        while title in hop and title not in seen:
            seen.add(title)
            title = hop[title]
        out[name] = pages.get(title)
    return out


def query_all(params: dict) -> dict:
    """One Wikipedia query with every continuation merged.

    The API stops returning page content once a reply gets too big and hands back a continue token; those pages
    come back without revisions, and treating them as missing would flag them "no article".
    """
    normalized, redirects, pages = [], [], {}
    extra: dict = {}
    while True:
        reply = get(f"{WIKIPEDIA}?{urllib.parse.urlencode({**params, **extra})}")
        query = reply.get("query", {})
        normalized += query.get("normalized", [])
        redirects += query.get("redirects", [])
        for page in query.get("pages", []):
            if page.get("revisions") or page["title"] not in pages:
                pages[page["title"]] = page
        if "continue" not in reply:
            return {"query": {"normalized": normalized, "redirects": redirects, "pages": list(pages.values())}}
        extra = reply["continue"]
        time.sleep(1)


def articles(names: list[str]) -> dict[str, dict | None]:
    """Current English Wikipedia article per scientific name: title, revision id, and plain text, or None."""
    out = {}
    for start in range(0, len(names), BATCH):
        batch = names[start : start + BATCH]
        params = {
            "action": "query",
            "prop": "revisions",
            "rvprop": "ids|content",
            "rvslots": "main",
            "titles": "|".join(batch),
            "redirects": 1,
            "format": "json",
            "formatversion": 2,
        }
        for name, page in resolve(batch, query_all(params)).items():
            if page is None:
                out[name] = None
            else:
                revision = page["revisions"][0]
                text = plain(revision["slots"]["main"]["content"])
                out[name] = {"title": page["title"], "revid": revision["revid"], "text": text}
        print(f"wikipedia: {min(start + BATCH, len(names))}/{len(names)}")
        time.sleep(1)
    return out


def usda_ratings(archive: bytes) -> dict[str, str]:
    """Binomial to USDA HumanLivestockToxicity for every moderate or severe row in the traits archive."""
    found, taxa = usda.measurements(
        archive,
        lambda r: r["measurementType"].endswith("HumanLivestockToxicity") and r["measurementValue"] in USDA_TOXIC,
    )
    unnamed = sorted(str(taxon) for taxon, _ in found if taxon not in taxa)
    if unnamed:
        # The published export lacks a few taxon rows; say which instead of dropping them silently.
        print(f"usda: {len(unnamed)} moderate/severe rows have no taxon row and are skipped: {unnamed}")
    return {usda.species_name(taxa[t]): USDA_TOXIC[r["measurementValue"]] for t, r in found if t in taxa}


def with_synonyms(ratings: dict[str, str], fetch: Callable[[str], dict] = get) -> dict[str, str]:
    """Add the binomial of every GBIF accepted name and synonym, varieties included, of each rated name."""
    out = dict(ratings)
    for name, level in ratings.items():
        match = fetch(match_url(name))
        key = match.get("acceptedUsageKey") or match.get("usageKey")
        if not key:
            continue
        # Deliberately loose: a variety cut to its binomial can flag a relative (Quercus alba via Q. stellata), but a
        # false flag only costs a target, while a missed one can send a kid to a toxic plant.
        for usage in usages(key, fetch):
            binomial = " ".join(usage.get("canonicalName", "").split()[:2])
            if binomial.count(" ") == 1:
                out.setdefault(binomial, level)
        # Live and uncached on purpose: a rebuild must see synonyms GBIF added since the last one. Paced for its API.
        time.sleep(0.2)
    return out


def main() -> int:
    """Write toxicity.json: flag, evidence, and article revision for every species-table row."""
    names = sorted(table_rows())
    ratings = with_synonyms(usda_ratings(usda.archive()))
    pages = articles(names)
    species = {}
    for name in names:
        page = pages[name]
        toxic, evidence = flag(page["text"] if page else None, ratings.get(name))
        species[name] = {
            "toxic": toxic,
            "evidence": evidence,
            "article": page["title"] if page else None,
            "revid": page["revid"] if page else None,
        }
    TOXICITY.write_text(
        json.dumps(
            {
                "built": date.today().isoformat(),
                "rule": {"min_chars": MIN_CHARS, "keywords": TOXIC.pattern, "other_plants": OTHER_PLANTS.pattern},
                "usda": {"url": usda.USDA_URL, "sha256": usda.USDA_SHA256, "moderate_or_severe": len(ratings)},
                "species": species,
            },
            indent=1,
            ensure_ascii=False,
        )
        + "\n"
    )
    flagged = sum(s["toxic"] for s in species.values())
    kinds = {}
    for s in species.values():
        if s["toxic"]:
            kind = s["evidence"].split(":")[0]
            kinds[kind] = kinds.get(kind, 0) + 1
    print(f"OK: {TOXICITY}: {flagged} of {len(species)} flagged {kinds}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
