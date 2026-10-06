# 🌱 wild-find v1 stories

The work queue for v1. Every story traces to a PRD requirement (R#), a Day-1 gate item, or a hole (H#). Tick the box when it ships; edit the story when it can't ship as written.

Module key: **core** = pure Kotlin, JVM-tested · **app** = Android · **pipeline** = uv Python.

## Day 1 · Oct 6 · go/no-go gate

Both models run on the test phone, or nothing else starts.

- [x] **S01 Sideload models** (Makefile) — `make fetch-models` pulls and verifies both pins into `.models/`; `make push-models` streams them into the debug app's `no_backup/models` over adb and verifies SHA-256 on the device; hole 12
- [x] **S02 BioCLIP reference** (pipeline) — `make reference` embeds a CC0 northern red oak fixture (iNat 363799243, 3 agreeing IDs) and the word, hazard, and scene labels into `app/src/androidTest/assets/reference/`; fails unless the fixture word is top-1
- [ ] **S03 BioCLIP on device** (app) — ONNX Runtime loads `flora_student_fp16.onnx`; fixture embedding matches S02 within tolerance; labels score correctly
- [ ] **S04 Gemma boxes** (core + app) — LiteRT-LM loads E2B; box call returns JSON that the core box parser accepts (clamp, reject inverted/zero-area, keep 5 largest)
- [ ] **S05 Gate harness** (app, debug only) — records verify latency, hint latency, RAM, and a 20-minute thermal run to a local exportable log; holes 4, 10, 13, 18
- [ ] **S06 Scene label verdict** (app) — scene + non-plant labels vs BioCLIP Mobile; on failure switch to the Gemma plant / non-plant / screen fallback; hole 17
- [ ] **S07 Model download** (core + app) — R9; see Download rules below; proven on the test phone: full pull, kill mid-pull + resume, Wi-Fi drop + resume, bad-hash retry
- [x] **S08 Debug/release side by side** (app) — debug uses `applicationIdSuffix = ".debug"` so a release install never wipes the debug app's 2.6 GB model on the one test phone

## Build pipeline · Oct 7

- [ ] **S10 Candidates** — build-time Gemma via LiteRT-LM Python on the same `.litertlm`; candidate prompt; H8
- [ ] **S11 Resolve** — iNat taxa search; Plantae; rank gate; ambiguous → manual review file
- [ ] **S12 Gates** — hazard drop, denylist drop, 25+ local sightings
- [ ] **S13 Fact cards** — Wikipedia text → card prompt → `icon_category`
- [ ] **S14 Fact-check** — manual Claude Code pass; verdicts committed as a review file for the post's disclosure
- [ ] **S15 Embeddings** — BioCLIP 2.5 ViT-H text encoder → `labels.npy` + `labels.json`; label text format per hole 3
- [ ] **S16 Fallback + output** — `menu.json`, `hazards.json`, fallback file; Ashley's ship review

## Game logic · core

- [ ] **S20 Contracts** — parse and validate `menu.json`, `hazards.json`, `labels.json`, cache entry; reject unknown `schema_version`
- [x] **S21 Region key** — whole-degree rounding; supported iff key is `34_-85`
- [ ] **S22 Sightings** — aggregate `species_counts` pages via id or `ancestor_ids`; eligibility at 25+; widen to 150 km once when < 3 eligible; R2
- [ ] **S23 Cache rules** — versioned entry; mismatch on schema, menu, region, month, or radius discards; H3
- [ ] **S24 Hunt pick** — sighting-weighted random, 3 targets, never a hazard; grass tutorial first-ever only; R3, R4, H1
- [ ] **S25 Verify decision** — PRD verify table in order; 15% box rule; floor + optional margin; multi-photo-ready input; R5, R12
- [ ] **S26 Hint guards** — target name, "I see", "there is", off-card numbers, 20 words; retry once → template; R6
- [ ] **S27 Hunt state** — current hunt survives process death; tutorial/opener flags persist; H2

## App · Oct 8–9

- [ ] **S30 Briar host** — placeholder Briar with the five states (welcome, searching/hint, found, retry, complete) driven by game events; final Rive art drops in later
- [ ] **S31 Safety opener** — R1, placeholder art, replayable, banned-copy check in tests
- [ ] **S32 Location** — coarse permission only, manual region pick on deny; coverage message off-region; R2, R7
- [ ] **S33 iNat client** — one query, ≤ 3 pages (≤ 6 on the widen path), named User-Agent, 429 Retry-After; R2
- [ ] **S34 Camera + verify flow** — CameraX capture, rotation normalize, Gemma boxes → BioCLIP crops → S25 → kid message; R5
- [ ] **S35 Hints** — levels 1/3 precomputed at hunt start, level 2 from last failed photo or current frame; R6
- [ ] **S36 Hunt complete** — success animation, stars, Hunt Again / Home; R15
- [ ] **S37 Offline** — airplane-mode hunt from cache or bundled fallback; R8
- [ ] **S38 Lifecycle** — Gemma released on background, reloaded on resume; hunt state restored
- [ ] **S39 Privacy proof** — network log on the test phone shows only iNat + HF; R7
- [ ] **S40 Leave-it star** (P1) — R10; H4

## Ship · Oct 9–11

- [ ] **S50 Calibration** — ~30 free photos (CC0 or public domain, iNaturalist research grade) → per-target floors + margin decision
- [ ] **S51 Holdout** — 20–30 free photos, never used in calibration, including non-plant negatives (screens, people, pavement, wide shots) → pass rate ≥ 90%, false pass ≤ 5%, hazard false-alarm rate recorded (H6)
- [ ] **S52 Field test** — screen time per target, find rate after a hint
- [ ] **S53 Release** — release keystore (local, never committed), R8 minify, signed APK on a GitHub Release, About screen credits; install the release build on the test phone and run a full first-launch download from it; H10
- [ ] **S54 Demo + post** — outdoor demo video; post discloses the Claude fact-check

## Download rules

The vestige download broke when Hugging Face moved its redirect CDN (`cas-bridge.xethub.hf.co` → `us.aws.cdn.hf.co`) and the app had the old host pinned. On Oct 5 both wild-find files redirect to `us.aws.cdn.hf.co`, report sizes and SHA-256 etags that match the PRD, and answer byte ranges with 206.

- Pin the start URL (`huggingface.co`, repo, revision, file), never the CDN host. Follow HTTPS redirects only.
- Trust comes from exact byte count + SHA-256, so a CDN move can't break or spoof the download.
- Re-resolve the redirect on every attempt; signed CDN URLs expire.
- Preflight with a HEAD: `x-linked-size` and `x-linked-etag` must match the pins, or stop with a clear message before pulling 2.6 GB.
- Resume from a `.part` file with `Range`; require a 206 whose `Content-Range` starts at the `.part` length, else restart.
- Run as a foreground-service download with a notification so it survives the screen turning off.
- On failure, show which host failed, so a parent can tell a filtered network from an outage.
- Store under `noBackupFilesDir`. Never uninstall the app on the test phone; `make install` keeps data.

## Open holes

New holes found while drafting these stories. PRD holes 3, 4, 5, 8, 10, 12, 13, 17, 18 still stand.

| # | Hole | Proposed fix | Severity |
| --- | --- | --- | --- |
| H1 | Grass tutorial target isn't in any contract: no taxon, label row, or floor | Ship grass (Poaceae) as a flagged `tutorial` entry in `menu.json` with its own label row and floor | High |
| H2 | "Only the current hunt persists" vs flags that must survive (opener seen, tutorial done, models verified) | Allow a fixed list of app flags; no history beyond them | Low |
| H3 | Widened 150 km counts get cached under the same key as 75 km counts | Add `radius_km` to the cache entry; mismatch discards | Medium |
| H4 | R10 never says what judges "still rooted" | Gemma yes/no call on the found crop; P1, decide before S40 | Medium |
| H5 | Fallback file is October-only; offline cold starts after October get October targets | Accept for v1; note in the post | Low |
| H6 | Hazard check runs first, and on Day 1 the mobile model put white oak only 0.022 above poison oak (teacher: 0.069); its embedding sits at 0.81 cosine to the teacher's | Track hazard false-alarm rate on the holdout set; decide whether a hazard must beat the target by a margin before it warns | High |
| H7 | Build-time menu gate uses 75 km while the app can widen to 150 km | Widened hunts may surface fewer words; accept, or run the gate at 150 km | Medium |
| H8 | LiteRT-LM Python ships as a CLI; prompt-in, JSON-out scripting is unverified | Verify on Day 2 before S10; fall back to transformers in uv | Medium |
| H10 | No release keystore plan for the GitHub Release APK | Local keystore, never committed; `keystore.properties` gitignored | Low |
