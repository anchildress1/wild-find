# Day 4 · Gemma 4 hints probe (Oct 9, 2026)

Question: can a local Gemma 4 write "where to look" hints that stay true to the plant's Wikipedia article? Oct 7 cut Gemma because unguided hints misled (E2B: 4 of 20 right).

## Setup

- Run 2026-10-09 15:02 EDT, Apple M4 Max, macOS 26.5.2, Ollama localhost
- Models: gemma4:12b @ c7597fc90b86, gemma4:26b @ 48eb98ec778c
- Sampling: temperature 1.0, top_p 0.95, top_k 64, seed 1, num_ctx 8192, thinking off
- Inputs: 20 West Georgia targets, each at its pinned Wikipedia revision (listed in the log header)
- Arms: `name_only` (no article) and `grounded` (article excerpt; hint must quote its source sentence or answer null)
- Aspects: place, light, ground, nearby, edges, range; `hint_rank` picks the top three
- Files: `gemma_hints.py` (probe), `gemma_hints.log`, `gemma_hints.csv` (per hint), `gemma_hints_grades.csv` (Claude's grade of each grounded hint against its quoted sentence)

## Void run, kept as evidence

`gemma_hints-void-thinking.log` (14:42 EDT): Gemma 4 thinks by default in Ollama, the thinking ate the whole 700-token reply budget, every reply came back empty and logged as an abstention. Fixed with `think: false`; the run above is the valid one.

## Probe results

| Model | Arm | Hints | From USDA | Clean | Fake evidence | Targets with 3 picks | Median s |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 12b | name_only | 120/120 | 0 | 120 | 0 | 0 | 1.9 |
| 12b | grounded | 97/120 | 2 | 94 | 3 | 19 | 10.7 |
| 26b | name_only | 120/120 | 0 | 120 | 0 | 0 | 1.2 |
| 26b | grounded | 90/120 | 4 | 90 | 0 | 19 | 4.2 |

- `name_only` always answers and cites nothing; its hints are unverifiable (e.g. "dry, sandy soil" for blue mistflower, whose article says moist soils)
- 12b made up 3 quotes; 26b made up none and is 2.5x faster

## Re-rank, Oct 9 (no model calls)

`gemma_hints_rerank.py` re-ranked the saved hints with the current `hint_rank`: every season hint now comes back beyond the three picks, and season candidates count toward rarity. Model text and grades are untouched; only the `pick` lines, `pick_count`, and `top_picks` changed. Targets with three picks stay 19 of 20 on both models.

## Grades (grounded hints, Claude against quoted sentence)

| Model | Graded | Supported | Overreach | Unsafe | Unsupported |
| --- | --- | --- | --- | --- | --- |
| 12b | 95 | 43 (45%) | 32 | 14 | 6 |
| 26b | 86 | 43 (50%) | 28 | 12 | 3 |

| Aspect | Supported | Overreach | Unsafe | Unsupported |
| --- | --- | --- | --- | --- |
| light | 21 | 5 | 0 | 1 |
| ground | 19 | 10 | 4 | 1 |
| nearby | 16 | 1 | 6 | 0 |
| place | 16 | 7 | 11 | 1 |
| edges | 14 | 2 | 5 | 1 |
| range | 0 | 35 | 0 | 5 |

- `range` never passes (0 of 40): native-range lists give a kid nothing to search for. Drop it
- 26 unsafe hints, 23 of them "water edge": hints sending a kid to a stream, pond, or swamp bank. Needs a banned-place rule
- Without `range`, supported is 86 of 141 (61%)
- Overreach pattern: "moist" sharpened to "wet", restoration advice read as habitat, Florida-only evidence generalized

## Verdict

- Grounding works: the quote requirement removed fabrication on 26b and the ungrounded arm has no way to be checked
- Model pick: 26b (no fake quotes, faster, slightly higher supported rate); 12b is not needed
- Not shippable as-is: ship only supported hints after a banned-place filter and dropping `range`
- Still owed per PRD Decisions: Hints: owner spot-check of a random 50 and the same probe at scale
- After dropping `range` and water-edge hints, 26b keeps 43 of 55 graded hints supported (78%); 12b keeps 43 of 63 (68%)
- 19 of 20 plants keep at least one supported hint on 26b, 8 keep three or more, 1 keeps none (American sycamore)
- Viable only behind the Claude grading pass: about one hint in five that survives the filters still misleads
- Grades are Claude's. The owner spot-checked but could not establish what is true from reading a hint alone, so the grades have no human ground truth yet; no hint ships on them alone

## Probe 2: up to two hints per aspect (Oct 9, 2026)

- Script `gemma_hints_2.py`, output `gemma_hints_2.csv` (one row per hint) and `gemma_hints_2.log`; grades in `gemma_hints_2_grades.csv`
- Same 20 plants, excerpts, sampler, and Apple M4 Max as above; gemma4:26b only, grounded only, `range` removed, water-edge check added, `think: false`
- Grading method changed: each hint judged only on whether its quoted sentence says it, not on whether it is true of the plant (the owner could not verify truth, see PRD Open Questions: Hints spot-check). Grades are still Claude's

| Step | Hints |
| --- | --- |
| Written by the model | 77 |
| Dropped by automatic checks | 7 (4 water, 4 repeated quote, one hint hit both) |
| Clean, graded | 70 |
| Supported by their quote | 60 (86% of clean, 78% of written) |
| Overreach | 5 |
| Unsafe | 3 (roadsides, interstate highways, swamps) |
| Range hidden in a place hint | 2 |

- Probe 1 on the same model: 50% of graded hints supported (78% after dropping `range` and water edges by hand); probe 2 gets 78% with the filters built in
- Coverage: all 20 plants have at least one supported hint, 12 have three or more (probe 1: 19 and 8); median 2.6 s per plant, against 4.2 s
- Not caught by the automatic checks: `roadsides` and `interstate highways` send a kid toward traffic, and `swamp`/`swampy` slipped past the water pattern; each needs a check before the full run
- The model smuggles range into `place` ("the eastern United States"); a place hint naming only a country or region needs a check
- Overreach repeats the earlier pattern: Florida-only evidence generalized, propagation advice read as habitat, "tolerates shade" read as "likes shade", "fixes nitrogen in poor soil" read as "grows well in poor soil"
- Plants with a second hint often got a near-duplicate of the first ("Look for it in woods", "It likes shade"); the repeated-quote check caught 4 of those
- Caveat: 20 well-documented species, one run at temperature 1.0, graded by the model family that is being judged for the pipeline's grading pass; the full run over the table is still the go/no-go

## Probe 3: probe 2 plus road, swamp, and region checks and a worked example (Oct 9, 2026)

- Script `gemma_hints_3.py`, output `gemma_hints_3.csv` and `gemma_hints_3.log`; quote-only grades in `gemma_hints_3_grades.csv`
- Same 20 plants, excerpts, model (gemma4:26b), sampler, and machine as probe 2; thinking off
- Prompt grounded in Google's Gemma 4 prompt-formatting guide: one consolidated system turn, one worked example (a plant outside the test set), Google's recommended sampler kept so runs compare. Added a rule that a place is a habitat, never a country or region
- New automatic checks: road (roadside, highway, interstate), swamp and wetland words in the water pattern, and a place hint naming a country or region

| Step | Probe 2 | Probe 3 |
| --- | --- | --- |
| Hints written by the model | 77 | 79 |
| Dropped by automatic checks | 7 | 13 (water 7, repeated quote 4, road 3) |
| Clean, graded | 70 | 66 |
| Supported by their quote | 60 | 60 |
| Overreach | 5 | 6 |
| Unsafe that got through | 3 | 0 |
| Range hidden in place that got through | 2 | 0 |
| Supported share of clean | 86% | 91% |
| Supported share of written | 78% | 76% |
| Plants with 3 or more supported | 12 | 11 |
| Plants with at least one | 20 | 20 |

- The new checks work: 0 unsafe or range hints reached grading, and the interstate-highway hint for shining sumac was caught
- The prompt changes did not move overreach (6 against 5, within the noise of one run at temperature 1.0): Florida-only evidence generalized, "tolerates shade" read as "likes shade", "fixes nitrogen in poor soil" read as "grows well in poor soil", restoration advice read as habitat. These need a grading pass, not a prompt
- The water check also drops legitimately sourced hints (a fern's "woodlands, stream banks" place hint); that is the intended trade
- Two supported hints carry no search value ("The tree grows in the wild", "It lives in many kinds of habitats")
- Grades are Claude's, quote-only. The sample is the same 20 well-documented species

## Full run: every playable row (Oct 9, 2026)

- `make hints` (`pipeline/src/wild_find_pipeline/hints.py`), gemma4:26b (digest 48eb98ec778c) through Ollama on the Apple M4 Max, probe-3 prompt and checks, up to two hints per aspect, `think: false`, Google's sampler (1.0 / 0.95 / 64, seed 1), num_ctx 8192; about 100 minutes wall clock, median under 3 s per row
- Inputs: the 2,111 species-table rows that are neither toxic-flagged nor hazards, each with its current English Wikipedia article (revision recorded per row in `pipeline/data/hints.json`); every row has an article
- Output: `pipeline/data/hints.json` (every candidate with its evidence, source, checks failed, and score) and `hints_full.log`
- One model reply (giant sequoia, *Sequoiadendron giganteum*) hit the 700-token cap and ships with no model hints. The first attempt stopped there, so `hints.py` now records a failed reply on its row and continues

| Count | Value |
| --- | --- |
| Rows | 2,111 |
| Model hints written | 6,827 |
| Clean (passed every automatic check) | 5,087 after the height recheck below |
| Dropped: water | 791 |
| Dropped: repeated quote | 708 |
| Dropped: road | 296 |
| Dropped: height (cliff, bluff, ledge, roof) | 61 |
| Dropped: evidence not in article | 71 |
| Dropped: region as place | 10 |
| Rows with three non-season picks | 1,148 (54%) |
| Rows with one or two | 787 |
| Rows with none | 172 (8%), which ship with description only |
| Rows with a season hint | 414 |
| Picked hints | 5,208: 4,300 from the model, 637 from USDA traits, 271 from USDA ratings |

### Quote-only sample

- 300 hints drawn at random (seed 1) from the picked model hints, which are the hints a kid would see; `hints_sample.csv`, graded in `hints_sample_grades.csv` on whether the quoted sentence says the hint, by Claude, not on whether it is true of the plant
- Result: 274 supported (91%), 13 overreach, 3 unsupported, 10 unsafe
- Unsafe hints all slipped past the water and road checks: cliffs, bluffs, canyon walls, shorelines, "freshwater", flood-prone lands, and "on top of houses"
- Fix: the water pattern gained shorelines, freshwaters, and flood words; a new height pattern covers cliffs, bluffs, ledges, canyon walls, and roofs. `make hints` has a `--recheck` mode that re-applies the patterns to the stored hints without the model: 108 hints were newly flagged, which removed all 10 unsafe hints from the sample and 2 supported ones
- After the recheck the sample's surviving hints are 272 supported of 288 (94%), 16 overreach or unsupported (5.6%), 0 unsafe. The PRD's stop line is 20% overreach
- Overreach repeats the probe findings: cultivation advice read as habitat ("requires full sun" in a growing guide), "tolerates" read as "likes", use read as habitat (erosion control, hedging), and sentences about other things (a virus, a plant name, C3 plants in general)
- Not caught: the sample is 300 of 4,300, so rarer unsafe wordings may remain; cultivation-advice sentences are the likeliest source of further overreach
- Grades are Claude's. The owner's 50-hint read beside the quotes is still owed

## S55 ship gate: the contact-hazard list (Oct 9, 2026)

Question: does the hazard warning still meet the Day-1 bars (at least 48 of 52 hazards caught, at most 1 in 250 safe photos warned) once the 7 fixed hazards grow into a contact-hazard list? Two lists were measured.

**Gate of record: run 2, rule B, top 5, as-shipped list.**

| Set | Result | Bar | Verdict |
| --- | --- | --- | --- |
| Day 1, hazards caught | 48/52 | ≥ 48/52 | Pass |
| Day 1, safe photos warned | 3/253 | ≤ 3/253 | Pass on the relaxed bar |
| S50, safe photos warned | 1/50 | recorded | — |
| S51, safe photos warned | 3/57 | recorded | — |
| New set, hazards caught | 39/52 | recorded | — |
| New set, safe photos warned | 3/30 | recorded | — |

The original bar of at most 1 in 250 failed under every rule and cutoff measured here. On Oct 10 the owner chose rule B at top 5 and relaxed the Day-1 safe bar to at most 3 of 253.

- **Run 1 (loose list):** 23:15 to 23:29 EDT, `species_labels.json` sha256 8b2f3125…, 147 hazard rows
- **Run 2 (tightened list, gate of record):** 23:56 EDT Oct 9 on `species_labels.json` sha256 6adca7ed…, 92 hazard rows (the fixed 7 plus 85 contact hazards). The final pass ran Oct 10 00:06 on a rebuild (sha256 047b86d6…) with the same 92 hazard rows and every count identical; `gate.log` records that pass and ends with the verdict
- Apple M4 Max, macOS 26.5.2, laptop CPU; onnxruntime 1.30.0, numpy 2.5.3, Pillow 12.3.0, Python 3.13.14. Every model pin, asset, and input SHA-256 is in each log's header
- Crops, plant gate, and BioCLIP as the phone runs them. A hazard ranks against all 4,272 rows, and ties warn
- "Fixed 7" means the same photos in the same run scored with the old list. It reproduces Day 1 exactly: 48/52 and 1/253, with all 610 photo-region ranks equal to `day-1/species_scores.csv`. S50 and S51 match the Day-3 CSVs photo for photo

Two warning rules, neither in code when measured:

- **Rule A** (ships today): warn when any hazard row is in the top k
- **Rule B** (local-gated): same, but a hazard row counts only if it is one of the fixed 7 or iNat lists it near the player. For Day 1, S50, and S51, "near" means the cached West Georgia October pull (647 table rows, 14 of them contact hazards). For the new set it means the app's own query at each photo's region and month: 75 km, widened to 150 km when fewer than 3 genera are playable, locale en-US. That came to 79 pulls, cached under `.models/hazard-gate-inat`, with every URL in `gate.log`

### Run 2 results, both rules

| Set | Rule | k=1 | k=2 | k=3 | k=4 | k=5 |
| --- | --- | --- | --- | --- | --- | --- |
| Day 1, hazards caught (bar ≥ 48) | Fixed 7 | 43 | 44 | 45 | 46 | 48 |
| | A | 43 | 44 | 45 | 46 | 48 |
| | B | 43 | 44 | 45 | 46 | 48 |
| Day 1, safe warned of 253 (bar ≤ 1) | Fixed 7 | 1 | 1 | 1 | 1 | 1 |
| | A | 6 | 9 | 12 | 15 | 18 |
| | B | 2 | 2 | 3 | 3 | 3 |
| S50, safe warned of 50 | Fixed 7 | 0 | 0 | 0 | 0 | 0 |
| | A | 1 | 1 | 2 | 3 | 4 |
| | B | 0 | 0 | 0 | 0 | 1 |
| S51, safe warned of 57 | Fixed 7 | 1 | 1 | 1 | 1 | 1 |
| | A | 1 | 3 | 4 | 4 | 6 |
| | B | 1 | 3 | 3 | 3 | 3 |
| New set, hazards caught of 52 | A | 32 | 41 | 42 | 44 | 45 |
| | B | 27 | 32 | 34 | 37 | 39 |
| New set, safe warned of 30 | A | 1 | 3 | 6 | 7 | 9 |
| | B | 0 | 1 | 2 | 2 | 3 |

- **The original bar fails under both rules at every cutoff.** Hazard catch holds at 48/52 everywhere. Rule A warns 18 of 253 at top 5 and still 6 at top 1. Rule B warns 3 at top 3 to 5 and 2 at top 1 or 2, which is why the owner relaxed the bar to 3 and kept top 5 for its catch
- Rule B's 3 Day-1 warnings: a grass photo that ranks poison ivy first (the old list's one false alarm), a grass photo that ranks spotted spurge (*Euphorbia hypericifolia*) third, and a sweetgum photo that ranks castor bean (*Ricinus communis*) first. Spurge and castor bean are both in the West Georgia pull
- Rule A's 18 Day-1 warnings come from 16 hazard species, led by alsike clover (*Trifolium hybridum*, 3 clover photos), frangipani, and woolly nightshade (2 each). Most aren't seen near West Georgia, so rule B drops them
- The 4 Day-1 hazard misses are the same under every rule: one poison ivy photo that ranks it 100th, two poison sumac photos that rank it 11th to 17th, and one poison ivy photo whose best rank under any rule is 8 (rule A)
- S51 under rule B: castor bean warns on a sweetgum target, honeyvine milkweed (*Cynanchum laeve*) on a redbud, and the old list's poison sumac on winged sumac. S50 under rule B: small-flower buttercup (*Ranunculus abortivus*) on a yellow wood sorrel
- New set, rule A misses 7 photos: one giant hogweed, one wild parsnip, one fig, one leafy spurge, and 3 of 4 greater celandine. Rule B misses 13: hogweed photos lose the help of look-alike *Heracleum* rows that aren't seen locally, and leafy spurge drops from 3 of 4 to 1 of 4
- Three species in the new set are off the run-2 list: greater celandine (in review), leafy spurge (rejected), and hogweed (*Heracleum sphondylium*, dropped since run 1). On listed species alone, rule A catches 37 of 40 and rule B 36 of 40
- Ox-eye daisy is no longer flagged in run 2, so the safe count needs no exception. It still warns on both photos under rule A, through stinking chamomile (*Anthemis cotula*). Rule B warns on neither
- The West Georgia hunt keeps all 23 targets, and its 282 blockers are unchanged from the fixed list
- Two new-set hunts (39_49 in January, 37_34 in November) don't have enough plants even at 150 km, so the app wouldn't play there. Their photos are still scored against the 150 km pull. One observation has obscured coordinates, so its region is approximate

### Run 2 with the review triage applied (for the owner's decision)

Same photos, same run, with `contact_hazards_review.json` (sha256 8d0221fb…) applied: rows marked `approve` are listed and rows marked `reject` are not. That removes 9 auto rows proposed for veto (stinking chamomile, burdock, asparagus fern, gotu kola, buffalo gourd, Paterson's curse, mango, star-of-Bethlehem, alsike clover) and adds hogweed (*Heracleum sphondylium*) and *Marah fabacea*. That leaves 85 hazard rows, and the fixed 7 stay. The as-shipped list above remains the gate of record.

| Set | Rule | k=1 | k=2 | k=3 | k=4 | k=5 |
| --- | --- | --- | --- | --- | --- | --- |
| Day 1, hazards caught of 52 (bar ≥ 48) | A triage | 43 | 44 | 45 | 46 | 48 |
| | B triage | 43 | 44 | 45 | 46 | 48 |
| Day 1, safe warned of 253 (bar ≤ 1) | A triage | 6 | 8 | 9 | 11 | 14 |
| | B triage | 2 | 2 | 3 | 3 | 3 |
| S50, safe warned of 50 | A triage | 0 | 0 | 1 | 2 | 4 |
| | B triage | 0 | 0 | 0 | 0 | 1 |
| S51, safe warned of 57 | A triage | 1 | 3 | 4 | 4 | 6 |
| | B triage | 1 | 3 | 3 | 3 | 3 |
| New set, hazards caught of 52 | A triage | 34 | 42 | 43 | 44 | 45 |
| | B triage | 29 | 34 | 36 | 38 | 41 |
| New set, safe warned of 30 | A triage | 1 | 3 | 4 | 5 | 8 |
| | B triage | 0 | 1 | 2 | 2 | 3 |

- **Under rule B at top 5, triage changes nothing on the gate:** 48/52 caught and 3/253 warned, so it also passes the relaxed bar. None of the vetoed rows caused a rule-B warning. Rule A drops from 18 to 14 Day-1 warnings, mostly by losing alsike clover, but still fails
- Rule A's 14 remaining Day-1 warnings come from 14 species, led by woolly nightshade (3), frangipani, stinging nettle, and hispid buttercup (2 each)
- Adding hogweed back lifts new-set catch under rule B from 39 to 41 of 52. Under rule A it also warns on one more *Angelica atropurpurea* photo
- On the new set's species that the triaged list includes (44 photos), rule A catches 41 and rule B 39 at top 5

### Run 1 (loose list, 147 rows)

| Set | Fixed 7 | Rule A, k=5 | Rule B, k=5 | Rule A, k=1 | Rule B, k=1 |
| --- | --- | --- | --- | --- | --- |
| Day 1, hazards caught of 52 | 48 | 49 | 48 | 43 | 43 |
| Day 1, safe warned of 253 | 1 | 27 | 9 | 6 | 2 |
| S50, safe warned of 50 | 0 | 6 | 3 | 1 | 0 |
| S51, safe warned of 57 | 1 | 10 | 6 | 2 | 2 |
| New set, hazards caught of 52 | 0 | 45 | 41 | 34 | 29 |
| New set, safe warned of 30 | 0 | 10 | 5 | 3 | 2 |

- Rule A's 27 Day-1 warnings came from 25 hazard species, including scarlet oak on white oak photos and alsike clover on clover. Tightening the list removed scarlet oak and cut the warnings to 18
- Run 1 also flagged ox-eye daisy, which the new set's photos treat as safe. Without its 2 photos, rule A warned on 8 of 28
- Run 1 made 5 local West Georgia rows into blockers (jewelweed, ox-eye daisy, red mulberry, scarlet oak, feverfew). Run 2 makes none

### Files

- `hazard_gate/day1_rerun.py` (Day-1 set) and `hazard_gate/gate.py` (every set, both rules, as shipped and triaged); `gate.log` is run 2
- Per-photo ranks for every rule and region: `day1.csv`, `s50_s51.csv`, `new_set.csv` (with each photo's hunt region, month, radius, and whether its own species is local)
- S50 and S51 reruns as Day 3 wrote them: `calibration.csv`, `calibration.log`, `holdout.csv`, `holdout.log`
- `hazard_gate/run1/`: run 1's log and CSVs (rule A only), plus `gate_rules.log`, the same assets rerun with both rules
- The contact-hazard review file changed during run 2, but `species_labels.json` did not, so the as-shipped numbers hold. The triage variant reads review sha256 8d0221fb… Rebuild the assets and rerun `gate.py` after any list change
