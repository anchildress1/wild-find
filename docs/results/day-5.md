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
