# 🌱 wild-find v1 stories

The work queue for v1. Every story traces to a PRD requirement (R#), a Day-1 gate item, or a hole (H#). Tick the box when it ships; edit the story when it can't ship as written.

Module key: **core** = pure Kotlin, JVM-tested · **app** = Android · **pipeline** = uv Python.

## Day 1 · Oct 6 · go/no-go gate

Every runtime model (TinyCLIP, BioCLIP, Gemma) runs on the test phone, or nothing else starts.

- [x] **S01 Sideload models** (Makefile) — `make fetch-models` pulls and verifies the Gemma pin into `.models/`; `make push-models` streams it into the debug app's `no_backup/models` over adb and verifies SHA-256 on the device; hole 12 — **dropped Oct 7 with Gemma** (`docs/results/day-2.md`)
- [x] **S02 BioCLIP reference** (pipeline) — `make reference` embeds a CC0 northern red oak fixture (iNat 363799243, 3 agreeing IDs) and the word, hazard, and scene labels into `app/src/androidTest/assets/reference/`; fails unless the fixture word is top-1
- [x] **S03 BioCLIP on device** (app) — ONNX Runtime loads `flora_student_fp32.onnx` (fp16 returns NaN on ARM); `make device-test` passes: cosine 1.0000 to the S02 reference, oak top-1, load 135 ms, embed 58 ms on the test phone
- [x] **S04 Gemma on device** — E2B loads on the GPU without out-of-memory (2.1 GB loaded, 2.9 GB peak; 4.0 s warm load) and answers vision prompts in 2.3–2.9 s; too slow for the verify path, so boxing was dropped for deterministic live verify — **dropped Oct 7 with Gemma** (`docs/results/day-2.md`)
- [x] **S09 Close-range threshold** — `make focus-probe` logged live `LENS_FOCUS_DISTANCE` at far, closer, full-frame, and too-close shots, plus pinch zoom, on the test phone (a can, not a plant); rule: diopters × zoom ≥ 2.0 while autofocus reports focused; PRD hole 19
- [ ] **S05 Gate harness** (app, debug only) — records verify time per capture frame, what BioCLIP saw (top and hazard species), first eligible frame to Found, RAM, and a 20-minute thermal run to a local exportable log; holes 4, 10, 13; the "wild-find gate" launcher icon starts an untethered run (target oak) and `make gate-harness` (`GATE_WORD`) starts one over adb; `make gate-pull` copies the CSVs into `docs/results/` and summarizes them; ticks after a logged 20-minute capture-mode run on each test phone (Galaxy S24 Ultra, Pixel 9) aimed at a real plant; runs last, once the game is playable
- [x] **S06 Plant gate** (pipeline + app) — export TinyCLIP ViT-8M's image encoder to fp32 ONNX plus text vectors for the exact Day-1 gate prompts; pin it; `PlantGate` in core; on-device parity passes (cosine 0.99999999, 40 ms per embedding); S34 gates every frame with it; PRD hole 17
- [x] **S07 Gemma download** (core + app) — R9, Gemma only; starts on its own at launch as a user-initiated transfer job, never behind a wait screen; proven on the test phone (`docs/results/day-1.md`): full pull at about 32 MB/s, kill mid-pull + resume, a 60-second outage with the app closed resumed by the system in a new process, bad hash deleted and pulled again on the next launch; low storage is JVM-tested only; in-app progress lands with the opener (S31) — **dropped Oct 7 with Gemma** (`docs/results/day-2.md`)
- [x] **S08 Debug/release side by side** (app) — debug uses `applicationIdSuffix = ".debug"` so a release install never wipes the debug app's 2.6 GB model on the one test phone
- [x] **S08b Bundle the small models** (build) — `make assets` fetches BioCLIP and the taxa table and labels (SHA-checked), builds species_table.npy and species_labels.json (missing hazard species appended, hazard flags set), and exports TinyCLIP into gitignored `app/generated/assets`; the one missing hazard row comes from the committed `pipeline/data/hazard_vectors.json` (`make hazard-vectors`; it and `make reference` are the manual steps that need the 3.9 GB teacher, while `make assets` and CI never do); CI runs `make assets` with a cache; any build without the assets fails; the app loads all of them from the APK

## Build pipeline · Oct 7–8

The Oct 7 redesign (PRD Redesign) dropped the build-time menu: the old S10–S16 (candidates, resolve, gates, fact cards, fact-check, fallback) are gone, and iNat supplies targets live. S10 now names the toxicity flag; S15 keeps its number.

- [x] **S10 Toxicity flag** (pipeline) — `make toxicity` reads every species-table row's English Wikipedia article and USDA PLANTS ratings (GBIF synonyms); flags per the PRD rule; commits `pipeline/data/toxicity.json` with evidence and revision; `make assets` merges `toxic` and `genus` into species_labels.json; Oct 7 build: 2,170 of 4,272 flagged (1,369 stubs), 64 of the 104 common West Georgia species pass
- [ ] **S15 Tutorial labels** (pipeline) — `make labels` writes only the 11 fixed tutorial labels into `labels.npy` + `labels.json`; drop the stand-in menu words; label text format per hole 3
- [ ] **S17 Plant type** (pipeline) — USDA PLANTS growth habit per species-table row through GBIF synonyms, else fern, moss, grass, or conifer from taxonomy, else null; merged into species_labels.json as `type`; shown with the target name

## Game logic · core

- [ ] **S20 Contracts** — parse and validate `species_labels.json` (genus, hazard, toxic), `hazards.json`, `labels.json`, cache entry; reject unknown `schema_version`
- [x] **S21 Region key** — whole-degree rounding; supported iff key is `34_-85`
- [ ] **S28 Any region** (core) — redesign: drop the `34_-85`-only gate in `RegionKey`; any whole-degree key plays; R2
- [ ] **S22 Sightings** — aggregate `species_counts` pages; eligible at 25+ sightings, in the species table, not toxic or hazard, common name of 3 words or fewer in the device locale; widen to 150 km once when < 3 eligible; R2
- [ ] **S23 Cache rules** — versioned entry; mismatch on schema, table version, region, locale, month, or radius discards; H3
- [ ] **S24 Hunt pick** — sighting-weighted random, 3 targets, never two from one genus, never a hazard or toxic species; grass tutorial first-ever only; R3, R4, H1
- [x] **S25 Verify decision** — every PRD verify-table state over live frames, in order: hazard in a region TinyCLIP calls a plant (reticle crop or full frame), reticle not a plant, no focus reading, too far, target top-1 for 3 frames (auto-capture), else reticle guidance; floor + optional margin; R5, R12; `VerifyStreak` reports `Matching(1..2)` for the ring and "Hold still", then `Found`; the grass tutorial goal (R3) skips the hazard row; `FrameVerifier` runs the per-frame model path behind encoder interfaces, so all of it is JVM-tested
- [ ] ~~**S26 Hint guards**~~ — dropped Oct 7: no hints, no Gemma
- [ ] **S27 Hunt state** — current hunt survives process death; tutorial/opener flags persist; H2
- [ ] **S29 Target pass** (core) — redesign: row 4 passes when the top-scoring row among the hunt's eligible local species is the target or shares its genus, for 3 frames; never ranked over the whole table (Day 1: 89% against a few labels vs 60% over the table, `docs/results/day-2/target_pass.log`); drop floor + margin and menu-label scoring; R5

## App · Oct 8–9

- [ ] **S30 Briar host** — Compose sprite player for the PRD sheet contract, five states (welcome, searching, found, retry, complete) driven by game events; each state sheet plays once, then the 16-frame `idle` loops until Briar leaves the screen; every state plays `idle` until per-state art lands
- [ ] **S31 Safety opener** — R1, placeholder art, replayable, banned-copy check in tests
- [ ] **S32 Location** — coarse permission only, manual region pick on deny; coverage message off-region; R2, R7
- [ ] **S33 iNat client** — one query, ≤ 3 pages (≤ 6 on the widen path), device `locale` for common names, named User-Agent, 429 Retry-After; R2
- [ ] **S34 Camera + verify flow** — a Capture button verifies up to 3 frames back to back (the gate harness pattern); per frame: rotation-normalize once; cut the PRD crops (center square, 60% reticle); TinyCLIP on the reticle crop and full frame; BioCLIP hazard check on each region TinyCLIP calls a plant, ranked against local species plus hazards; reticle plant gate → target pass (S29); "Get closer or zoom in" only on a far miss; S25 → Found and kid message; R5
- [ ] ~~**S35 Hints**~~ — dropped Oct 7: the target's name and type show instead (S17)
- [ ] **S36 Hunt complete** — success animation, stars, Hunt Again / Home; R15
- [ ] **S37 Offline** — airplane-mode hunt from cache; a never-pulled region says it needs signal once; R8
- [ ] **S38 Lifecycle** — hunt state restored after the app goes to the background
- [ ] **S39 Privacy proof** — network log on the test phone shows only iNat; R7
- [ ] **S40 Leave-it star** (P1) — R10; H4

## Ship · Oct 9–11

- [ ] **S50 Calibration** — ~30 free photos (CC0 or public domain, iNaturalist research grade) → genus-pass rate, and whether any floor is needed; the hazard rule needs no calibration
- [ ] **S51 Holdout** — 20–30 free photos, never used in calibration, including non-plant negatives (screens, people, pavement) → pass rate ≥ 90%, false pass ≤ 5%, hazard false-alarm rate recorded (H6)
- [ ] **S52 Field test** — screen time per target, find rate per target, and the wide-shot false-pass rate measured live on the test phone, since "walk closer" depends on real autofocus readings
- [ ] **S53 Release** — release keystore (local, never committed), R8 minify, signed APK on a GitHub Release, About screen credits; install the release build on the test phone and run a first hunt from it; H10
- [ ] **S54 Demo + post** — outdoor demo video; post explains the Oct 7 redesign from `docs/results/day-2/`

## Open holes

New holes found while drafting these stories. PRD holes 3, 4, 5, 10, 12, 13, 19 still stand (19 only until the field test). H-numbers below are this file's own list, separate from PRD hole numbers.

| # | Hole | Proposed fix | Severity |
| --- | --- | --- | --- |
| H1 | Grass tutorial target isn't in any contract: no taxon, label row, or floor | Resolved by the redesign: grass is a fixed tutorial label in `labels.json` (R3), not a target | Low |
| H2 | "Only the current hunt persists" vs flags that must survive (opener seen, tutorial done, models verified) | Allow a fixed list of app flags; no history beyond them | Low |
| H3 | Widened 150 km counts get cached under the same key as 75 km counts | Resolved: the PRD cache entry carries `radius_km`; mismatch discards | Low |
| H4 | R10 never says what judges "still rooted" | No language model ships; decide a BioCLIP or rule-based check before S40, or drop R10 | Medium |
| H5 | Fallback file is October-only; offline cold starts after October get October targets | Resolved by the redesign: no fallback file; a region needs one online pull | Low |
| H6 | Hazard false alarms on safe plants | Resolved on Day 1 by scoring hazards against BioCLIP's species table (4,271 species plus *T. pubescens*) (warn when a hazard species is in the top 5): 48 of 52 hazards caught, 1 of 253 safe photos warned. Against menu labels alone, magnolia warned 10 of 10 and honeysuckle 9 of 10 | Low |
| H7 | Build-time menu gate uses 75 km while the app can widen to 150 km | Resolved by the redesign: no build-time gate; the app applies 25+ at whichever radius it queried | Low |
| H8 | LiteRT-LM Python ships as a CLI; prompt-in, JSON-out scripting is unverified | Resolved Oct 7: `litert-lm-api` 0.18.0 has a Python `Engine` with seeded sampling; the candidate prompt returns a bare JSON array on the laptop CPU in about 2 s (`docs/results/day-2/candidates.log`); then the redesign removed build-time Gemma | Low |
| H10 | No release keystore plan for the GitHub Release APK | Local keystore, never committed; `keystore.properties` gitignored | Low |
