# Day 5 · Target pass rule study (Oct 10, 2026)

Question: can a verify row-4 rule pass more real target photos than today's ~50% while keeping toxic passes at exactly 0? This was measured on the laptop only. No app code changed.

## Setup

- Run 2026-10-10 08:34 to 08:38 EDT on an Apple M4 Max (arm64), macOS 26.5.2. Python 3.13.14, onnxruntime 1.30.0, numpy 2.5.3, Pillow 12.3.0
- Models: BioCLIP 2.5 Mobile fp32 (`flora_student_fp32.onnx` sha256 `8624d44a…`, the pinned build) and the TinyCLIP plant gate. Asset SHA-256s are in `target_pass/assets.sha256`, snapshotted once into `.models/target-pass-assets`
- Hunt: the West Georgia October hunt from `day-3/calibration.py`, with 23 eligible targets and 282 local toxic or hazard blockers
- Row 1 is rule B (shipped Oct 10): floor hazards plus local hazards, top 5 of the whole table
- Files live in `target_pass/`:
  - `fetch.py` builds the new photo set; `photos.csv` and `fetch.log` record it
  - `embed.py` embeds 11 crops per photo
  - `rules.py` runs the rules (`reproduce.log`, `tune.log`, `test.log`, and per-photo `*.csv`)
  - `cv.py` runs the cross-validation (`cv.log`)

## Splits

| Split | Photos | Targets | Toxic | Other |
| --- | --- | --- | --- | --- |
| TUNE | S50 calibration + toxic-block set | 99 (30 + 69) | 194 (189 toxic-block + 5 flagged S50 plants) | 15 safe non-target plants |
| TEST | S51 holdout + NEW | 99 (30 + 69) | 73 (15 + 58) | 27 non-plants |

- NEW is 69 target photos (3 for each of the 23 targets) and 58 toxic photos. The toxic species are poison ivy ×8, Atlantic poison oak ×2, Virginia creeper ×4, white, post, and blackjack oak ×3 each, pokeweed ×4, horsenettle ×4, and 10 blockers that beat real targets in the Day-3 logs
- All NEW photos are iNat research-grade CC0, taken newest first, and checked by SHA-256. None of them appears in any other `docs/results` file (checked again just before the TEST run)
- A toxic pass counts when row 4 alone passes any target, with the plant gate and row 1 ignored. That is the strictest count
- Headroom is tau minus the best toxic lead. A positive value means the rule held with that much to spare
- Every rule's tau was set on TUNE. TEST ran once, for the baseline and 3 finalists only

## Reproduction

`reproduce.log` matches the Oct 8 numbers exactly. Every photo also matches `calibration.csv`, `holdout.csv`, and `toxic_block.csv` (0 mismatches).

| Check | Oct 8 | Harness |
| --- | --- | --- |
| S50 target pass | 17/30 | 17/30 |
| S51 target pass | 16/30 | 16/30 |
| Toxic-block targets, row 4 only | 33/69 | 33/69 |
| Toxic-block toxic pass | 0/189 (0/180 Day-2 subset) | 0/189 (0/180) |
| S51 toxic pass / false pass | 0/15, 0/57 | 0/15, 0/57 |

- Under the shipped row 1 (rule B), S51 drops to 15/30: one contact-hazard warning lands on a target photo. That 15/30 is the baseline the rest of this page uses

## TUNE (all rules)

Phone cost counts extra BioCLIP passes per frame over today's 2 (reticle, plus the full frame whenever the gate calls it a plant).

| Rule | Tau | TUNE pass | Toxic | Headroom | Wrong genus | Extra passes |
| --- | --- | --- | --- | --- | --- | --- |
| R0 baseline (reticle, 0.048) | 0.048 | 50/99 (51%) | 0/194 | +0.0003 | 0 | 0 |
| R1 mean of reticle + full scores | 0.024 | 62/99 (63%) | 0/194 | +0.0005 | 0 | 0 |
| R1h R1 at boundary + 0.01 | 0.034 | 60/99 (61%) | 0/194 | +0.0105 | 0 | 0 |
| R2 reticle or full passes | 0.048 | 61/99 (62%) | 0/194 | +0.0003 | 2 | 0 |
| R3 reticle and full pass | 0.001 | 54/99 (55%) | 0/194 | +0.0415 | 0 | 0 |
| R4 reticle + flip | 0.036 | 53/99 | 0/194 | +0.0005 | 0 | +1 |
| R5 reticle + full + both flips | 0.035 | 58/99 | 0/194 | +0.0001 | 0 | +2 |
| R6 4 reticle scales (50–80%) | 0.028 | 56/99 | 0/194 | +0.0004 | 2 | +3 |
| R7 4 scales + full | 0.023 | 60/99 | 0/194 | +0.0007 | 2 | +3 |
| R8 today's 3-in-a-row on shaken crops | 0.048 | 49/99 | 0/194 | +0.0345 | 0 | 0 |
| R9 mean of 3 shaken crops | 0.040 | 52/99 | 0/194 | +0.0002 | 0 | 0 |
| R11 3 shaken crops + full | 0.029 | 56/99 | 0/194 | +0.0005 | 1 | 0 |
| R13 / R14 relative gap (gap / pool std) | 0.82 / 0.49 | 50 / 62 | 0/194 | n/a | 0 | 0 |
| R15 / R16 hunt's 3 targets as competitors | 0.048 / 0.024 | 50 / 62 | 0/194 | same as R0 / R1 | 0 / 0.2 | 0 |
| R17 drop audited wrong-sense flags | 0.048 | 50/99 | 0/194 | +0.0003 | 0 | 0 |
| R18 weak-flag blockers discounted (best) | 0.051 | 55/99 | 0/194 | +0.0006 | 0 | 0 |
| R20 full frame only | 0.005 | 60/99 | 0/194 | +0.0002 | 5 (5.1%) | 0 |
| R21 reticle:full 1:3 | 0.001 | 67/99 (68%) | 0/194 | +0.0010 | 3 | 0 |
| R21h R21 at boundary + 0.01 | 0.011 | 66/99 (67%) | 0/194 | +0.0110 | 2 | 0 |

- Adding the full frame is the only lever that matters. Every rule that adds it gains 8 to 17 finds, and it costs nothing, because the phone already embeds the full frame for row 1
- Flips, crop scales, shaken-frame means, relative margins, hunt-only competitors, and flag changes each move 0 to 5 finds
- The full table, including every weak-flag variant, is in `tune.log`
- In cross-validation grouped by species (`cv.log`), every rule leaks about 1.2 toxic photos per pass, today's included. Each rule's boundary is set by one photo, so a fixed headroom is the only real defense. That is why R1h and R21h exist

## TEST (finalists, run once)

| Rule | Tau | TEST pass | S51 / NEW | Toxic | Headroom | Wrong genus | Non-plant | Extra passes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R0 baseline | 0.048 | 47/99 (47%) | 15/30, 32/69 | 0/73 | +0.0157 | 2/99 (2.0%) | 0/27 | 0 |
| R1 reticle + full mean | 0.024 | 60/99 (61%) | 18/30, 42/69 | **1/73** | −0.0072 | 2/99 | 0/27 | 0 |
| R1h reticle + full mean | 0.034 | 56/99 (57%) | 17/30, 39/69 | 0/73 | +0.0028 | 1/99 (1.0%) | 0/27 | 0 |
| R21h reticle:full 1:3 | 0.011 | 66/99 (67%) | 20/30, 46/69 | **4/73** | −0.0197 | 1/99 | 0/27 | 0 |

- R1 and R21h fail: toxic photos pass
- The toxic photo closest to passing under every rule is the same honey locust (*Gleditsia triacanthos*) read as mimosa (*Albizia*). Its lead is 0.031 to 0.032. The audit put honey locust's flag in the irrelevant class (the sentence is about a different plant), but it stays flagged and counts here
- Next closest is red maple (animals-only flag) at 0.014 under R1h
- R1h gains 11 TEST finds and loses 2. Platanus gains 3 and magnolia 2. The 2 lost are a fern and a fireweed photo that pass on the reticle and fail when the full frame is averaged in
- Four targets pass 0 of 4 or 5 TEST photos under both rules: sweetgum, tulip tree, persimmon, and winged sumac. No rule here fixes them

## Verdict

- Ship R1h: score each species as the mean of its reticle and full-frame cosines, with the margin at 0.034
  - TUNE 50 → 60 of 99, TEST 47 → 56 of 99. Across both splits, 97 → 116 of 198 (49% → 59%)
  - 0 of 267 toxic photos pass
  - Worst headroom is +0.0028 (TEST, honey locust). Today's worst is +0.0003 (TUNE, poison ivy), so R1h's worst case is about 10x safer than what ships
  - Wrong genus 1 of 99 and non-plant 0 of 27 on TEST
  - Zero extra model passes
- Not shippable: R1 at its TUNE boundary (1 toxic pass on TEST) and R21h (4 toxic passes). Any weighting that favors the full frame needs a margin near zero, and fresh toxic photos clear it
- The headroom that remains is thin and set by one flagged legume. Leaving honey locust aside, R1h's TEST headroom is +0.020 (red maple)
- Picking among 3 finalists on TEST is a mild selection effect. The other 2 failed outright, so the pick is conservative. A fresh look-alike set before release would confirm the 0
- When the gate rejects the full frame, there is no full-frame embedding, so the reticle is scored alone. That happened on 3 plant photos (all toxic, led by blockers by 0.06 to 0.09) and on no target photo. Port it with today's 0.048 so the case stays exactly as strict as now; no measured number changes

## S05 gate-harness runs pulled Oct 10

Two harness runs from the S24 Ultra (SM-S928U, Android 16, app 0.1.0, target water oak), pulled with `make gate-pull` into `docs/results/gate/`:

| Run | Length | Power | Frames verified | Peak memory | Thermal status | Clean stop |
| --- | --- | --- | --- | --- | --- | --- |
| 20261008-151148 | 2.4 min | charging the whole time | 0 | 591 MB | none | no |
| 20261010-093416 | 2.2 min | charging the whole time | 0 | 386 MB | none | no |

Neither run captured a frame, ran unplugged, or reached 20 minutes, so S05's heat and per-frame timing bar is still open. The longest heat data so far is still Day 2: severe thermal status at 36 minutes of continuous verify on the charger, and 5.5 minutes of capture-only verify on battery with no heat.

## Hazard rule C: the fixed floor only inside North America (Oct 10)

Question: if the always-on hazard floor (every *Toxicodendron*, pokeweed, and Carolina horsenettle) warns unseen only inside North America, and like any other hazard elsewhere (only when the region's pull names it), do the gate numbers move?

- Run 2026-10-10 10:28 EDT on an Apple M4 Max (arm64), macOS 26.5.2. Python 3.13.14, onnxruntime 1.30.0, numpy 2.5.3, Pillow 12.3.0
- Models and table: BioCLIP 2.5 Mobile fp32 (`flora_student_fp32.onnx` sha256 `8624d44a…`) and `species_labels.json` sha256 `6cb9e032…`, the current `make assets` build. Every hash is in the `gate.log` header
- Script: `day-4/hazard_gate/gate.py`, with rule C added beside rule B in `day1_rerun.py`. Output is `day-4/hazard_gate/gate.log` plus `day1.csv`, `s50_s51.csv`, and `new_set.csv`
- Rule C is rule B with the fixed 7 kept warning unseen only when the photo's hunt-region center sits in core `NorthAmerica`'s whole-degree box: latitude 5 to 72, longitude −170 to −50
- Day 1, S50, and S51 score as West Georgia hunts, inside the box. Each new-set photo uses its own region key; 64 of its 82 photos are outside the box

At k = 5:

| Set | Rule B | Rule C |
| --- | --- | --- |
| Day-1 hazards caught | 48/52 | 48/52 |
| Day-1 safe warned | 3/253 | 3/253 |
| S50 safe warned | 1/50 | 1/50 |
| S51 safe warned | 3/57 | 3/57 |
| New-set hazards caught | 41/52 | 41/52 |
| New-set safe warned | 3/30 | 3/30 |

- No photo's warning changes at any k from 1 to 5. Day 1 and S50/S51 ranks are identical row for row, since every one of those hunts is inside the box
- Three new-set photos outside the box change rank, and all three stay far past the top 5:
  - A hogweed photo near 54°N, 3°W goes from 32, a western poison oak floor row, to 67, its own species now the best warning row
  - A hemp-nettle photo near 48°N, 7°E goes from 109 to 221
  - A caraway photo near 60°N, 60°E goes from 158 to 899
- Every new-set pull names at least one listed hazard, so every photo still had a row that could warn. The case where nothing can warn (outside the box, and no listed hazard in the pull) is covered by core's unit tests, not by this set
- Gate of record is now rule C, k = 5: Day-1 hazards 48/52 (bar ≥ 48) PASS, Day-1 safe 3/253 (bar ≤ 3) PASS

Two more S24 runs pulled later on Oct 10:

| Run | Sampled | Power | Captures | Camera paused at | Thermal status | Battery temp |
| --- | --- | --- | --- | --- | --- | --- |
| 20261010-095652 | 13.1 min | charging the whole time | 0 | 3.1 min | severe at 12.7 min | 28.3 to 38.3 °C |
| 20261010-103343 | 8.1 min | charging 217 of 239 samples | 0 | 7.3 min | critical from the first sample | 49.8 to 50.7 °C |

Neither run logged a Capture tap, and in both the camera paused partway through (the app left the foreground or the screen locked), so they record heat with the camera off rather than verify cost. The 10:33 run started with the battery already near 50 °C. S05 stays open.

## Self-review fix: a toxic plant filling only the circle (Oct 10)

The PR self-review found that R1h averages every row, blockers included, so a toxic plant that fills only the reticle is diluted by a full frame of the target's genus, and the margin dropped from 0.048 to 0.034 on top of that. The fix keeps the target on the mean but scores each blocker on the larger of its mean and its reticle cosine (`target_pass/blocker_guard.py`, `blocker_guard.log`, same embeddings and splits as above).

| Rule | Margin | TUNE targets | TUNE toxic (headroom) | TEST targets | TEST toxic (headroom) | Wrong genus (TEST) |
| --- | --- | --- | --- | --- | --- | --- |
| R1h | 0.034 | 60/99 | 0/194 (+0.0105) | 56/99 | 0/73 (+0.0028) | 1/99 |
| Guard, margin retuned | 0.011 | 60/99 | 0/194 (+0.0149) | 59/99 | **2/73 (−0.0029)** | 2/99 |
| **Guard at 0.034 (shipped)** | 0.034 | 55/99 | 0/194 (+0.0379) | 53/99 | 0/73 (+0.0201) | 1/99 |

Retuning lets the guard's own boundary pull the margin down to 0.011, and 2 fresh toxic photos pass, so the margin stays at R1h's 0.034. The guard there costs 8 finds across both splits (116 to 108 of 198, still up from 97) and widens the closest toxic call from 0.0028 to 0.0201 under the margin. TEST was scored a second time here, so it carries the same small selection effect as the first study.

## Adversarial review fixes · Oct 10

- Worktree `fix/adversarial-review`, based on `feat/contact-hazard-list` at `69415ca`; separate camera, accessibility, and persistence agents, followed by independent reviews.
- Host: Apple M4 Max, macOS 26.5.2; SDKMAN JDK 25.0.1-tem, Kotlin 2.4.20, AGP 9.4.1, Compose BOM 2026.09.00, CameraX 1.6.2, ONNX Runtime 1.30.0; Python 3.13.14 and pytest 9.1.1.
- Phone: Samsung Galaxy S24 Ultra (SM-S928U), Android 16; debug app 0.1.0; models and tables rebuilt with `make assets` in the worktree.
- Regression inputs: verification-only hunt rows; failed/partial cache writes and older disk entries; every hazard verdict with clean or truncated model replies; real animator-setting toggles; actual Briar pixels; 320 dp hints at 200% font; stale camera generations and adjustment futures; stalled capture deadlines and stale outcomes; hazard preservation; real camera hint/Skip journeys.
- Restore and durable-cache failures reproduced before their fixes; truncated negative hazard replies now require review.
- Final `make ai-checks` passed: 309 core tests, 258 pipeline tests, formatting, lint, APK/test-APK compilation, coverage gate, and secret scan ([log](day-5/review-fixes/ai-checks.log)).
- Core line coverage: 99.73% (1,095 covered, 3 missed); ActiveHunt, AreaCacher, and LocalListSource have complete line and branch coverage. Hazard classifier: 45 tests, 99% statement coverage; only the CLI entrypoint line is uncovered.
- Sonar analyses of nine changed production files: no unresolved issues ([results](day-5/review-fixes/sonar.json)); the camera binding function keeps explicit platform inputs with a function-scoped S107 exception rather than a parameter-count wrapper.
- Semgrep: 1,277 rules over 103 production files, no scan errors; eight unchanged URL/loopback audit alerts triaged as fixed endpoints and pinned downloads, no actionable changed-source finding; final camera/game incremental scan also clean.
- First E2E run: 52/53 passed; one hunt-loading assertion timed out at 90 seconds ([log](day-5/review-fixes/e2e-first.log)); the unchanged test passed alone in 9.14 seconds ([retry](day-5/review-fixes/e2e-isolated-retry.log)). Its initial timeout cause is unconfirmed.
- Final `make device-test`: 55/55 passed, including eight analyzer/watchdog cases, two future-order cases, six accessibility cases, and actual disk failure/restore tests ([log](day-5/review-fixes/device-tests.log)); installs preserved app data.
- Full E2E repeat: 53/53 passed in 407.379 seconds, using the same installed isolated E2E app and unchanged tests ([log](day-5/review-fixes/e2e-final.log)); all camera, hints, settings, hunt-start, area-selection, persistence, safety, and win-page journeys passed.

## Rebased adversarial re-review · Oct 10

- Rebased `fix/adversarial-review` onto the latest local parent `feat/contact-hazard-list` at `129417e`; the fetched remote parent is an ancestor. The final parent addition contains only documentation and gate-harness records.
- Five fresh reviewers covered accessibility, security, clean code, design, and camera behavior; uncommitted and new-file fixes were included.
- Updated two restored test fixtures for the parent's `UseVerifier(ActiveHunt?)` and regional `HazardCheck` contracts.
- Post-rebase `make ai-checks` passed: 312 core tests, 258 pipeline tests, lint, test APK compilation, coverage gate and secret scan ([log](day-5/review-fixes/rereview-ai-checks.log)). Refreshed core line coverage remains 99.73% (1,095 covered, 3 missed).
- Post-rebase `make device-test`: 55/55 passed in 20.464 seconds on the same S24 Ultra and toolchain recorded above; installations retained app data ([log](day-5/review-fixes/rereview-device.log)).
- Separate temporary camera-panel probe: one test passed in 63.85 seconds; no probe remains in app source. At 411 dp width and font scale 2, Capture-to-Checking changes the panel from 814 to 883 pixels, causing a viewport rebind. At 320/360 dp with scale 2 and 320/411 dp with scale 1, the panel height stays unchanged ([measurements](day-5/review-fixes/rereview-camera-geometry.log), [probe](day-5/review-fixes/CameraPanelGeometryProbeTest.txt)).
- Additional findings: concurrent same-key cache writers share a temporary path outside the serialized dispatcher; a real-filesystem interleaving produced invalid JSON after the first writer's successful rename. A hazard response missing `kind` raises `KeyError` instead of reaching the pipeline review path.
- Fresh Semgrep: 119 files, 312 executed rules, zero scan errors; nine unchanged audit alerts triaged as non-actionable. Focused Sonar analysis of GameViewModel, GameRules and HazardCheck returned zero issues.
- No additional accessibility or design finding; the camera layout finding affects large-text accessibility. The three findings above remain open after this review.

## Main-based fixes and verification · Oct 10

- Fetched `origin/main` at `055cf2a` and rebased only `fix/adversarial-review` onto it; squash-merged feature commits were not replayed. All relevant earlier fixes were preserved; main already supplies the held-back-row restore correction and its unit regression.
- Rebuilt generated assets from main's current data after the parser change: 4,272 species, 85 hazards, 82 hazard card lines, 2,161 toxic rows, and four never-target rows. Host, phone, model pins and toolchain are the same as recorded above.
- Fixed the three remaining findings: one capture button reserves all action labels' wrapped geometry; store reads, writes and deletion share an instance monitor; malformed model replies validate required fields before indexing and persist for review.
- Before-fix device regressions reproduced both faults: concurrent cache refresh lost a saved entry; at 411 dp and 200% text the camera panel changed from 1,049 to 949 pixels on readiness. These failures are intentional proof, using freshly built pre-fix APKs ([log](day-5/review-fixes/main-before-regressions.log)).
- Parser regressions reproduced three pre-fix failures, then all 77 targeted tests passed; malformed verdict/kind types and missing fields for every verdict are covered. Branch-enabled classifier coverage is 99%; only the CLI entrypoint statement is missed.
- Final `make ai-checks` passed: 314 core tests and 350 pipeline tests, formatting, lint, coverage gate, APK/test-APK compilation and secret scan ([log](day-5/review-fixes/main-ai-checks.log)). Refreshed core line coverage is 99.73% (1,098 covered, three missed).
- Final `make device-test`: 58/58 passed in 25.881 seconds on the S24 Ultra; includes actual concurrent cache IO, all camera action states with/without hints at large text, selected-label semantics, and stale-frame refusal followed by fresh-frame recovery ([log](day-5/review-fixes/main-device.log)). App data was retained.
- Final Semgrep: 119 files, 312 executed rules, zero errors, nine unchanged non-actionable audit alerts, no new actionable finding ([report](day-5/review-fixes/main-semgrep.json)). Sonar's tool integration was unavailable for this run; earlier snippet results are not claimed as a main-based scan.
- Independent accessibility, clean-code/design, and error/coverage reviews found no remaining production defect; the uncovered stale-frame edge now has a passing regression.
- Full isolated E2E: 53/53 passed in 375.247 seconds on the same phone, covering camera focus/zoom/hints/Skip, settings and twelve-month caching, hint sheets, hunt start, area selection, persistence, safety copy, screen rules and win pages ([log](day-5/review-fixes/main-e2e.log)). No physical field check is claimed.

## Claude follow-up review and fixes · Oct 10

- Imported Claude's local `review16` patch into the isolated `fix/adversarial-review` worktree; his checkout was left intact. Fetched main remains `055cf2a`, already an ancestor. Separate camera, accessibility/design, and security/clean-code reviewers inspected the combined patch.
- Host, Samsung S24 Ultra Android 16, model pins and toolchain match the main-based run above. Inputs include capture-callback interleavings, closed harness logs, stale adjustment futures, restored open hints, live motion toggles, and a 320 × 600 dp hazard screen at 200% text.
- Fixed independent focus/zoom errors, unnecessary viewport rebinds, capture-cancellation callback races, refused/error/logging harness recovery, restored-sheet viewport reservation, clipped hazard actions, and motion reset blanking. Final lint cleanup inlines the single-use failure handler without changing its monitor.
- Before-fix regressions reproduced cancellation returning during its callback and a refused capture disabling the harness ([log](day-5/review-fixes/claude-camera-before.log)); closed-log IO escaped the adapter ([log](day-5/review-fixes/claude-closed-log-before.log)); short-screen hazard detail was clipped ([log](day-5/review-fixes/claude-hazard-before.log)). These failures are intentional proof.
- Final `make ai-checks` passed: 314 core and 350 pipeline tests, formatting, Detekt, Android lint, coverage gate, APK/test-APK compilation and secret scan ([log](day-5/review-fixes/claude-ai-checks.log)). Core line coverage: 99.73% (1,098 covered, three missed). Checks also passed before each code commit.
- Final `make device-test`: 67/67 passed in 29.651 seconds, including 13 capture/harness, five adjustment, seven accessibility and two geometry tests ([log](day-5/review-fixes/claude-device.log)). App data was preserved.
- Final independent review found no additional actionable defect. Semgrep 1.168.0: 125 files, 1,074 loaded/311 executed rules, zero errors, 10 unchanged non-actionable audit alerts; production and debug sources were included ([report](day-5/review-fixes/claude-semgrep.json)). Physical field checks remain open.
- First full E2E: 52/53 passed in 398.106 seconds; the physical Back-key camera journey did not show the hunt list within 15 seconds ([log](day-5/review-fixes/claude-e2e-first.log)). The unchanged isolated test passed in 12.349 seconds ([retry](day-5/review-fixes/claude-back-retry.log)); the initial failure's cause is unconfirmed.
- Full E2E repeat on the unchanged APK passed all 10 camera tests, then the twelve-month cache test timed out at 240 seconds and subsequent hunt setup reported `iNat didn't answer and nothing is cached: the phone needs signal for this test`. The run was stopped after consecutive failures; no further E2E retry or production change was made ([incomplete log](day-5/review-fixes/claude-e2e-repeat-stopped.log)). The runner’s `Process crashed` tail comes from deliberately force-stopping the isolated app. This is not a full-suite pass.
