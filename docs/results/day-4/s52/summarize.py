"""S52 monitor proxy: find rate, wide and look-alike false pass, toxic passes, and timings from gate-harness runs.

Run from the repo root after make gate-pull (runs land in docs/results/gate):
uv run --project pipeline python -I docs/results/day-4/s52/summarize.py [runs dir]

Reads photos.csv (one row per photo shown on the monitor) and runs.csv (order,run: the harness run directory each
photo got; a later row for the same order replaces an earlier one, so a retried photo counts once). One harness run
per photo, so every frame and capture in a run belongs to that photo. Bars: S51 and the PRD success metrics.
"""

import json
import re
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

from wild_find_pipeline.gate_summary import FRAME_BUDGET_MS, NS_PER_MS, NS_PER_S, pct, rows

HERE = Path(__file__).resolve().parent
PHOTOS = HERE / "photos.csv"
RUNS = HERE / "runs.csv"
SLIDESHOW = HERE / "slideshow.html"
DEFAULT_RUNS_DIR = HERE.parents[1] / "gate"
CAPTURE_BUDGET_MS = 1000.0
SCREEN_BAR_S, SCREEN_STRETCH_S = 60.0, 30.0
FIND_BAR = 0.45
FALSE_PASS_BAR = 0.05
SLIDE_ROW = re.compile(r'^\s*\[(\d+), "([^"]+)", "([^"]+)"\],$')


def slideshow_matches(photos: list[dict[str, str]]) -> bool:
    """True when slideshow.html's embedded list is photos.csv's order, file, and target, row for row."""
    embedded = [m.groups() for line in SLIDESHOW.read_text().splitlines() if (m := SLIDE_ROW.match(line))]
    return embedded == [(p["order"], p["file"], p["target_to_set"]) for p in photos]


def photo_result(run: Path) -> dict:
    """What one photo's run measured: outcome, screen time, capture-to-Found, capture and frame times."""
    events = rows(run / "events.csv")
    frames = sorted(rows(run / "frames.csv"), key=lambda r: int(r["t_ns"]))
    # The preview is live from the camera event; start comes earlier, while the camera still binds.
    t0 = next((int(e["t_ns"]) for e in events if e["event"] == "camera"), None)
    if t0 is None:
        t0 = next((int(e["t_ns"]) for e in events if e["event"] == "start"), None)
    if t0 is None:
        # A target the harness can't hunt (not eligible) logs load_error and never starts.
        error = next((e["detail"] for e in events if e["event"].endswith("_error")), "no start event")
        raise ValueError(error)
    captures = [int(e["t_ns"]) for e in events if e["event"] == "capture"]
    done = [int(r["t_ns"]) + float(r["frame_ms"]) * NS_PER_MS for r in frames]
    capture_ms = []
    for i, start in enumerate(captures):
        end = captures[i + 1] if i + 1 < len(captures) else float("inf")
        burst = [d for r, d in zip(frames, done, strict=True) if start <= int(r["t_ns"]) < end]
        if burst:
            capture_ms.append((max(burst) - start) / NS_PER_MS)
    verdicts = [r["verdict"] for r in frames]
    found = next((i for i, v in enumerate(verdicts) if v == "found"), None)
    result = {
        "target": json.loads((run / "run.json").read_text())["target"],
        "captures": len(captures),
        "capture_ms": capture_ms,
        "frame_ms": [float(r["frame_ms"]) for r in frames],
        "hazard": "hazard" in verdicts,
        "found": found is not None,
        "verdicts": Counter(verdicts),
        "screen_s": None,
        "capture_to_found_ms": None,
        "captures_to_found": None,
    }
    if found is not None:
        t_found = int(frames[found]["t_ns"])
        before = [c for c in captures if c <= t_found]
        result["screen_s"] = (done[found] - t0) / NS_PER_S
        result["capture_to_found_ms"] = (done[found] - before[-1]) / NS_PER_MS
        result["captures_to_found"] = len(before)
    return result


def outcome(r: dict) -> str:
    """One word for a photo's run: found, hazard, no_capture, or miss with its commonest verdict."""
    if r["found"]:
        return "found"
    if r["hazard"]:
        return "hazard"
    if not r["verdicts"]:
        return "no_capture"
    return "miss:" + r["verdicts"].most_common(1)[0][0]


def rate(hits: int, n: int) -> str:
    """hits/n with a percentage, or n/a for none."""
    return f"{hits}/{n} ({hits / n:.0%})" if n else "n/a"


def found(group: list[tuple[dict, dict]]) -> int:
    """How many of a set's photos reached Found."""
    return sum(r["found"] for _, r in group)


def bar(ok: bool) -> str:
    """MET or MISSED."""
    return "MET" if ok else "MISSED"


def report(photos: list[dict[str, str]], results: dict[str, dict]) -> list[str]:
    """Per-photo lines, then the S52 numbers against their bars."""
    lines = ["order set       target                      outcome            caps  screen_s  cap_to_found_ms  laptop"]
    for p in photos:
        r = results.get(p["order"])
        if r is None:
            lines.append(f"{p['order']:>5} {p['set']:<9} {p['target_to_set']:<27} not run")
            continue
        screen = f"{r['screen_s']:.1f}" if r["screen_s"] is not None else "-"
        c2f = f"{r['capture_to_found_ms']:.0f}" if r["capture_to_found_ms"] is not None else "-"
        laptop = "pass" if p["laptop_pass"] == "True" else "no"
        lines.append(
            f"{p['order']:>5} {p['set']:<9} {p['target_to_set']:<27} {outcome(r):<18} {r['captures']:>4}"
            f"  {screen:>8}  {c2f:>15}  {laptop}"
        )
    by_set = defaultdict(list)
    for p in photos:
        if p["order"] in results:
            by_set[p["set"]].append((p, results[p["order"]]))
    close, wide, look, toxic = (by_set[s] for s in ("close", "wide", "lookalike", "toxic"))
    lines += ["", f"photos run: {len(results)} of {len(photos)}"]
    per_target = defaultdict(lambda: [0, 0])
    for p, r in close:
        per_target[p["target_to_set"]][0] += r["found"]
        per_target[p["target_to_set"]][1] += 1
    lines.append(
        "find rate per target (close): " + ", ".join(f"{t} {h}/{n}" for t, (h, n) in sorted(per_target.items()))
    )
    if close:
        lines.append(
            f"find rate, close photos (bar >= {FIND_BAR:.0%}): {rate(found(close), len(close))} -> "
            f"{bar(found(close) / len(close) >= FIND_BAR)}"
        )
    if wide:
        lines.append(
            f"wide-shot pass (bar <= {FALSE_PASS_BAR:.0%}): {rate(found(wide), len(wide))} -> "
            f"{bar(found(wide) / len(wide) <= FALSE_PASS_BAR)}"
        )
    if look:
        lines.append(
            f"look-alike false pass (bar <= {FALSE_PASS_BAR:.0%}): {rate(found(look), len(look))} -> "
            f"{bar(found(look) / len(look) <= FALSE_PASS_BAR)}"
        )
    if toxic:
        warned = sum(r["hazard"] for _, r in toxic)
        lines.append(
            f"toxic passes (bar 0): {rate(found(toxic), len(toxic))} -> {bar(found(toxic) == 0)}; "
            f"hazard card shown on {warned} of {len(toxic)}"
        )
    c2f = [r["capture_to_found_ms"] for r in results.values() if r["capture_to_found_ms"] is not None]
    lines.append(
        f"capture to Found ms: median {statistics.median(c2f):.0f}, max {max(c2f):.0f} ({len(c2f)} Found)"
        if c2f
        else "capture to Found: no Found"
    )
    screens = [r["screen_s"] for _, r in close if r["screen_s"] is not None]
    if screens:
        lines.append(
            f"screen time to Found, close (bar < {SCREEN_BAR_S:.0f} s, stretch < {SCREEN_STRETCH_S:.0f} s): "
            f"median {statistics.median(screens):.1f} s, max {max(screens):.1f} s; "
            f"under {SCREEN_BAR_S:.0f} s {sum(s < SCREEN_BAR_S for s in screens)} of {len(screens)} Found"
        )
    caps = [m for r in results.values() for m in r["capture_ms"]]
    if caps:
        late = sum(m >= CAPTURE_BUDGET_MS for m in caps)
        lines.append(
            f"capture tap to last verdict ms (bar < {CAPTURE_BUDGET_MS:.0f}): p50 {pct(caps, 50):.0f}, "
            f"p95 {pct(caps, 95):.0f}; at or over: {late} of {len(caps)} -> {bar(late == 0)}"
        )
    frames = [m for r in results.values() for m in r["frame_ms"]]
    if frames:
        lines.append(
            f"verify ms per frame: p50 {pct(frames, 50):.0f}, p95 {pct(frames, 95):.0f}; "
            f"at or over {FRAME_BUDGET_MS:.0f}: {sum(m >= FRAME_BUDGET_MS for m in frames)} of {len(frames)}"
        )
    not_plant = sum(r["verdicts"]["not_plant"] for r in results.values())
    lines.append(f"not_plant frames (monitor read as no plant): {not_plant} of {len(frames)}")
    flips = [p["order"] for p, r in close + wide + look + toxic if r["found"] != (p["laptop_pass"] == "True")]
    lines.append(f"phone disagrees with the laptop verdict on photos: {', '.join(flips) or 'none'}")
    return lines


def main(argv: list[str]) -> int:
    """Print the S52 summary; exit 1 on a run that doesn't match its photo."""
    runs_dir = Path(argv[0]) if argv else DEFAULT_RUNS_DIR
    photos = rows(PHOTOS)
    if not slideshow_matches(photos):
        print("slideshow.html's photo list no longer matches photos.csv", file=sys.stderr)
        return 1
    by_order = {p["order"]: p for p in photos}
    runs = {r["order"]: r["run"] for r in rows(RUNS)} if RUNS.exists() else {}
    results, bad = {}, 0
    for order, run in runs.items():
        try:
            r = photo_result(runs_dir / run)
        except (OSError, ValueError) as e:
            print(f"photo {order}: run {run} unusable: {e}", file=sys.stderr)
            bad += 1
            continue
        if r["target"] != by_order[order]["target_to_set"]:
            print(
                f"photo {order}: run {run} targets {r['target']}, not {by_order[order]['target_to_set']}",
                file=sys.stderr,
            )
            bad += 1
            continue
        results[order] = r
    print("\n".join(report(photos, results)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
