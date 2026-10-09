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
