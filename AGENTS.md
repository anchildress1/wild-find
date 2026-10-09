# AGENTS.md

Spec of record: `docs/PRD.md`. Work queue: `docs/stories.md`. A change that contradicts the PRD updates the PRD in the same commit.

## Layout

| Path | Owns | Rule |
| --- | --- | --- |
| `core/` | Game logic: contracts, region, sightings, cache, hunt pick, verify decision | Pure Kotlin JVM. No `android.*` imports. Every rule is unit-tested here |
| `app/` | Compose UI, sprite playback, CameraX, ONNX Runtime, iNat HTTP | Thin adapters over `core`. Logic that can run on the JVM moves to `core` |
| `pipeline/` | Build-time species table, toxicity flags, tutorial label embeddings, plant gate | uv only. Committed shipped outputs land in `app/src/main/assets/`; generated bundled models and tables in gitignored `app/generated/assets/` (`make assets`); test references in `app/src/androidTest/assets/` |
| `assets/source/` | Original art | Reference input. Never edit or regenerate |

## Hard rules

- Kid copy never says safe, harmless, not poisonous, or okay to touch.
- No photo or device coordinate leaves the phone. Network = iNat `species_counts`. Nothing else.
- No analytics, crash SDKs, accounts, or remote config.
- Region membership = rounded whole-degree key match. The iNat query sends the region center.
- Open-weight models only at app time. Never swap the inference runtime (ONNX Runtime) to dodge a gap.
- No language model in the app; Gemma was measured and dropped Oct 7 (`docs/results/day-2.md`).
- No iOS, KMP, `expect`/`actual`, or Vestige code.
- Model pins live in `core/src/main/resources/models.properties`; `docs/PRD.md` Data Contracts mirrors them. Change both in one commit.

## UI

- Animation-first. Every screen transition and state change is animated; no static form or list screens.
- Briar (the mascot) and the opener are animated WebPs packed from videos per the PRD Briar animation contract. Game events pick the state; Compose plays the frames and never draws or composites Briar itself.
- 48 dp touch targets, content descriptions, no color-only signal, sunlight contrast.
- Invoke `/compose-skill` before touching `@Composable` code.

## Checks

- `make ai-checks` before every commit. Warnings fail the build.
- Every measurement (latency, memory, accuracy, model comparisons) goes into `docs/results/` the same day, with date, device, versions, and inputs. The challenge post is written from it; numbers that only live in chat are lost.
- `make setup` once per clone (lefthook + uv sync).
- Local settings live in `.env` (copy `.env.example`). New variables go in `.env.example` with a one-line comment. Never commit `.env`.
- Public Kotlin and Python API gets a one-line KDoc/docstring; detekt and ruff `D1` enforce it. Inline comments explain why, never what.
- `make assets` builds the bundled models and tables into gitignored `app/generated/assets`; every build fails without them. CI runs it with a cache.
- `make hazard-vectors`, `make labels`, and `make reference` pull the 3.9 GB BioCLIP teacher; commit `pipeline/data/hazard_vectors.json`, `app/src/main/assets/labels.*`, and the references they write. `make assets` and CI never need the teacher.
- Never `adb uninstall` the app on the test phone; it deletes gate-harness runs not yet pulled. `make install` keeps data.
- Check that needs the user (field test, airplane mode, anything physical) → stop and hand it over; never tick it yourself. Automated on-device tests run with `make device-test` when the phone is attached; tick those on a logged pass.
