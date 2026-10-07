# 🌿 Day-2 results (October 7, 2026)

Day 2 took the verify path outside for the first time and tested the redesign's assumptions. Every number here comes from the raw files linked below; nothing was estimated.

## Field runs on real plants

Both runs used the S24 Ultra (SM-S928U, Android 16) in Ashley's yard with the gate harness, target oak. The first was continuous analysis on a charger; the second was capture-only, unplugged, after the harness changed.

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
- Run 1 had 22 warning frames. Their best hazard ranked anywhere from 1st to 5th, and 5 frames ranked one 1st. That run predates species logging, so which plants set them off is unknown; Ashley's yard is full of blackberries.
- The per-capture "closest hazard" column names a hazard on every row, pokeweed or horsenettle included. That's only the nearest one, often ranked in the hundreds; it isn't a warning.

**3. BioCLIP ranks against plants from every continent.**
- Plausible top picks: Bermuda grass, St. Augustine grass, sugarberry, crossvine, green ash, Japanese stiltgrass, eastern annual saltmarsh aster.
- Implausible top picks: New Zealand lancewood, a New Zealand passionflower, kapok, black cottonwood.
- No capture ranked an oak first.

**4. Verify is too slow to run continuously.**
- Run 1 took 366 ms per frame at p50 (924 ms max) once BioCLIP ran on both regions, which it did on 82% of frames outdoors.
- That's 2.5 analyzed frames a second, and thermal status reached severe at 36 minutes on the charger.
- Run 2 ran only on capture: 189 ms per capture frame at p50, and thermal status stayed at none.

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
| Does a text flag work? | It flagged 30 of 117 common West Georgia species and wrongly dropped about 6; the full table build flagged 2,170 of 4,272, mostly stubs | [day-2/toxicity_flag.log](day-2/toxicity_flag.log), [day-2/toxicity-build.log](day-2/toxicity-build.log) |
| Fixed 25 sightings, or a share? | 25+ leaves 0 to 11 species anywhere in the country of Georgia; 0.5% with at least 3 sightings leaves 9 to 24 playable species in every place tested | [day-2/threshold_regions.log](day-2/threshold_regions.log) |
| Does the species table cover other places? | West Georgia: 104 of 117 common species. Tbilisi: 4 of 6. Genus coverage is 89% to 97% | [day-2/table_coverage.log](day-2/table_coverage.log) |
