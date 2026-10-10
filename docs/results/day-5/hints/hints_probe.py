"""S72 probe: rerun the tightened hints prompt and checks on the 50 Oct 10 spot-check plants only.

Counts how many of the 13 graded misses are gone and how many of the 37 good hints survive (over all 37, and over
the 29 that break no S72 rule). A graded hint survives when the new run keeps a clean hint of the same kind that
cites the same quote; a miss is gone when it doesn't. The loose count also keeps a good item whose row still has a
clean hint of that kind from another sentence, or the same quote under another kind, since a rerun often moves a hint. Writes hints_probe.json and hints_probe.log next to this file.

Run: uv run --project pipeline --group reference python -I docs/results/day-5/hints/hints_probe.py
"""

import json
import re
import time
from pathlib import Path

from wild_find_pipeline import gemma, hints, usda
from wild_find_pipeline.hint_traits import TRAITS, usda_traits
from wild_find_pipeline.paths import PLANT_TYPES, SYNONYMS
from wild_find_pipeline.toxicity import articles

HERE = Path(__file__).parent
GRADED = HERE.parents[1] / "day-4/hints_spotcheck.md"
ITEM = re.compile(
    r"## (\d+)\. \*(.+?)\* \((\w+), model\)\n\n- \*\*Hint:\*\* (.*)\n- \*\*Quote:\*\* (.*)\n.*\n- \*\*Verdict:\*\* (\w+)"
)
# The eight `y` items an S72 rule rejects by design (owner decision, Oct 10).
PINNED = {1, 17, 18, 19, 31, 36, 37, 42}


def main() -> None:
    """Run the probe and write its JSON and log."""
    items = [
        (int(n), row, aspect, hint, quote, v)
        for n, row, aspect, hint, quote, v in ITEM.findall(GRADED.read_text())
    ]
    rows = sorted({i[1] for i in items})
    pages = articles(rows)
    facts = usda_traits(usda.archive(), TRAITS + hints.USDA_TRAITS)
    kinds = json.loads(PLANT_TYPES.read_text())["species"]
    aliases = json.loads(SYNONYMS.read_text())["species"]
    start = time.perf_counter()
    species = {
        row: hints.hints_for(
            row, pages[row], aliases.get(row, []), kinds.get(row, {}).get("type"), facts
        )
        for row in rows
    }
    seconds = time.perf_counter() - start
    # 48 rows are too few to measure which habitats are common, so the generic cut uses the full Oct 9 run's shares.
    full = hints.habitat_shares(json.loads(hints.HINTS.read_text())["species"])
    hints.habitat_shares = lambda _: full
    found = hints.rank_all(species)
    lines = [
        f"S72 probe {time.strftime('%Y-%m-%d')}: {hints.MODEL} digest {gemma.digest()}, sampler {hints.SAMPLER}",
        f"{len(rows)} rows from {GRADED.name}, model time {seconds:.0f}s",
        f"habitat shares from the full Oct 9 run: {hints.shares_text(found)}; cut at {hints.GENERIC_SHARE:.0%}",
        "",
    ]
    tally = {
        "bad gone": 0,
        "good29 loose": 0,
        "bad": 0,
        "good kept": 0,
        "good": 0,
        "good29 kept": 0,
        "good29": 0,
    }
    for n, row, aspect, hint, quote, verdict in items:
        same = [
            h
            for h in species[row]["hints"]
            if h["source"] == "model"
            and h["aspect"] == aspect
            and h["evidence"] == quote
        ]
        kept = [h for h in same if not h["issues"]]
        moved = [
            h
            for h in species[row]["hints"]
            if h["source"] == "model"
            and not h["issues"]
            and (h["aspect"] == aspect or h["evidence"] == quote)
        ]
        good = verdict == "y"
        if good:
            tally["good"] += 1
            tally["good kept"] += bool(kept)
            if n not in PINNED:
                tally["good29"] += 1
                tally["good29 kept"] += bool(kept)
                tally["good29 loose"] += bool(moved)
        else:
            tally["bad"] += 1
            tally["bad gone"] += not kept
        status = (
            ("kept" if kept else "move" if moved else "lost")
            if good
            else ("BACK" if kept else "gone")
        )
        now = (
            "; ".join(f"{h['text']} {h['issues'] or ''}".strip() for h in same)
            or "(quote not cited)"
        )
        lines.append(
            f"{n:>2} {verdict} {status:4} {row} ({aspect}): was {hint!r} -> {now}"
        )
    lines += [
        "",
        f"misses gone: {tally['bad gone']}/{tally['bad']}",
        f"good kept: {tally['good kept']}/{tally['good']} ({tally['good kept'] / tally['good']:.0%})",
        (
            f"good kept, 29 not pinned: {tally['good29 kept']}/{tally['good29']} "
            f"({tally['good29 kept'] / tally['good29']:.0%}); loose {tally['good29 loose']}/{tally['good29']} "
            f"({tally['good29 loose'] / tally['good29']:.0%})"
        ),
    ]
    (HERE / "hints_probe.json").write_text(
        json.dumps(species, indent=1, ensure_ascii=False) + "\n"
    )
    (HERE / "hints_probe.log").write_text("\n".join(lines) + "\n")
    print("\n".join(lines[-3:]))


if __name__ == "__main__":
    main()
