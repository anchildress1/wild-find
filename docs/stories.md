# 🌱 wild-find v1 stories

The work queue for v1. Every story traces to a PRD requirement (R#), a Day-1 gate item, or a hole (H#). Tick the box when it ships; edit the story when it can't ship as written.

Module key: **core** = pure Kotlin, JVM-tested · **app** = Android · **pipeline** = uv Python.

## Day 1 · Oct 6 · go/no-go gate

Every runtime model (TinyCLIP, BioCLIP, Gemma) runs on the test phone, or nothing else starts.

- [ ] ~~**S01 Sideload models**~~ (Makefile) — `make fetch-models` pulls and verifies the Gemma pin into `.models/`; `make push-models` streams it into the debug app's `no_backup/models` over adb and verifies SHA-256 on the device; hole 12 — **dropped Oct 7 with Gemma** (`docs/results/day-2.md`)
- [x] **S02 BioCLIP reference** (pipeline) — `make reference` embeds a CC0 northern red oak fixture (iNat 363799243, 3 agreeing IDs) and the word, hazard, and scene labels into `app/src/androidTest/assets/reference/`; fails unless the fixture word is top-1
- [x] **S03 BioCLIP on device** (app) — ONNX Runtime loads `flora_student_fp32.onnx` (fp16 returns NaN on ARM); `make device-test` passes: cosine 1.0000 to the S02 reference, oak top-1, load 135 ms, embed 58 ms on the test phone
- [ ] ~~**S04 Gemma on device**~~ — E2B loads on the GPU without out-of-memory (2.1 GB loaded, 2.9 GB peak; 4.0 s warm load) and answers vision prompts in 2.3–2.9 s; too slow for the verify path, so boxing was dropped for deterministic live verify — **dropped Oct 7 with Gemma** (`docs/results/day-2.md`)
- [x] **S09 Close-range threshold** — a debug focus probe (since removed; the gate harness logs the same readings) logged live `LENS_FOCUS_DISTANCE` at far, closer, full-frame, and too-close shots, plus pinch zoom, on the test phone (a can, not a plant); rule: diopters × zoom ≥ 2.0 while autofocus reports focused; PRD hole 19
- [x] **S06 Plant gate** (pipeline + app) — export TinyCLIP ViT-8M's image encoder to fp32 ONNX plus text vectors for the exact Day-1 gate prompts; pin it; `PlantGate` in core; on-device parity passes (cosine 0.99999999, 40 ms per embedding); S34 gates every frame with it; PRD hole 17
- [ ] ~~**S07 Gemma download**~~ (core + app) — R9, Gemma only; starts on its own at launch as a user-initiated transfer job, never behind a wait screen; proven on the test phone (`docs/results/day-1.md`): full pull at about 32 MB/s, kill mid-pull + resume, a 60-second outage with the app closed resumed by the system in a new process, bad hash deleted and pulled again on the next launch; low storage is JVM-tested only; in-app progress lands with the opener (S31) — **dropped Oct 7 with Gemma** (`docs/results/day-2.md`)
- [x] **S08 Debug/release side by side** (app) — debug uses `applicationIdSuffix = ".debug"` so a release install never wipes the debug app's gate-harness runs not yet pulled
- [x] **S08b Bundle the small models** (build) — `make assets` fetches BioCLIP and the taxa table and labels (SHA-checked), builds species_table.npy and species_labels.json (missing hazard species appended, hazard flags set), and exports TinyCLIP into gitignored `app/generated/assets`; the one missing hazard row comes from the committed `pipeline/data/hazard_vectors.json` (`make hazard-vectors`; it and `make reference` are the manual steps that need the 3.9 GB teacher, while `make assets` and CI never do); CI runs `make assets` with a cache; any build without the assets fails; the app loads all of them from the APK

## Build pipeline · Oct 7–8

The Oct 7 redesign (PRD Redesign) dropped the build-time menu: the old S10–S16 (candidates, resolve, gates, fact cards, fact-check, fallback) are gone, and iNat supplies targets live. S10 now names the toxicity flag; S15 keeps its number.

- [x] **S10 Toxicity flag** (pipeline) — `make toxicity` reads every species-table row's English Wikipedia article and USDA PLANTS ratings (GBIF synonyms); flags per the PRD rule; commits `pipeline/data/toxicity.json` with evidence and revision; `make assets` merges `toxic` and `genus` into species_labels.json; Oct 7 build: 2,161 of 4,272 flagged (1,369 stubs; "non-toxic" and "not toxic" never count), 64 of the 104 common West Georgia species pass
- [x] **S15 Tutorial labels** (pipeline) — `make labels` writes only the 11 fixed tutorial labels into `labels.npy` + `labels.json` (schema 2: scientific name and prompt per row); the stand-in menu words are gone; the rebuilt vectors match the old tutorial rows within 1e-7; label text format per hole 3
- [x] **S17 Plant type** (pipeline) — `make plant-types` writes `pipeline/data/plant_types.json`: fern, moss, or conifer from GBIF class or phylum first, else USDA PLANTS growth habit by the row name or a shipped GBIF alias (fixed precedence grass, vine, tree, shrub, herb, subshrub as shrub), else grass for Poaceae, else null; merged into species_labels.json as `type`; 3,544 of 4,272 rows typed, 21 of 23 West Georgia targets (`docs/results/day-3/plant_types.log`)

## Game logic · core · Oct 8

- [x] **S20 Contracts** — core validates what the app parses: `SpeciesRow.checked` lines `species_labels.json` up with `species_table.npy` (unique names, each genus its name's own, every hazard toxic-flagged), `LabelSet` holds `labels.json` to schema 2 and the 11 tutorial labels, `PlantGate` checks `plant_gate.json`; any break throws at load. The app keeps the JSON reading (org.json), since core has no JSON library. The cache entry lands with S23, `type` with S17
- [x] **S21 Region key** — whole-degree rounding; the `34_-85`-only gate is superseded by S28
- [x] **S28 Any region** (core) — redesign: the `34_-85`-only gate in `RegionKey` is gone; any whole-degree key plays, and its whole degrees are the center the query sends; R2
- [x] **S22 Sightings** — aggregate `species_counts` pages; match iNat names to species-table rows through GBIF accepted names and synonyms (PRD hole 22), the build ships the aliases in `species_labels.json` and `NameIndex` resolves them (West Georgia 641 → 647 names; `docs/results/day-3/name_match.log`); eligible at 0.5%+ of the query's plant sightings and 3+ sightings, in the species table, not toxic or hazard, common name of 3 words or fewer in the device locale; widen to 150 km once when < 3 eligible; every local toxic or hazard species with a table row comes back as a blocker for S29; R2
- [x] **S23 Cache rules** — `CacheKey` carries schema, table version (first 12 hex of `species_labels.json`'s SHA-256), region, locale, month, and radius; `CacheEntry` serves only an equal key, so any mismatch discards; the entry keeps the whole pull so the eligible list and blockers rebuild offline; reading and writing the file lands with S33/S37; H3
- [x] **S24 Hunt pick** — sighting-weighted random, 3 targets, never two from one genus, never a hazard or toxic species (eligibility already drops them); fewer than 3 genera leave a short hunt for the coverage message; grass tutorial first-ever only; R3, R4, H1
- [x] **S25 Verify decision** — `VerifyStreak` applies the PRD verify table to each capture frame, first match wins: hazard in a region TinyCLIP calls a plant (reticle crop or full frame), reticle not a plant, no focus reading, target pass for 3 frames in a row (`Matching(1..2)` for the ring and "Hold still", then `Found`), too far (explains a miss, never blocks), else reticle guidance; R5, R12; the grass tutorial goal (R3) skips the hazard row; `FrameVerifier` runs the per-frame model path behind encoder interfaces, so all of it is JVM-tested; the target goal itself is S29
- [ ] ~~**S26 Hint guards**~~ — dropped Oct 7: no hints, no Gemma
- [x] **S27 Hunt state** — `HuntProgress` holds the current hunt (tutorial pending, targets, found rows; targets in any order, one star per find) and `AppFlags` the fixed flags that outlive it (opener seen, tutorial done); saving and restoring across process death is S38; H2
- [x] **S29 Target pass** (core) — redesign: row 4 passes when the top-scoring row among the hunt's eligible local species is the target or shares its genus and leads every local toxic or hazard blocker by `TargetGoal.MARGIN` (0.048), for 3 frames; 0 of 180 local toxic photos pass, 34 of 69 real finds do (`docs/results/day-2/toxic_block.log`); never ranked over the whole table (Day 1: 89% against a few labels vs 60% over the table, `docs/results/day-2/target_pass.log`); replace `TargetGoal`'s floor + margin and menu-word scoring, and the harness and device tests that use them, in the same change as S15; R5

## App · Oct 8–9

Validation: `make device-test` excludes E2E; `make e2e` uses the separate `.e2e` app package and resets only that package's game state.

- [x] **S30 Briar host** — Compose player for the PRD Briar animation contract: each state is an animated WebP packed from its video, picked by screens; on the camera Briar shows only on the hazard card; `opener`, `warning`, `welcome` (the wave; the opener plays the warning clip), and `found` play, rest 1.5 s, and play again, `try_again` plays on the needs-signal and not-enough pages, `complete` loops, and `idle` loops only where no state plays
- [x] **S31 Safety opener** — R1, placeholder art, replayable, banned-copy check in tests (`BannedCopyTest` scans every shipped string and plural on the phone)
- [x] **S32 Location** — "Use my area" (coarse permission only, never re-asked after a denial) or "Pick on a map": the built-in Natural Earth map, crosshairs snapped to whole degrees, "Hunt here" at 12° across or less, 1° arrow pad; the map opens on the rough location when allowed; the area reads "34°N, 85°W · Georgia" from offline Natural Earth names; coverage message when an area comes up short; R2, R7
- [x] **S33 iNat client** — `SpeciesCountsQuery` (core) builds the one query from the region center, month, locale, and radius, caps it at 3 pages of 500, and reads `Retry-After` seconds; `InatClient` (app) fetches only the pages the first page's total needs, with a named User-Agent, and returns the pull, a 429 wait, or a failure; the widen path is one more query at 150 km; INTERNET is the only new permission; R2
- [x] **S34 Camera + verify flow** — a Capture button verifies up to 3 frames back to back (the gate harness pattern); per frame: rotation-normalize once; cut the PRD crops (center square, 60% reticle); TinyCLIP on the reticle crop and full frame; BioCLIP hazard check on each region TinyCLIP calls a plant, ranked against local species plus hazards; reticle plant gate → target pass (S29); "Get closer or zoom in" only on a far miss; S25 → Found and kid message; R5
- [ ] ~~**S35 Hints**~~ — dropped Oct 7: the target's name and type show instead (S17)
- [x] **S36 Hunt complete** — success animation, stars, Hunt Again / Home; R15
- [ ] **S37 Offline** — airplane-mode hunt from cache; a never-pulled region says it needs signal once; R8 (wired: cache fallback, "No signal" banner, needs-signal screen; ticks after the airplane-mode run on the phone)
- [x] **S38 Lifecycle** — hunt state restored after the app goes to the background
- [x] **S39 Privacy proof** — network log on the test phone shows only iNat; R7. Oct 8: the first log caught ONNX Runtime's bundled Microsoft telemetry holding a connection to `mobile.events.data.microsoft.com`; with `ORT_DISABLE_TELEMETRY` set at launch, launch and a full iNat pull show only `api.inaturalist.org` (`docs/results/day-3/privacy/privacy.log`); the capture path is still unlogged, since the camera stays off indoors
- [ ] ~~**S40 Leave-it star**~~ — dropped Oct 8 with R10: no on-device check can tell a rooted plant from a picked one

## Ship · Oct 9–11

- [x] **S50 Calibration** — ~30 free photos (CC0 or public domain, iNaturalist research grade) → genus-pass rate, and whether any floor is needed; the hazard rule needs no calibration; Oct 8 on the laptop (matches the phone), 30 fresh photos of all 23 targets: 17 of 30 pass (57%), every miss on the 0.048 margin; passing top-1 scores overlap wrong-genus ones, so no floor (`docs/results/day-3/calibration.log`)
- [x] **S51 Holdout** — 20–30 free photos, never used in calibration, including non-plant negatives (screens, people, pavement) → single-photo pass rate ≥ 45% (lowered Oct 8 from 90%; 48% measured), 0 toxic passes, false pass ≤ 5%, hazard false-alarm rate recorded (H6); Oct 8, 72 fresh photos on the laptop: 16 of 30 targets pass (53%), 0 of 15 toxic, 0 of 57 non-plant or wrong-genus, hazard false alarm 1 of 30 (a winged sumac read as poison oak); focus rows can't run on stills (`docs/results/day-3/holdout.log`)
- [ ] **S52 Field test** — screen time per target, find rate per target, and the wide-shot false-pass rate measured live on the test phone, since "walk closer" depends on real autofocus readings
- [ ] **S05 Gate harness** (app, debug only) — records verify time per capture frame, what BioCLIP saw (top and hazard species), capture to Found, RAM, and a 20-minute thermal run to a local exportable log; holes 10, 13; the "Wild Find gate" launcher icon starts an untethered run (target water oak) and `make gate-harness` (`GATE_TARGET`) starts one over adb; `make gate-pull` copies the CSVs into `docs/results/` and summarizes them; ticks after a logged 20-minute capture-mode run, unplugged, on each test phone (Galaxy S24 Ultra, Pixel 9) aimed at a real plant; runs last, once the game is playable
- [ ] **S53 Release** — release keystore (local, never committed), R8 minify, signed APK on a GitHub Release, About screen credits; install the release build on the test phone and run a first hunt from it; H10; before release day, measure the release APK and list what fills it: the debug APK was 341.6 MB on Oct 8, far over the models, tables, sprites, and map combined
- [ ] **S54 Demo + post** — outdoor demo video; post explains the Oct 7 redesign from `docs/results/day-2/`

## Hazards worldwide · build-time Gemma

- [ ] **S56 Build-time hints** (pipeline + core + app) — PRD Decisions: Hints.
  - **Probe gate:** run the Day-4 probe on Gemma 4 12b and 26b. Grade every grounded hint against its quoted sentence and pick a model, or stop if none writes trustworthy hints. Record it in `docs/results/day-4.md`.
  - **Pipeline:** `make hints` writes ranked, auto-checked hints for every playable row. `make hint-grades` has Claude grade each one. `pipeline/data/hints.json` is committed with each hint's evidence and verdict.
  - **Spot-check:** a random 50 go to the owner to grade, and hints ship only on agreement.
  - **App:** `make assets` merges the supported hints into species_labels.json, `SpeciesRow` carries them (core's `Hint`, `Season`, and `forMonth` order them by the device month, and `HuntPick` prefers hinted species), and the screens show them per the design update.

- [ ] **S55 Contact-hazard list** (pipeline + core) — the warning card knows only 7 North American hazards (every *Toxicodendron*, pokeweed, Carolina horsenettle), so it never fires in places like Tbilisi or Borjomi, even beside giant hogweed. Local toxic flags already block target passes everywhere, but they show no card, and "toxic" can't drive the card: 2,161 of 4,272 rows are flagged, mostly for eating or for a stub article, so a top-5 check on them would warn on nearly every photo.
  - **Build:** a build-time step runs Gemma locally over each species row's committed evidence (Wikipedia text, USDA traits). It answers one narrow question: is this a contact or handling hazard (urushiol, phototoxic sap, stinging hairs, irritant latex, spines)? Each answer cites its sentence and lands in a committed file beside `toxicity.json`. Uncertain or uncited rows go to hand review. Rows that fail stay off the list.
  - **Text:** hazard text comes from the same file and passes the banned-copy check.
  - **App:** the hazard check reads the list instead of the fixed 7. No new model ships, since BioCLIP already names the species.
  - **Gate before shipping:** re-measure on the S50 and S51 photo sets plus new contact-hazard photos from other regions. Hazard catch must stay at least 48 of 52, and safe photos warned must stay at most 1 in 250. Tune the top-5 cutoff if the longer list costs false warnings. Record the run in `docs/results/` the same day.
  - **PRD:** this reverses the fixed hazard list in Build Pipeline and verify row 1, so the PRD changes in the same commit.

## Open holes

New holes found while drafting these stories. PRD holes 4, 10, and 19 still stand (19 only until the field test). H-numbers below are this file's own list, separate from PRD hole numbers; H9 was never assigned.

| # | Hole | Proposed fix | Severity |
| --- | --- | --- | --- |
| H1 | Grass tutorial target isn't in any contract: no taxon, label row, or floor | Resolved by the redesign: grass is a fixed tutorial label in `labels.json` (R3), not a target | Low |
| H2 | "Only the current hunt persists" vs flags that must survive (opener seen, tutorial done, models verified) | Allow a fixed list of app flags; no history beyond them | Low |
| H3 | Widened 150 km counts get cached under the same key as 75 km counts | Resolved: the PRD cache entry carries `radius_km`; mismatch discards | Low |
| H4 | R10 never says what judges "still rooted" | Resolved Oct 8: R10 and S40 dropped for v1 | Low |
| H5 | Fallback file is October-only; offline cold starts after October get October targets | Resolved by the redesign: no fallback file; a region needs one online pull | Low |
| H6 | Hazard false alarms on safe plants | Resolved on Day 1 by scoring hazards against BioCLIP's species table (4,271 species plus *T. pubescens*) (warn when a hazard species is in the top 5): 48 of 52 hazards caught, 1 of 253 safe photos warned. Against menu labels alone, magnolia warned 10 of 10 and honeysuckle 9 of 10 | Low |
| H7 | Build-time menu gate uses 75 km while the app can widen to 150 km | Resolved by the redesign: no build-time gate; the app applies its sighting floor at whichever radius it queried | Low |
| H8 | LiteRT-LM Python ships as a CLI; prompt-in, JSON-out scripting is unverified | Resolved Oct 7: `litert-lm-api` 0.18.0 has a Python `Engine` with seeded sampling; the candidate prompt returns a bare JSON array on the laptop CPU in about 2 s (`docs/results/day-2/candidates.log`); then the redesign removed build-time Gemma | Low |
| H11 | The toxicity flag catches white, blackjack, and post oak, yarrow, a grass, a moss, and box elder; as blockers they cost 21 of the 35 finds the margin rule loses | Resolved Oct 8 by measurement: unflagging the wrong-sense flags gains one target (black walnut, 35 of 72) and unflagging livestock-only ones two more (37 of 78), with 0 toxic false passes either way; BioCLIP's look-alike confusion, not the flags, costs the finds. The rule stays; water lettuce shows why (`docs/results/day-3/toxicity_audit.md`) | Low |
| H10 | No release keystore plan for the GitHub Release APK | Local keystore, never committed; `keystore.properties` gitignored | Low |
| H12 | A Capture takes about 2 s on a mid-range phone (moto g stylus 2026: 390 to 1,250 ms a frame vs about 190 ms on the S24 Ultra), double the 1 s bar (`docs/results/day-3/device-moto.log`, PRD hole 4) | Future work, not v1: measure a real capture on the moto first. Candidates, each to be measured against the Day-1 verdicts before it ships: fewer frames per capture on slow phones; run TinyCLIP on the reticle crop only when it decides; run the plant gate and BioCLIP on separate threads; tune ONNX Runtime's thread count or try its XNNPACK provider; a smaller analysis size. fp16 stays out: it returned NaN on ARM | Medium |
| H13 | The warning card covers 7 North American hazards only; other regions get blockers but no card, even beside giant hogweed | S55: a build-time contact-hazard list, gated on the hazard catch and false-warning rates | High |
