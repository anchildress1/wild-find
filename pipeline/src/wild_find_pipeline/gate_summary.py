"""S05 gate-harness summary: verify time per frame, what BioCLIP saw, time to Found, memory, and heat."""

import csv
import json
import math
import sys
from collections import Counter
from pathlib import Path

import numpy as np

NS_PER_MS = 1_000_000
NS_PER_S = 1_000_000_000
# The harness analyzes a frame every 500 ms; a slower frame makes it skip the next one.
FRAME_BUDGET_MS = 500.0
FOUND_BUDGET_MS = 1500.0
# The PRD's targets are strict "under" limits, so a sample exactly at one counts against it.
# PowerManager thermal statuses.
THERMAL_NAMES = {0: "none", 1: "light", 2: "moderate", 3: "severe", 4: "critical", 5: "emergency", 6: "shutdown"}
# CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME: sensor time shares elapsedRealtimeNanos' clock.
REALTIME = "timestamp_source 1"
# HazardCheck.TOP_K: a hazard species ranked this high or better warns.
HAZARD_TOP_K = 5
SPECIES_SHOWN = 10


def rows(path: Path) -> list[dict[str, str]]:
    """CSV rows by column name."""
    with path.open(newline="") as f:
        return list(csv.DictReader(f))


def pct(values: list[float], q: float) -> float:
    """The q-th percentile (0-100) by linear interpolation; NaN for no values."""
    return float(np.percentile(values, q)) if values else math.nan


def spread(values: list[float]) -> str:
    """p50 / p95 / max, in one line; "none" for no values."""
    if not values:
        return "none"
    return f"p50 {pct(values, 50):.0f}, p95 {pct(values, 95):.0f}, max {max(values, default=float('nan')):.0f}"


def found_latencies(frames: list[dict[str, str]]) -> list[float]:
    """Milliseconds from each streak's first eligible frame reaching the analyzer to its Found verdict."""
    latencies, start = [], None
    for row in frames:
        if row["verdict"] == "matching" and int(row["streak"]) == 1:
            start = int(row["t_ns"])
        elif row["verdict"] == "found" and start is not None:
            latencies.append((int(row["t_ns"]) - start) / NS_PER_MS + float(row["frame_ms"]))
            start = None
        elif row["verdict"] != "matching":
            start = None
    return latencies


def species_lines(frames: list[dict[str, str]]) -> list[str]:
    """What BioCLIP saw: the reticle's top-1 species, and which hazard species set off each warning."""
    if "reticle_top" not in frames[0]:
        return []
    tops = Counter(r["reticle_top"] for r in frames if r["reticle_top"])
    warned = Counter(
        r[f"{region}_hazard"]
        for r in frames
        for region in ("reticle", "full")
        if r[f"{region}_hazard_rank"] and int(r[f"{region}_hazard_rank"]) <= HAZARD_TOP_K
    )
    return [
        "reticle top-1 species: " + (", ".join(f"{k} {v}" for k, v in tops.most_common(SPECIES_SHOWN)) or "none"),
        "hazard warnings by species (region-frames): "
        + (", ".join(f"{k} {v}" for k, v in warned.most_common()) or "none"),
    ]


def frame_lines(frames: list[dict[str, str]], camera_event: str) -> list[str]:
    """Summary lines for frames.csv."""
    if not frames:
        return ["frames: none"]
    seconds = (int(frames[-1]["t_ns"]) - int(frames[0]["t_ns"])) / NS_PER_S
    totals = [float(r["frame_ms"]) for r in frames]
    both = [float(r["frame_ms"]) for r in frames if r["full_hazard_rank"] and r["reticle_hazard_rank"]]
    verdicts = Counter(r["verdict"] for r in frames)
    # A missing capture result reads as "Tap the plant to focus" too; split those from real unfocused frames.
    unmatched_tap = sum(r["verdict"] == "tap_to_focus" and r["focus_matched"] != "true" for r in frames)
    over = sum(t >= FRAME_BUDGET_MS for t in totals)
    lines = [
        f"frames: {len(frames)} over {seconds:.0f} s ({len(frames) / seconds if seconds else 0:.1f} analyzed/s), "
        f"upright {frames[0]['frame_w']}x{frames[0]['frame_h']}",
        f"frame ms (buffer to verdict): {spread(totals)}; at or over {FRAME_BUDGET_MS:.0f} ms: {over} of {len(totals)}",
        f"frame ms with BioCLIP on both regions: {spread(both)} ({len(both)} frames)",
        "stage p50 ms: "
        + ", ".join(
            f"{stage} {pct([float(r[f'{stage}_ms']) for r in frames], 50):.1f}"
            for stage in ("crop", "resize", "gate", "bioclip", "hazard", "goal")
        ),
        f"focus matched by sensor timestamp: {sum(r['focus_matched'] == 'true' for r in frames)} of {len(frames)}",
        "verdicts: " + ", ".join(f"{k} {v}" for k, v in sorted(verdicts.items())),
        f"tap_to_focus with no focus reading for the frame: {unmatched_tap} of {verdicts['tap_to_focus']}",
        *species_lines(frames),
    ]
    if REALTIME in camera_event:
        lag = [(int(r["t_ns"]) - int(r["sensor_ns"])) / NS_PER_MS for r in frames]
        lines.append(f"capture to analyzer ms: {spread(lag)}")
    found = found_latencies(frames)
    if found:
        late = sum(f >= FOUND_BUDGET_MS for f in found)
        lines.append(
            f"first eligible frame to Found ms: {spread(found)}; "
            f"at or over {FOUND_BUDGET_MS:.0f}: {late} of {len(found)}"
        )
    else:
        lines.append("first eligible frame to Found: no Found this run")
    return lines


def system_lines(system: list[dict[str, str]]) -> list[str]:
    """Summary lines for system.csv."""
    if not system:
        return ["system: none"]
    start = int(system[0]["t_ns"])
    minutes = (int(system[-1]["t_ns"]) - start) / NS_PER_S / 60
    pss = [int(r["pss_kb"]) / 1024 for r in system]
    temps = [float(r["battery_temp_c"]) for r in system if r["battery_temp_c"]]
    statuses = [int(r["thermal_status"]) for r in system]
    firsts = []
    for status in sorted(set(statuses)):
        if status:
            first = next(r for r in system if int(r["thermal_status"]) >= status)
            firsts.append(f"{THERMAL_NAMES[status]} at {(int(first['t_ns']) - start) / NS_PER_S / 60:.1f} min")
    levels = [int(r["battery_pct"]) for r in system if r["battery_pct"]]
    headroom = [
        ((int(r["t_ns"]) - start) / NS_PER_S / 60, float(r["thermal_headroom"]))
        for r in system
        if r["thermal_headroom"] not in ("", "NaN")
    ]
    return [
        f"system: {minutes:.1f} min sampled, charging "
        + {"true": "yes", "false": "no"}.get(system[-1]["charging"], "unavailable"),
        f"PSS MB: start {pss[0]:.0f}, max {max(pss):.0f}, end {pss[-1]:.0f}",
        f"thermal status max {THERMAL_NAMES[max(statuses)]}" + (f"; first {', '.join(firsts)}" if firsts else ""),
        f"thermal headroom: first {headroom[0][1]:.2f} at {headroom[0][0]:.1f} min, "
        f"max {max(h for _, h in headroom):.2f}, end {headroom[-1][1]:.2f}; "
        f"{len(system) - len(headroom)} of {len(system)} samples NaN"
        if headroom
        else "thermal headroom: unavailable",
        f"battery temp C: start {temps[0]:.1f}, max {max(temps):.1f}, end {temps[-1]:.1f}"
        if temps
        else "battery temp: unavailable",
        f"battery %: {levels[0]} to {levels[-1]}"
        + (f" ({(levels[0] - levels[-1]) / minutes * 60:.0f} %/h)" if minutes else "")
        if levels
        else "battery %: unavailable",
    ]


def error_lines(events: list[dict[str, str]]) -> list[str]:
    """How many events were errors, and the first one."""
    errors = [e for e in events if e["event"].endswith("_error")]
    return [f"errors: {len(errors)}, first {errors[0]['event']}: {errors[0]['detail']}"] if errors else []


def summarize(run: Path) -> str:
    """The summary text for one run directory."""
    info = json.loads((run / "run.json").read_text())
    events = rows(run / "events.csv")
    camera = next((e["detail"] for e in events if e["event"] == "camera"), "")
    header = [
        f"run {run.name}: {info['device']} ({info['soc']}), Android {info['android']}, app {info['app_version']}",
        f"goal {info['goal']} {info['word']}, requested analysis {info['requested_analysis']}, {camera}",
    ]
    names = {e["event"] for e in events}
    # A run killed by OOM, heat, or a crash never logs stop; its numbers end early and must say so.
    if "stop" not in names:
        header.append("WARNING: no stop event; the run ended without a clean close (crash, kill, or still running)")
    # Back pauses the activity right before its clean stop; only a pause the run came back from is a gap.
    pauses = sum(e["event"] == "resumed" for e in events)
    if pauses:
        header.append(f"WARNING: paused {pauses} time(s) (screen off or app left); frames and samples have gaps")
    return "\n".join(
        header
        + frame_lines(rows(run / "frames.csv"), camera)
        + system_lines(rows(run / "system.csv"))
        + error_lines(events)
    )


def main(argv: list[str]) -> int:
    """Write summary.txt into every run directory under argv[0] that doesn't have one yet.

    An existing summary describes its run's data as the harness logged it then; a later script never rewrites it.
    """
    root = Path(argv[0])
    runs = sorted(p for p in root.iterdir() if (p / "run.json").is_file() and not (p / "summary.txt").exists())
    if not runs:
        print(f"no unsummarized harness runs under {root}", file=sys.stderr)
        return 0
    failed = 0
    for run in runs:
        # One truncated or partial run must not stop every run after it from getting a summary.
        # A failed run gets no summary.txt, so the next pull retries it instead of keeping the failure as its result.
        try:
            text = summarize(run)
        except (AttributeError, KeyError, OSError, TypeError, ValueError) as e:
            failed += 1
            print(f"run {run.name}: SUMMARY FAILED: {e!r}", file=sys.stderr)
            continue
        (run / "summary.txt").write_text(text + "\n")
        print(text + "\n")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
