"""Build step: the other GBIF backbone names of each species-table row, so drifted iNat names still find their row.

Writes the committed pipeline/data/synonyms.json; it needs about 13,000 GBIF requests (cached in .models/gbif), so CI
never runs it. An alias claimed by two rows, or equal to another row's own name, is dropped: it can't pick one row.
Day 3 (docs/results/day-3/name_match.log): raw GBIF synonym lists hold later homonyms and lumped species (iNat's
Quercus lyrata, overcup oak, came back as Q. lobata), so an alias must keep the row's epithet and resolve back to
the row's accepted taxon.
"""

import hashlib
import json
import sys
import urllib.parse
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

from wild_find_pipeline.labels import lacking_hazards
from wild_find_pipeline.paths import MODEL_CACHE, SYNONYMS, ensure_artifact
from wild_find_pipeline.toxicity import GBIF, get

GBIF_CACHE = MODEL_CACHE / "gbif"
WORKERS = 4
PAGE = 1000
# Latin gender endings, so a genus transfer like Cocculus carolinus -> Nephroia carolina keeps its epithet.
ENDINGS = ("us", "um", "a", "is", "e")


def cached_get(url: str, cache: Path = GBIF_CACHE) -> dict:
    """GBIF JSON for a URL, from the on-disk cache when present, so a rerun after a failure doesn't refetch."""
    path = cache / f"{hashlib.sha256(url.encode()).hexdigest()}.json"
    if path.is_file():
        return json.loads(path.read_text())
    reply = get(url)
    cache.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(reply))
    return reply


def binomial(usage: dict) -> str | None:
    """A species-rank usage's canonical name without authors, else None."""
    name = usage.get("canonicalName") or ""
    return name if usage.get("rank") == "SPECIES" and name.count(" ") == 1 else None


def epithet_stem(name: str) -> str:
    """A binomial's epithet without its Latin gender ending."""
    epithet = name.split()[1]
    for ending in ENDINGS:
        if epithet.endswith(ending) and len(epithet) > len(ending) + 2:
            return epithet.removesuffix(ending)
    return epithet


def accepted_key(name: str, fetch: Callable[[str], dict] = cached_get) -> int | None:
    """GBIF key of the accepted taxon a plant name resolves to; None without an exact species-rank match."""
    query = urllib.parse.urlencode({"name": name, "kingdom": "Plantae", "strict": "true"})
    match = fetch(f"{GBIF}/species/match?{query}")
    # A fuzzy or higher-rank match would hand this row another taxon's names.
    if match.get("matchType") != "EXACT" or match.get("rank") != "SPECIES":
        return None
    return match.get("acceptedUsageKey") or match["usageKey"]


def backbone_names(name: str, fetch: Callable[[str], dict] = cached_get) -> list[str] | None:
    """Accepted and species-rank synonym names that keep the table name's epithet and resolve back to its taxon.

    None when the table name has no exact match.
    """
    key = accepted_key(name, fetch)
    if key is None:
        return None
    usages = [fetch(f"{GBIF}/species/{key}")]
    offset = 0
    while True:
        page = fetch(f"{GBIF}/species/{key}/synonyms?limit={PAGE}&offset={offset}")
        usages += page["results"]
        if page.get("endOfRecords", True) or not page["results"]:
            break
        offset += len(page["results"])
    candidates = {b for usage in usages if (b := binomial(usage))} - {name}
    # A synonym listed under this taxon can still be another accepted species' name (a later homonym) when looked
    # up on its own, which is how iNat's name would resolve.
    return sorted(
        alias for alias in candidates if epithet_stem(alias) == epithet_stem(name) and accepted_key(alias, fetch) == key
    )


def aliases(found: dict[str, list[str]]) -> tuple[dict[str, list[str]], list[str], list[str]]:
    """Per row, its names other than its own that point at it alone; also the ambiguous and table-name drops."""
    owners: dict[str, set[str]] = {}
    for row, names in found.items():
        for alias in names:
            if alias != row:
                owners.setdefault(alias, set()).add(row)
    taken = sorted(alias for alias in owners if alias in found)
    ambiguous = sorted(alias for alias, rows in owners.items() if len(rows) > 1 and alias not in found)
    dropped = set(taken) | set(ambiguous)
    species = {row: sorted(a for a in names if a != row and a not in dropped) for row, names in found.items()}
    return species, ambiguous, taken


def main() -> int:
    """Write synonyms.json: the unambiguous GBIF aliases of every species-table row, plus what was dropped."""
    names = [e["scientific"] for e in json.loads(ensure_artifact("taxa_labels").read_text())]
    rows = names + lacking_hazards(names)
    with ThreadPoolExecutor(WORKERS) as pool:
        resolved = dict(zip(rows, pool.map(backbone_names, rows), strict=True))
    unmatched = sorted(row for row, found in resolved.items() if found is None)
    species, ambiguous, taken = aliases({row: found or [] for row, found in resolved.items()})
    SYNONYMS.write_text(
        json.dumps(
            {
                "built": date.today().isoformat(),
                "source": {
                    "api": GBIF,
                    "rule": "species/match strict, kingdom Plantae, EXACT species only; accepted name plus "
                    "/species/{key}/synonyms, SPECIES rank, canonicalName; kept when the epithet matches the row's "
                    "(gender ending aside) and the alias itself matches to the row's accepted key",
                },
                "rows": len(rows),
                "unmatched": unmatched,
                "dropped_ambiguous": len(ambiguous),
                "dropped_table_name": len(taken),
                "ambiguous": ambiguous,
                "table_name": taken,
                "species": species,
            },
            indent=1,
            ensure_ascii=False,
        )
        + "\n"
    )
    total = sum(map(len, species.values()))
    print(
        f"OK: {SYNONYMS}: {total} aliases over {sum(bool(v) for v in species.values())} of {len(rows)} rows; "
        f"{len(unmatched)} unmatched, {len(ambiguous)} ambiguous and {len(taken)} table-name aliases dropped"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
