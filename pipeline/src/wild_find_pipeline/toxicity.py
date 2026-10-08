"""Build step 2: one toxicity flag per species-table row from English Wikipedia text and USDA PLANTS.

Writes the committed pipeline/data/toxicity.json; it needs about 300 network requests, so CI never runs it.
PRD rule: flag on a toxicity sentence, a USDA rating of moderate or severe, a stub, or no article at all.
"""

import contextlib
import csv
import email.utils
import io
import json
import re
import sys
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import UTC, date, datetime
from pathlib import Path

from wild_find_pipeline.paths import MODEL_CACHE, TOXICITY, ensure_artifact, file_sha256

USER_AGENT = "wild-find-pipeline/0.1 (+https://github.com/anchildress1/wild-find)"
WIKIPEDIA = "https://en.wikipedia.org/w/api.php"
GBIF = "https://api.gbif.org/v1"
# USDA PLANTS traits as published to Zenodo on Dec 11, 2025; pinned by bytes, like the models.
USDA_URL = "https://zenodo.org/api/records/17903503/files/usda_plant_traits.tar.gz/content"
USDA_SHA256 = "d646ab96b3308a51f66bf5adfe3ac7e31abd68223c9436734789c96da9df014b"
USDA_TOXIC = {
    "http://purl.obolibrary.org/obo/PATO_0000395": "moderate",
    "http://purl.obolibrary.org/obo/PATO_0000396": "severe",
}
# Plain-text length under which an article is a stub; its silence on toxicity proves nothing.
MIN_CHARS = 1500
BATCH = 50
# Other plants' names that carry the keywords; removed first so a mention isn't a claim.
OTHER_PLANTS = re.compile(r"\bpoison[- ](?:ivy|ivies|oak|oaks|sumac|sumacs|hemlock)\b", re.I)
# Words that say the opposite; removed too, so "non-toxic" isn't read as a toxicity claim.
NOT_TOXIC = re.compile(r"\bnon-?(?:toxic|poisonous)\b", re.I)
# Whole words only: "Toxicodendron" must not match.
TOXIC = re.compile(r"\b(?:toxic|toxicity|toxins?|poisons?|poisonous|poisoning)\b", re.I)
SENTENCE = re.compile(r"[^.!?\n]+[.!?]?")
# Sections whose citation titles and links say "toxic" about other things.
SKIP_SECTIONS = re.compile(r"^(?:references|notes|citations|sources|further reading|external links|see also)$", re.I)


def retry_after(value: str | None) -> float:
    """Seconds to wait from a Retry-After header, in delta seconds or an HTTP date; 10 when absent or unreadable."""
    if value and value.strip().isdigit():
        return float(value)
    if value:
        try:
            return max(0.0, (email.utils.parsedate_to_datetime(value) - datetime.now(UTC)).total_seconds())
        except (TypeError, ValueError):
            pass
    return 10.0


def get(url: str) -> dict:
    """GET JSON with the named User-Agent, up to 5 tries: 429 and 503 wait per Retry-After, network errors back off.

    Raises the last error once the tries run out, and any other HTTP error at once.
    """
    for attempt in range(5):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as e:
            if attempt == 4 or e.code not in (429, 503):
                raise
            time.sleep(retry_after(e.headers.get("Retry-After")))
        except (urllib.error.URLError, TimeoutError):
            if attempt == 4:
                raise
            time.sleep(2**attempt)
    raise AssertionError("unreachable")


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


def flag(text: str | None, usda: str | None) -> tuple[bool, str]:
    """PRD toxicity rule for one species; returns the flag and its evidence."""
    if text is not None and (sentence := toxic_sentence(text)):
        return True, f"wikipedia: {sentence[:300]}"
    if usda:
        return True, f"usda: {usda}"
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
    csv.field_size_limit(sys.maxsize)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        # The published archive stores members as ./name.tab.
        members = {Path(m.name).name: m for m in tar.getmembers()}

        def rows(member: str):
            text = tar.extractfile(members[member]).read().decode()
            return csv.DictReader(io.StringIO(text), delimiter="\t", quoting=csv.QUOTE_NONE)

        levels = {
            r["occurrenceID"]: USDA_TOXIC[r["measurementValue"]]
            for r in rows("measurement_or_fact_specific.tab")
            if r["measurementType"].endswith("HumanLivestockToxicity") and r["measurementValue"] in USDA_TOXIC
        }
        taxa = {r["occurrenceID"]: r["taxonID"] for r in rows("occurrence_specific.tab") if r["occurrenceID"] in levels}
        names = {r["taxonID"]: " ".join(r["scientificName"].split()[:2]) for r in rows("taxon.tab")}
    unnamed = sorted(str(taxa.get(o)) for o in levels if taxa.get(o) not in names)
    if unnamed:
        # The published export lacks a few taxon rows; say which instead of dropping them silently.
        print(f"usda: {len(unnamed)} moderate/severe rows have no taxon row and are skipped: {unnamed}")
    return {names[taxa[o]]: level for o, level in levels.items() if taxa.get(o) in names}


def usda_archive() -> bytes:
    """The pinned USDA PLANTS traits archive, cached in .models and checked by SHA-256."""
    MODEL_CACHE.mkdir(exist_ok=True)
    path = MODEL_CACHE / "usda_plant_traits.tar.gz"
    if not path.is_file() or file_sha256(path) != USDA_SHA256:
        request = urllib.request.Request(USDA_URL, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=300) as response:
            path.write_bytes(response.read())
    if file_sha256(path) != USDA_SHA256:
        raise ValueError(f"{path} does not match its pinned SHA-256")
    return path.read_bytes()


def with_synonyms(ratings: dict[str, str]) -> dict[str, str]:
    """Add every GBIF backbone synonym and accepted name of each rated binomial, so naming drift still matches."""
    out = dict(ratings)
    for name, level in ratings.items():
        match = get(
            f"{GBIF}/species/match?" + urllib.parse.urlencode({"name": name, "kingdom": "Plantae", "strict": "true"})
        )
        key = match.get("acceptedUsageKey") or match.get("usageKey")
        if not key:
            continue
        accepted = get(f"{GBIF}/species/{key}")
        synonyms = get(f"{GBIF}/species/{key}/synonyms?limit=1000")["results"]
        for usage in [accepted, *synonyms]:
            binomial = " ".join(usage.get("canonicalName", "").split()[:2])
            if binomial.count(" ") == 1:
                out.setdefault(binomial, level)
        time.sleep(0.2)
    return out


def main() -> int:
    """Write toxicity.json: flag, evidence, and article revision for every species-table row."""
    from wild_find_pipeline.labels import HAZARDS

    names = [e["scientific"] for e in json.loads(ensure_artifact("taxa_labels").read_text())]
    names += [n for n in HAZARDS.values() if n not in names and " " in n]
    usda = with_synonyms(usda_ratings(usda_archive()))
    pages = articles(sorted(set(names)))
    species = {}
    for name in sorted(set(names)):
        page = pages[name]
        toxic, evidence = flag(page["text"] if page else None, usda.get(name))
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
                "usda": {"url": USDA_URL, "sha256": USDA_SHA256, "moderate_or_severe": len(usda)},
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
