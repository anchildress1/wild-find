# S52 monitor proxy 🌿

The field test (S52) moves indoors. The owner holds the Galaxy S24 Ultra and aims it at plant photos shown full screen on the Mac's monitor, while Claude advances the photos and starts each harness run over adb. Camera, autofocus, timing, and the verify path are all real. The photos are not.

## What it measures, and what it can't

| Number | Source | Bar |
| --- | --- | --- |
| Find rate per target | `close` photos that reach Found | 45% or more overall (S51, PRD correct-pass rate) |
| Wide-shot pass rate | `wide` photos that reach Found | 5% or less (PRD false-pass rate) |
| Look-alike false pass | `lookalike` photos that reach Found as the target they score closest to | 5% or less |
| Toxic passes | `toxic` photos that reach Found | 0 |
| Screen time per target | harness `camera` event to the Found frame's verdict, `close` photos | under 60 s, stretch under 30 s |
| Capture to Found | the last `capture` event before Found to that frame's verdict | reported as a median |
| Verify latency | each `capture` event to its last frame's verdict | one capture under 1 s |
| Verify time per frame | `frame_ms` in `frames.csv` | 333 ms or less |

Limits worth knowing before reading the numbers:

- **Focus reads the monitor, not the plant.** The diopter reading is the monitor's distance, so "Get closer or zoom in" and the focus half of the wide-shot question stay unmeasured. The wide set tests only whether a small subject passes.
- **The wide bar may be the wrong bar.** The PRD's Framing row says "a far subject still passes", and the laptop already passes 2 of these 10 wide photos (orders 28 and 36). Expect the 5% bar to miss unless it gets redefined.
- **Screen time here is aim and verify only.** Searching for the plant is the field part, and it stays unmeasured.
- **The photos are small.** The iNaturalist medium size tops out at 500 px, so the monitor shows them upscaled.
- **The build is the working tree.** `make install` compiles whatever is on disk, other agents' work included. Note `git rev-parse HEAD` and `git status --short` in the log.

## Photos

`photos.csv` has 52 photos, all from the Day-3 caches, with nothing new downloaded. `laptop_pass` is that photo's Day-3 laptop verdict for the same target, so a disagreement points at the monitor or the camera rather than the model.

| Set | Count | Picked how |
| --- | --- | --- |
| close | 23 | One per West Georgia target, a close framing, the holdout photo first |
| wide | 10 | Whole trees, fields, banks, and canopies where the target is small, picked by eye |
| lookalike | 9 | A wrong-genus photo set to the target it scored top-1 among on the laptop, plus grape and peppervine swapped |
| toxic | 10 | The four toxic photos that passed on Day 2 with no margin, white oak and American holly against their own genus, and the four hazard photos |

Orders 1 to 3 are the probe: three close photos that pass on the laptop. The rest are shuffled with a fixed seed so the sets interleave.

## Setup, once

```sh
make install                                   # once; never make gate-harness per photo, since it rebuilds and reinstalls
echo order,run > docs/results/day-4/s52/runs.csv
uv run --project pipeline python -I -m http.server 8052 --bind 127.0.0.1 --directory "$PWD"
```

- Open `http://127.0.0.1:8052/docs/results/day-4/s52/slideshow.html#1` in Chrome and press `f` for full screen.
- The page reads the photos in place from the gitignored `.models` caches, so nothing gets copied. Opening the file directly may work too, but only the local server was tested.
- Keys: right or space for next, left for back, `i` to show the target (keep it hidden while the camera is aimed, since CLIP models read text), and `b` to blank the screen. The corner shows only the order number, tiny and dim.
- Set the monitor to full brightness, dim the room lights, and turn off Night Shift and True Tone.

The per-photo step Claude runs: show photo N, end the last run with Back, start a run for N's target, and record the run directory.

```sh
s52() {
  local target pkg=dev.anchildress1.wildfind.debug
  target=$(awk -F, -v o="$1" '$1 == o { print $5 }' docs/results/day-4/s52/photos.csv)
  osascript -e "tell application \"Google Chrome\" to set URL of active tab of front window to \"http://127.0.0.1:8052/docs/results/day-4/s52/slideshow.html#$1\""
  adb shell input keyevent KEYCODE_BACK
  sleep 2
  adb shell am start -W -n $pkg/dev.anchildress1.wildfind.harness.GateHarnessActivity --es target "'$target'"
  sleep 3
  echo "$1,$(adb shell ls -t /sdcard/Android/data/$pkg/files/gate | head -1 | tr -d '\r')" >> docs/results/day-4/s52/runs.csv
}
```

The harness reads its target once at launch, so switching targets means a new run: Back ends the old one cleanly (a `stop` event), and the app keeps the models loaded between runs. A retried photo just gets another `s52 N`. The later `runs.csv` row wins.

## Per photo

1. Claude runs `s52 N` and says when the harness preview is up.
2. The owner holds the phone 30 to 40 cm from the monitor, so the photo fills the preview and no bezel shows, with the circle on the plant. Ignore the harness's "sees" line, since kids never see it.
3. Tap Capture. On "Tap the plant to focus", tap the screen on the plant, then Capture again.
4. Stop at the first Found. Otherwise stop after 5 captures or 60 s, whichever comes first. A wide, look-alike, or toxic photo gets all 5 captures, because any Found there counts against the app.
5. The owner says "done", and Claude moves to N+1.

## Step 1: the 3-photo probe decides go or no-go

The risk is that TinyCLIP's plant gate reads a monitor as "a screen", since screens are one of its not-plant labels. Run `s52 1`, `s52 2`, and `s52 3`, then pull and summarize.

- **Go:** at least 2 of the 3 reach Found, and `not_plant` makes up no more than a third of their frames.
- **No-go:** try one round of fixes: kill glare by tilting the monitor, step back to about 50 cm and use 2x zoom to soften moiré, and dim the room further. Re-probe once. If it still fails, stop, record the not-plant shares from `frames.csv` as the finding, and leave S52 open for the outdoor test.

## Pull and summarize

```sh
make gate-pull                                  # copies the runs to docs/results/gate and writes each summary.txt
uv run --project pipeline python -I docs/results/day-4/s52/summarize.py | tee docs/results/day-4/s52/summary.log
```

`summarize.py` exits non-zero when a run's target doesn't match its photo or a run never started (`load_error`, for example a target the build no longer counts as eligible). It also checks that the slideshow's list still matches `photos.csv`. Commit `runs.csv`, `summary.log`, and the pulled run folders the same day.
