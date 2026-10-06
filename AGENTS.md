# AGENTS.md

Spec of record: `docs/PRD.md`. Work queue: `docs/stories.md`. A change that contradicts the PRD updates the PRD in the same commit.

## Layout

| Path | Owns | Rule |
| --- | --- | --- |
| `core/` | Game logic: contracts, region, sightings, cache, hunt pick, verify decision, hint guards | Pure Kotlin JVM. No `android.*` imports. Every rule is unit-tested here |
| `app/` | Compose UI, Rive, CameraX, LiteRT-LM, ONNX Runtime, model download, iNat HTTP | Thin adapters over `core`. Logic that can run on the JVM moves to `core` |
| `pipeline/` | Build-time menu, fact cards, label embeddings | uv only. Shipped outputs land in `app/src/main/assets/`; test references in `app/src/androidTest/assets/` |
| `assets/source/` | Original art | Reference input. Never edit or regenerate |

## Hard rules

- Kid copy never says safe, harmless, not poisonous, or okay to touch.
- No photo or device coordinate leaves the phone. Network = iNat `species_counts` + Hugging Face model download. Nothing else.
- No analytics, crash SDKs, accounts, or remote config.
- Region membership = rounded whole-degree key match. The iNat query sends the region center.
- Open-weight models only at app time. Never swap the inference runtime (LiteRT-LM, ONNX Runtime) to dodge a gap.
- No iOS, KMP, `expect`/`actual`, or Vestige code.
- Model pins live in `core/src/main/resources/models.properties`; `docs/PRD.md` Data Contracts mirrors them. Change both in one commit.

## UI

- Animation-first. Every screen transition and state change is animated; no static form or list screens.
- Briar (the mascot) and the opener are Rive state machines. Game events drive Rive inputs; Compose never draws Briar's frames.
- 48 dp touch targets, content descriptions, no color-only signal, sunlight contrast.
- Invoke `/compose-skill` before touching `@Composable` code. Invoke `/litertlm-android-sdk` before touching Gemma code.

## Checks

- `make ai-checks` before every commit. Warnings fail the build.
- Every measurement (latency, memory, accuracy, model comparisons) goes into `docs/results/` the same day, with date, device, versions, and inputs. The challenge post is written from it; numbers that only live in chat are lost.
- `make setup` once per clone (lefthook + uv sync).
- Local settings live in `.env` (copy `.env.example`). New variables go in `.env.example` with a one-line comment. Never commit `.env`.
- Public Kotlin and Python API gets a one-line KDoc/docstring; detekt and ruff `D1` enforce it. Inline comments explain why, never what.
- `make assets` builds the bundled models and tables into gitignored `app/generated/assets`; every build fails without them. CI runs it with a cache.
- `make hazard-vectors` is the only step that pulls the 3.9 GB BioCLIP teacher; commit its `pipeline/data/hazard_vectors.json`. CI never runs it.
- `make fetch-models` then `make push-models` sideloads Gemma to the test phone.
- Never `adb uninstall` the app on the test phone; it deletes the 2.6 GB model. `make install` keeps data.
- Check that needs the user (field test, airplane mode, anything physical) → stop and hand it over; never tick it yourself. Automated on-device tests run with `make device-test` when the phone is attached; tick those on a logged pass.
