# 🌿 Day-2 results (October 7, 2026)

Day 2 took the verify path outside for the first time and tested the redesign's assumptions. Every number here comes from the raw files linked below; nothing was estimated.

## Field runs on real plants

Both runs used the S24 Ultra (SM-S928U, Android 16) in Ashley's yard with the gate harness, target oak. The first was continuous analysis on a charger; the second was capture-only, unplugged, after the harness changed.

Run 2's `summary.txt` says `charging yes` because the summarizer read only the last sample, and the phone was plugged in for the last 9. Its `system.csv` shows 153 of 162 samples unplugged. The summarizer now counts every sample.

Three 1-minute morning runs (`084701`, `095515`, `101831`) bracket the resize change in [day-2/resize-benchmark.log](day-2/resize-benchmark.log): resize p50 108.8 ms in the first, 17.0 and 21.4 ms after. TinyCLIP called 774 of 776 frames not a plant, so they measure speed only, never verify.

| Run | Mode | Raw data |
| --- | --- | --- |
| Run 1, 17:10 | Continuous verify (target 5 frames a second), hint ladder with a hardcoded stand-in line on levels 1 and 3, phone charging | [gate/20261007-171013-oak/](gate/20261007-171013-oak/), with Ashley's spot list in `notes.txt` |
| Run 2, 18:06 | Verify only on a Capture tap (3 frames back to back), Gemma on every Hint tap with a 10 s rest, top species logged per capture | [gate/20261007-180644-oak/](gate/20261007-180644-oak/) |

### Problems found

**1. "Walk closer" blocks most real captures.**
- Run 1: 804 of 1,567 analyzed frames (51%).
- Run 2: 11 of 19 captures.
- The camera focused fine. What blocks them is the close-range rule (diopters × zoom ≥ 2.0, about half a metre), and nobody hunting a tree stands that close.

**2. Hazard warnings fire on look-alikes at the edge of the cutoff.**
- Run 2's one warning was a capture BioCLIP read as northern dewberry (*Rubus flagellaris*), with eastern poison ivy ranked 5th. A rank of 5 or better warns.
- Run 1 had 22 warning frames. Their best hazard ranked anywhere from 1st to 5th, and in 5 of those frames a hazard species ranked 1st. That run predates species logging, so which plants set them off is unknown; Ashley's yard is full of blackberries.
- The per-capture "closest hazard" column names a hazard on every row, pokeweed or horsenettle included. That's only the nearest one, often ranked in the hundreds; it isn't a warning.

**3. BioCLIP ranks against plants from every continent.**
- Plausible top picks: Bermuda grass, St. Augustine grass, sugarberry, crossvine, green ash, Japanese stiltgrass, eastern annual saltmarsh aster.
- Implausible top picks: New Zealand lancewood, a New Zealand passionflower, kapok, black cottonwood.
- No capture ranked an oak first.

**4. Verify is too slow to run continuously.**
- Run 1 took 366 ms per frame at p50 (924 ms max) once BioCLIP ran on both regions, which it did on 82% of frames outdoors.
- That's 2.5 analyzed frames a second, and thermal status reached severe at 36 minutes on the charger.
- Run 2 ran only on capture and unplugged: 189 ms per capture frame at p50, and thermal status stayed at none over 5.5 minutes. That's too short to show heat on battery; S05's 20-minute run covers it.

**5. Gemma's hints are generic and repetitive.**
- Run 1 had 2 Gemma hints; run 2 had 3. Every one said some version of "look for a big tree near the woods edge / sun / lawn."
- Each echoes the harness's hardcoded oak line ("Oaks grow in yards, parks, and along the woods edge"). Scene tags varied (lawn; none; tree, sun, woods edge, lawn), but the hints barely did.
- Scene tags did come back outdoors on 4 of 5 calls; the empty tags in earlier runs were the camera facing a table.

**6. The harness never showed what to look for.**
- Run 1 never put the target on screen, so the walk had no goal. Run 2 added "Find: oak."

### Hazard cutoff, from Day 1's photos

The hazard rule warns when a hazard species ranks within the top k of the 4,272-species table, on either the full frame or the reticle crop ([day-2/hazard_topk.log](day-2/hazard_topk.log)).

| Cutoff | Hazard photos caught (of 52) | False alarms (of 255) |
| --- | --- | --- |
| Top 1 | 43 | 1 |
| Top 3 | 45 | 1 |
| Top 5 | 48 | 1 |

On Day 1's photos, tightening the cutoff loses real catches and removes no false alarms. Those photos had no blackberries, which is the look-alike the field found. Ranking only local species (problem 3) changes every rank, so the cutoff has to be measured again after that fix.

## Laptop probes behind the Oct 7 redesign

| Question | Answer | Raw data |
| --- | --- | --- |
| Can build-time Gemma write a target list? | Greedy decoding gives 10 words; 10 seeds give 29, about half generic or garden crops | [day-2/candidates.log](day-2/candidates.log) |
| Is there an open toxicity flag? | Wikidata has none. USDA PLANTS rates Nandina, ivy, holly, and pawpaw "none". The FDA database was decommissioned | [day-2/menu_sources.log](day-2/menu_sources.log), [day-2/usda_toxicity.log](day-2/usda_toxicity.log) |
| Can CLIP models judge toxicity? | No: AUC 0.42 to 0.65 for TinyCLIP 8M, 39M, 40M, and BioCLIP 2.5, by photo or by name | [day-2/tinyclip_poison.log](day-2/tinyclip_poison.log), [day-2/clip_poison_names.log](day-2/clip_poison_names.log) |
| Can BioCLIP name a plant's type? | No: 71.0% right, below the 71.5% from always guessing herb | [day-2/bioclip_habit.log](day-2/bioclip_habit.log) |
| Does a text flag work? | It flagged 30 of 117 common West Georgia species and wrongly dropped about 6. The first full build flagged 2,170 of 4,272, mostly stubs; following Wikipedia continuations changed nothing; dropping "non-toxic" and then "not toxic" unflagged 4 and 5 more, leaving 2,161 | [day-2/toxicity_flag.log](day-2/toxicity_flag.log), [day-2/toxicity-build.log](day-2/toxicity-build.log), [day-2/toxicity-build-2.log](day-2/toxicity-build-2.log), [day-2/toxicity-build-3.log](day-2/toxicity-build-3.log), [day-2/toxicity-build-4.log](day-2/toxicity-build-4.log) |
| Fixed 25 sightings, or a share? | A share, decided Oct 7. Counting only species in the table and not toxic, a flat 25+ leaves 0 to 5 playable species across Georgia (the country) and 65 to 77 in West Georgia and Atlanta; 0.5% with at least 3 sightings leaves 9 to 24 everywhere | [day-2/threshold_regions.log](day-2/threshold_regions.log), [day-2/playable_species.log](day-2/playable_species.log) |
| Does the species table cover other places? | West Georgia: 104 of 117 common species. Tbilisi: 4 of 6. Genus coverage is 89% to 97% | [day-2/table_coverage.log](day-2/table_coverage.log) |
| Can Gemma E2B describe a plant from its scientific name? | No: on the 20 most-seen West Georgia targets, with Gemma 4's recommended sampler, a system instruction, and two examples, 4 descriptions were right, 7 partly right, 2 generic, and 7 wrong enough to mislead (Claude's grading). Google's model card calls Gemma "not knowledge bases"; E2B scores 60.0% on MMLU Pro to E4B's 69.4% | [day-2/gemma_describe.log](day-2/gemma_describe.log), [day-2/gemma_describe_grades.csv](day-2/gemma_describe_grades.csv) |
| Could a "What's this?" capture name any plant? | Not reliably: BioCLIP's top pick over the whole table was the right genus on 60% of Day 1's labeled crops; naming only when the top 3 agree is right 85% but stays quiet on 59% of captures. Sweetgum was right 2 of 10 | [day-2/whats_this.log](day-2/whats_this.log) |
| Does "is this the target?" work? | Yes, against a few labels: the photo's own group was top-1 among 6 on 102 of 114 Day-1 crops (89%), oak 11 of 12. That's the pass the hunt uses; ranking over the whole table drops it to 60% | [day-2/target_pass.log](day-2/target_pass.log) |
| Can a toxic plant pass as a target? | Yes, unless toxic species block. On 249 CC0 photos (3 per eligible West Georgia species, 3 per 60 local toxic species): with no blockers, every toxic photo passes as some target; blocking toxic species that clear the sighting floor, 87 of 180; blocking every local toxic species, 4 of 180 at 64% real finds. Only a margin of 0.048 over the best blocker reaches 0 of 180, keeping 34 of 69 real finds; top-k vetoes reach 0 by passing nothing | [day-2/toxic_block.log](day-2/toxic_block.log), [day-2/toxic_block.csv](day-2/toxic_block.csv), [day-2/toxic_block_photos.csv](day-2/toxic_block_photos.csv) |
| Do iNat's local names match the species table? | Only about 60% do: 641 of 1,029 in West Georgia, 171 of 300 in Tbilisi, 355 of 665 across Georgia (the country); the rest need synonym matching (PRD hole 22) | [day-2/playable_species.log](day-2/playable_species.log) |
