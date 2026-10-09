import csv
import json

import pytest

from wild_find_pipeline.gate_summary import found_latencies, main, pct, spread, summarize

FRAME_COLUMNS = [
    "t_ns",
    "sensor_ns",
    "gap_ms",
    "frame_w",
    "frame_h",
    "rotation",
    "verdict",
    "streak",
    "reticle_share",
    "full_share",
    "reticle_hazard_rank",
    "full_hazard_rank",
    "reticle_top",
    "reticle_hazard",
    "full_top",
    "full_hazard",
    "goal_score",
    "goal_rank",
    "af_state",
    "diopters",
    "zoom",
    "focus_matched",
    "crop_ms",
    "resize_ms",
    "gate_ms",
    "bioclip_ms",
    "hazard_ms",
    "goal_ms",
    "verify_ms",
    "frame_ms",
]
MS = 1_000_000


def frame(t_ms, verdict, streak=0, frame_ms=150.0, both=True):
    row = dict.fromkeys(FRAME_COLUMNS, "1")
    row.update(
        t_ns=t_ms * MS,
        sensor_ns=t_ms * MS - 40 * MS,
        frame_w=432,
        frame_h=960,
        verdict=verdict,
        streak=streak,
        frame_ms=frame_ms,
        focus_matched="true",
        reticle_hazard_rank=9 if both else "",
        full_hazard_rank=9 if both else "",
        reticle_top="Quercus alba" if both else "",
        reticle_hazard="Toxicodendron radicans" if both else "",
        full_top="Quercus alba" if both else "",
        full_hazard="Toxicodendron radicans" if both else "",
    )
    return row


def write(path, rows, columns=None):
    with path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=columns or list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


@pytest.fixture
def run(tmp_path):
    d = tmp_path / "20261007-090000-oak-1280x960"
    d.mkdir()
    (d / "run.json").write_text(
        json.dumps(
            {
                "device": "samsung SM-S928U",
                "soc": "SM8650",
                "android": "16",
                "app_version": "0.1.0",
                "goal": "target",
                "target": "Quercus nigra",
                "requested_analysis": "1280x960",
            }
        )
    )
    frames = [
        frame(0, "guide"),
        frame(200, "matching", 1),
        frame(400, "matching", 2),
        frame(600, "found", 3, frame_ms=180.0),
        frame(800, "not_plant", both=False, frame_ms=250.0),
    ]
    write(d / "frames.csv", frames)
    system = [
        {
            "t_ns": 0,
            "pss_kb": 3_000_000,
            "avail_mem_kb": 1,
            "low_memory": "false",
            "thermal_status": 0,
            "thermal_headroom": 0.4,
            "battery_temp_c": 30.0,
            "battery_pct": 90,
            "charging": "false",
        },
        {
            "t_ns": 1200 * 10**9,
            "pss_kb": 3_100_000,
            "avail_mem_kb": 1,
            "low_memory": "false",
            "thermal_status": 2,
            "thermal_headroom": "NaN",
            "battery_temp_c": 38.5,
            "battery_pct": 80,
            "charging": "false",
        },
    ]
    write(d / "system.csv", system)
    write(
        d / "events.csv",
        [
            {"t_ns": 0, "event": "camera", "detail": "viewport 1080x2340, timestamp_source 1"},
            {"t_ns": 1, "event": "stop", "detail": "oak"},
        ],
    )
    return d


def test_percentile_interpolates_and_handles_empty():
    assert pct([1.0, 2.0, 3.0, 4.0], 50) == 2.5
    assert pct([5.0], 95) == 5.0
    assert pct([], 50) != pct([], 50)  # NaN


def test_found_latency_runs_from_the_first_eligible_frame_to_the_found_verdict():
    frames = [frame(0, "matching", 1), frame(200, "matching", 2), frame(400, "found", 3, frame_ms=100.0)]

    assert found_latencies(frames) == [500.0]


def test_a_broken_streak_does_not_count():
    frames = [frame(0, "matching", 1), frame(200, "walk_closer"), frame(400, "found", 3)]

    assert found_latencies(frames) == []


def test_summary_reports_every_gate_number(run):
    text = summarize(run)

    assert "frames: 5 over 1 s" in text
    assert "at or over 333 ms: 0 of 5" in text
    assert "(4 frames)" in text
    assert "capture to analyzer ms: p50 40" in text
    assert "first eligible frame to Found ms: p50 580" in text
    assert "thermal status max moderate; first moderate at 20.0 min" in text
    assert "battery %: 90 to 80 (30 %/h)" in text
    assert "PSS MB: start 2930, max 3027" in text
    assert "reticle top-1 species: Quercus alba 4" in text
    assert "hazard warnings by species (region-frames): none" in text
    assert "tap_to_focus with no focus reading for the frame: 0 of 0" in text
    assert "1 of 2 samples NaN" in text
    assert "WARNING" not in text


def test_a_run_without_a_stop_event_is_flagged(run):
    events = (run / "events.csv").read_text().splitlines()
    (run / "events.csv").write_text("\n".join(e for e in events if ",stop," not in e) + "\n")

    assert "WARNING: no stop event" in summarize(run)


def test_a_run_that_died_before_any_row_summarizes_as_empty(run):
    for name in ("frames.csv", "system.csv"):
        header = (run / name).read_text().splitlines()[0]
        (run / name).write_text(header + "\n")

    text = summarize(run)

    assert "frames: none" in text
    assert "system: none" in text


def test_a_frame_exactly_at_the_budget_counts_against_it(run):
    frames = (run / "frames.csv").read_text().replace(",150.0\n", ",333.0\n", 1)
    (run / "frames.csv").write_text(frames)

    assert "at or over 333 ms: 1 of 5" in summarize(run)


def test_a_missing_charging_state_reads_unavailable(run):
    (run / "system.csv").write_text((run / "system.csv").read_text().replace(",false\n", ",\n"))

    assert "charging unavailable" in summarize(run)


def test_charging_counts_every_sample_not_the_last(run):
    system = (run / "system.csv").read_text()
    head, last = system.rstrip("\n").rsplit("\n", 1)
    (run / "system.csv").write_text(f"{head}\n{last.rsplit(',', 1)[0]},true\n")

    assert "charging 1 of 2 samples" in summarize(run)


def test_a_run_with_a_truncated_row_fails_alone(run, capsys):
    with (run / "events.csv").open("a") as f:
        f.write("5\n")

    assert main([str(run.parent)]) == 1
    assert not (run / "summary.txt").exists()
    assert "SUMMARY FAILED" in capsys.readouterr().err


def test_a_paused_run_is_flagged(run):
    with (run / "events.csv").open("a") as f:
        f.write("3,paused,\n4,resumed,\n")

    assert "WARNING: paused 1 time(s)" in summarize(run)


def test_the_pause_before_a_clean_stop_is_not_a_gap(run):
    with (run / "events.csv").open("a") as f:
        f.write("3,paused,\n")

    assert "WARNING" not in summarize(run)


def test_a_stat_with_no_values_reads_none():
    assert spread([]) == "none"


def test_capture_lag_needs_a_realtime_sensor_clock(run):
    events = (run / "events.csv").read_text().replace("timestamp_source 1", "timestamp_source 0")
    (run / "events.csv").write_text(events)

    assert "capture to analyzer" not in summarize(run)


def test_errors_and_missing_battery_extras_are_reported_not_invented(run):
    with (run / "events.csv").open("a") as f:
        f.write('2,sample_error,"IllegalStateException: boom"\n')
    system = (run / "system.csv").read_text().replace(",30.0,90,", ",,,")
    (run / "system.csv").write_text(system)

    text = summarize(run)

    assert "errors: 1, first sample_error: IllegalStateException: boom" in text
    assert "battery temp C: start 38.5" in text
    assert "battery %: 80 to 80" in text


def test_main_writes_summary_into_each_run(run, capsys):
    assert main([str(run.parent)]) == 0
    assert (run / "summary.txt").read_text().startswith("run 20261007-090000-oak-1280x960")


def test_main_without_new_runs_is_a_no_op(tmp_path):
    assert main([str(tmp_path)]) == 0


def test_main_never_rewrites_an_existing_summary(run):
    (run / "summary.txt").write_text("as logged\n")

    assert main([str(run.parent)]) == 0
    assert (run / "summary.txt").read_text() == "as logged\n"


def test_one_broken_run_still_lets_the_others_summarize(run, capsys):
    broken = run.parent / "20261007-080000-oak-640x480"
    broken.mkdir()
    (broken / "run.json").write_text("{}")

    assert main([str(run.parent)]) == 1
    assert not (broken / "summary.txt").exists()
    assert (run / "summary.txt").read_text().startswith("run 20261007-090000-oak-1280x960")


def test_hazard_warnings_name_the_species_that_set_them_off(run):
    frames = (run / "frames.csv").read_text().replace(",9,9,Quercus alba,", ",5,9,Quercus alba,", 1)
    (run / "frames.csv").write_text(frames)

    assert "hazard warnings by species (region-frames): Toxicodendron radicans 1" in summarize(run)
