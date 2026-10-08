# 🌿 Day-3 results (October 8, 2026)

Day 3 checked whether the toxic-blocker rule still holds once iNat names match through synonyms, and whether bad toxicity flags explain the finds that rule loses. Every number comes from the raw files linked below; the scripts sit next to their output.

## Questions answered

| Question | Answer | Raw data |
| --- | --- | --- |
| Do GBIF synonyms match more of iNat's local names? | Barely: West Georgia goes from 641 to 647 of 1,029 names, Atlanta 653 to 659, Tbilisi and Borjomi unchanged. Playable targets stay at 23, 24, 12, and 9. Most unmatched names are species the table lacks, not drifted names. Taking every GBIF synonym merged distinct species through homonyms (overcup oak into valley oak, 13 of 25 West Georgia hits wrong), so an alias must keep the row's epithet and resolve back to its taxon | [day-3/name_match.log](day-3/name_match.log), [day-3/name_match.csv](day-3/name_match.csv) |
| Does the zero-toxic rule still hold with synonym matching? | Yes. Every local toxic or hazard species blocks (282, up 3), and the top species must lead the best blocker by 0.048: 0 of 189 toxic photos pass as any target. Real finds drop one, to 33 of 69 (48%), because a newly matched wild ginger takes a crane-fly orchid photo. The smallest zero-false-pass margin is still 0.0477, set by one poison ivy photo read as American beautyberry | [day-3/toxic_block.log](day-3/toxic_block.log), [day-3/toxic_block.csv](day-3/toxic_block.csv), [day-3/toxic_block_photos.csv](day-3/toxic_block_photos.csv) |
| Do other US regions get enough targets? | Yes. With the app's own rules on a live October pull, West Georgia gets 23 eligible targets, Seattle 33, Denver 26, Boston 24, Miami 23, Phoenix 22, and Chicago 10, in 9 to 25 genera each. Miami matches only 329 of its 936 names to the table | [day-3/us_coverage.log](day-3/us_coverage.log), [day-3/us_coverage.csv](day-3/us_coverage.csv) |
| Do bad toxicity flags cause the lost finds? | No. Of 279 West Georgia flags, 129 are stubs, 102 human harm, 22 livestock only, and 18 use the word in another sense. Fixing the wrong-sense flags adds one target (black walnut) and one pass (35 of 72); also unflagging livestock-only flags reaches 37 of 78. Toxic false passes stay at 0 either way. BioCLIP confusing look-alikes costs the finds. The rule stays: it would also unflag water lettuce, whose calcium oxalate its article never mentions | [day-3/toxicity_audit.md](day-3/toxicity_audit.md), [day-3/toxicity_audit.csv](day-3/toxicity_audit.csv), [day-3/toxicity_audit.log](day-3/toxicity_audit.log) |

## Decision

The zero-toxic rule ships, and the correct-pass bar drops from 90% to what it can reach: 45% of single holdout photos (48% measured), with 0 toxic photos passing. A kid can retake a capture, so the field find rate (S52) is the number that matters for play.
