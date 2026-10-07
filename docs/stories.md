# 🌱 wild-find v1 stories

The work queue for v1. Every story traces to a PRD requirement (R#), a Day-1 gate item, or a hole (H#). Tick the box when it ships; edit the story when it can't ship as written.

Module key: **core** = pure Kotlin, JVM-tested · **app** = Android · **pipeline** = uv Python.

## Day 1 · Oct 6 · go/no-go gate

Every runtime model (TinyCLIP, BioCLIP, Gemma) runs on the test phone, or nothing else starts.

- [x] **S01 Sideload models** (Makefile) — `make fetch-models` pulls and verifies the Gemma pin into `.models/`; `make push-models` streams it into the debug app's `no_backup/models` over adb and verifies SHA-256 on the device; hole 12
- [x] **S02 BioCLIP reference** (pipeline) — `make reference` embeds a CC0 northern red oak fixture (iNat 363799243, 3 agreeing IDs) and the word, hazard, and scene labels into `app/src/androidTest/assets/reference/`; fails unless the fixture word is top-1
- [x] **S03 BioCLIP on device** (app) — ONNX Runtime loads `flora_student_fp32.onnx` (fp16 returns NaN on ARM); `make device-test` passes: cosine 1.0000 to the S02 reference, oak top-1, load 135 ms, embed 58 ms on the test phone
- [x] **S04 Gemma on device** — E2B loads on the GPU without out-of-memory (2.1 GB loaded, 2.9 GB peak; 4.0 s warm load) and answers vision prompts in 2.3–2.9 s; too slow for the verify path, so boxing was dropped for deterministic live verify
- [x] **S09 Close-range threshold** — `make focus-probe` logged live `LENS_FOCUS_DISTANCE` at far, closer, full-frame, and too-close shots, plus pinch zoom, on the test phone (a can, not a plant); rule: diopters × zoom ≥ 2.0 while autofocus reports focused; PRD hole 19
- [ ] **S05 Gate harness** (app, debug only) — records per-frame verify time, first-eligible-frame to Found, hint latency, RAM, and a 20-minute live-camera thermal run to a local exportable log; holes 4, 10, 13; `make gate-harness` (`GATE_WORD`) runs the real verify path at the default 1920 x 1440 analysis size and the level-2 hint on the phone, `make gate-pull` copies the CSVs into `docs/results/` and summarizes them; ticks after a logged 20-minute run on each test phone (Galaxy S24 Ultra, Pixel 9)
- [x] **S06 Plant gate** (pipeline + app) — export TinyCLIP ViT-8M's image encoder to fp32 ONNX plus text vectors for the exact Day-1 gate prompts; pin it; `PlantGate` in core; on-device parity passes (cosine 0.99999999, 40 ms per embedding); S34 gates every frame with it; PRD hole 17
- [x] **S07 Gemma download** (core + app) — R9, Gemma only; starts on its own at launch as a user-initiated transfer job, never behind a wait screen; see Download rules below; proven on the test phone (`docs/results/day-1.md`): full pull at about 32 MB/s, kill mid-pull + resume, a 60-second outage with the app closed resumed by the system in a new process, bad hash deleted and pulled again on the next launch; low storage is JVM-tested only; in-app progress lands with the opener (S31)
- [x] **S08 Debug/release side by side** (app) — debug uses `applicationIdSuffix = ".debug"` so a release install never wipes the debug app's 2.6 GB model on the one test phone
- [x] **S08b Bundle the small models** (build) — `make assets` fetches BioCLIP and the taxa table and labels (SHA-checked), builds species_table.npy and species_labels.json (missing hazard species appended, hazard flags set), and exports TinyCLIP into gitignored `app/generated/assets`; the one missing hazard row comes from the committed `pipeline/data/hazard_vectors.json` (`make hazard-vectors`; it and `make reference` are the manual steps that need the 3.9 GB teacher, while `make assets` and CI never do); CI runs `make assets` with a cache; any build without the assets fails; the app loads all of them from the APK

## Build pipeline · Oct 7

- [ ] **S10 Candidates** — build-time Gemma via LiteRT-LM Python on the same `.litertlm`; candidate prompt; H8
- [ ] **S11 Resolve** — iNat taxa search; Plantae; rank gate; ambiguous → manual review file
- [ ] **S12 Gates** — hazard drop, denylist drop, 25+ local sightings
- [ ] **S13 Fact cards** — Wikipedia text → card prompt → `icon_category`
- [ ] **S14 Fact-check** — manual Claude Code pass; verdicts committed as a review file for the post's disclosure
- [ ] **S15 Embeddings** — BioCLIP 2.5 ViT-H text encoder → `labels.npy` + `labels.json`; label text format per hole 3; `make labels` already writes them for the 11 fixed tutorial labels plus a stand-in menu of the five Day-1 targets, committed to `app/src/main/assets/`; done when the built menu feeds it
- [ ] **S16 Fallback + output** — `menu.json`, `hazards.json`, fallback file; Ashley's ship review

## Game logic · core

- [ ] **S20 Contracts** — parse and validate `menu.json`, `hazards.json`, `labels.json`, cache entry; reject unknown `schema_version`
- [x] **S21 Region key** — whole-degree rounding; supported iff key is `34_-85`
- [ ] **S22 Sightings** — aggregate `species_counts` pages via id or `ancestor_ids`; eligibility at 25+; widen to 150 km once when < 3 eligible; R2
- [ ] **S23 Cache rules** — versioned entry; mismatch on schema, menu, region, month, or radius discards; H3
- [ ] **S24 Hunt pick** — sighting-weighted random, 3 targets, never a hazard; grass tutorial first-ever only; R3, R4, H1
- [x] **S25 Verify decision** — every PRD verify-table state over live frames, in order: hazard in a region TinyCLIP calls a plant (reticle crop or full frame), reticle not a plant, no focus reading, too far, target top-1 for 3 frames (auto-capture), else reticle guidance; floor + optional margin; R5, R12; `VerifyStreak` reports `Matching(1..2)` for the ring and "Hold still", then `Found`; the grass tutorial goal (R3) skips the hazard row; `FrameVerifier` runs the per-frame model path behind encoder interfaces, so all of it is JVM-tested
- [ ] **S26 Hint guards** — target name, "I see", "there is", off-card numbers, 20 words; retry once → template; R6
- [ ] **S27 Hunt state** — current hunt survives process death; tutorial/opener flags persist; H2

## App · Oct 8–9

- [ ] **S30 Briar host** — Compose sprite player for the PRD sheet contract, five states (welcome, searching/hint, found, retry, complete) driven by game events; each state sheet plays once and holds its last frame; every state plays the 16-frame `idle` loop until per-state art lands
- [ ] **S31 Safety opener** — R1, placeholder art, replayable, banned-copy check in tests
- [ ] **S32 Location** — coarse permission only, manual region pick on deny; coverage message off-region; R2, R7
- [ ] **S33 iNat client** — one query, ≤ 3 pages (≤ 6 on the widen path), named User-Agent, 429 Retry-After; R2
- [ ] **S34 Camera + verify flow** — gate every frame with `PlantGate` before BioCLIP; CameraX preview at about 5 fps: rotation-normalize the frame once; cut the PRD crops (center square, 60% reticle); TinyCLIP on the reticle crop and full frame; BioCLIP hazard check on each region TinyCLIP calls a plant; reticle plant gate → BioCLIP target; autofocus distance → "walk closer"; S25 → auto-capture and kid message; R5
- [ ] **S35 Hints** — levels 1/3 precomputed at hunt start, level 2 from the current camera frame at tap time; R6
- [ ] **S36 Hunt complete** — success animation, stars, Hunt Again / Home; R15
- [ ] **S37 Offline** — airplane-mode hunt from cache or bundled fallback; R8
- [ ] **S38 Lifecycle** — Gemma released on background, reloaded on resume; hunt state restored
- [ ] **S39 Privacy proof** — network log on the test phone shows only iNat + HF; R7
- [ ] **S40 Leave-it star** (P1) — R10; H4

## Ship · Oct 9–11

- [ ] **S50 Calibration** — ~30 free photos (CC0 or public domain, iNaturalist research grade) → per-target floors + target margin decision; the hazard rule needs no calibration
- [ ] **S51 Holdout** — 20–30 free photos, never used in calibration, including non-plant negatives (screens, people, pavement) → pass rate ≥ 90%, false pass ≤ 5%, hazard false-alarm rate recorded (H6)
- [ ] **S52 Field test** — screen time per target, find rate after a hint, and the wide-shot false-pass rate measured live on the test phone, since "walk closer" depends on real autofocus readings
- [ ] **S53 Release** — release keystore (local, never committed), R8 minify, signed APK on a GitHub Release, About screen credits; install the release build on the test phone and run a full first-launch download from it; H10
- [ ] **S54 Demo + post** — outdoor demo video; post discloses the Claude fact-check

## Download rules

The vestige download broke when Hugging Face moved its redirect CDN (`cas-bridge.xethub.hf.co` → `us.aws.cdn.hf.co`) and the app had the old host pinned. On Oct 5 both wild-find files redirect to `us.aws.cdn.hf.co`, report sizes and SHA-256 etags that match the PRD, and answer byte ranges with 206.

- Pin the start URL (`huggingface.co`, repo, revision, file), never the CDN host. Follow HTTPS redirects only.
- Trust comes from exact byte count + SHA-256, so a CDN move can't break or spoof the download.
- Re-resolve the redirect on every attempt; signed CDN URLs expire.
- Preflight with a HEAD: `x-linked-size` and `x-linked-etag` must match the pins, or stop with a clear message before pulling 2.6 GB.
- Resume from a `.part` file with `Range`; require a 206 whose `Content-Range` starts at the `.part` length, else restart.
- Run as a user-initiated data transfer job (Android 14+, so minSdk is 34) with a progress notification, so it survives the screen turning off and the system reruns it after a dropped network with the app closed. A foreground service can't restart from the background on Android 12+.
- On failure, show which host failed, so a parent can tell a filtered network from an outage.
- Store under `noBackupFilesDir`. Never uninstall the app on the test phone; `make install` keeps data.

## Open holes

New holes found while drafting these stories. PRD holes 3, 4, 5, 10, 12, 13, 19 still stand (19 only until the field test). H-numbers below are this file's own list, separate from PRD hole numbers.

| # | Hole | Proposed fix | Severity |
| --- | --- | --- | --- |
| H1 | Grass tutorial target isn't in any contract: no taxon, label row, or floor | Ship grass (Poaceae) as a flagged `tutorial` entry in `menu.json` with its own label row and floor | High |
| H2 | "Only the current hunt persists" vs flags that must survive (opener seen, tutorial done, models verified) | Allow a fixed list of app flags; no history beyond them | Low |
| H3 | Widened 150 km counts get cached under the same key as 75 km counts | Resolved: the PRD cache entry carries `radius_km`; mismatch discards | Low |
| H4 | R10 never says what judges "still rooted" | Gemma yes/no call on the found crop; P1, decide before S40 | Medium |
| H5 | Fallback file is October-only; offline cold starts after October get October targets | Accept for v1; note in the post | Low |
| H6 | Hazard false alarms on safe plants | Resolved on Day 1 by scoring hazards against BioCLIP's species table (4,271 species plus *T. pubescens*) (warn when a hazard species is in the top 5): 48 of 52 hazards caught, 1 of 253 safe photos warned. Against menu labels alone, magnolia warned 10 of 10 and honeysuckle 9 of 10 | Low |
| H7 | Build-time menu gate uses 75 km while the app can widen to 150 km | Widened hunts may surface fewer words; accept, or run the gate at 150 km | Medium |
| H8 | LiteRT-LM Python ships as a CLI; prompt-in, JSON-out scripting is unverified | Verify on Day 2 before S10; fall back to transformers in uv | Medium |
| H10 | No release keystore plan for the GitHub Release APK | Local keystore, never committed; `keystore.properties` gitignored | Low |
