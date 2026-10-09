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
- Grades are Claude's, not the owner's; no hint ships on them alone
