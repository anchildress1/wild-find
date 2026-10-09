"""Pick the best few hints per plant from every hint it has a source for.

A candidate is {"aspect", "text", "support", "bucket"}. Unsupported candidates are dropped, never ranked low. The rest
score by how much the aspect narrows a kid's search, how well sourced the text is, and how rare its bucket is across
the batch: a hint that fits half the plants ("look in the sun") tells a kid less than one that fits few.
"""

from collections import Counter

# Search-narrowing value of each aspect: 3 = where to stand, 2 = what to scan for, 1 = nice to know.
TIER = {
    "place": 3,
    "ground": 3,
    "light": 3,
    "season": 3,
    "nearby": 2,
    "sign": 2,
    "size": 1,
    "edges": 1,
    "range": 1,
}
# A quote checked against the Wikipedia article beats a USDA rating or trait, which needs a decoded or templated step.
SUPPORT = {"article": 1.0, "usda": 0.8}
PICKS = 3


def shares(batch: list[list[dict]]) -> dict[tuple[str, str], float]:
    """Fraction of plants in the batch that have each (aspect, bucket); candidates without a bucket are skipped."""
    counts = Counter(
        key for plant in batch for key in {(c["aspect"], c["bucket"]) for c in plant if c.get("bucket") is not None}
    )
    return {key: n / len(batch) for key, n in counts.items()}


def score(candidate: dict, common: dict[tuple[str, str], float]) -> float:
    """Tier x support x rarity; 0 for an unknown aspect or unsupported source."""
    tier = TIER.get(candidate["aspect"], 0)
    support = SUPPORT.get(candidate["support"], 0.0)
    rarity = 1.0 - common.get((candidate["aspect"], candidate.get("bucket")), 0.0)
    return tier * support * rarity


def rank(candidates: list[dict], common: dict[tuple[str, str], float], picks: int = PICKS) -> list[dict]:
    """The top `picks` scoring candidates, each with its score, best first; ties keep the TIER table's order.

    Every season candidate is kept on top of the picks: which one fits is the device month's call at play time.
    """
    order = list(TIER)
    scored = [{**c, "score": round(score(c, common), 3)} for c in candidates]
    kept = [c for c in scored if c["score"] > 0]
    kept.sort(key=lambda c: (-c["score"], order.index(c["aspect"])))
    others = [c for c in kept if c["aspect"] != "season"][:picks]
    return others + [c for c in kept if c["aspect"] == "season"]
