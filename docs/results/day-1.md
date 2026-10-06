# 📏 Day-1 results (October 6, 2026)

Measured results from the Day-1 go/no-go gate. Every number here was observed, not estimated. The test photos and their licenses are listed in [day-1-photos.tsv](day-1-photos.tsv).

## Setup

| Item | Value |
| --- | --- |
| Phone | Samsung Galaxy S24 Ultra (SM-S928U), Snapdragon 8 Gen 3 (SM8650), 11 GB RAM, Android 16 (SDK 36) |
| On-device runtimes | ONNX Runtime Android 1.30.0 (CPU); LiteRT-LM Android 0.17.1 (GPU text and vision backends) |
| Laptop | macOS, ONNX Runtime 1.30.0, open_clip 3.3.0, torch 2.14.1 |
| Gemma | `gemma-4-E2B-it.litertlm`, litert-community @ b3ca0d2 |
| BioCLIP Mobile | crazedcodernate/bioclip-2.5-mobile-fastvit @ 29b474e (fp16 and fp32 files) |
| BioCLIP teacher | imageomics/bioclip-2.5-vith14 @ 6e3d04e |
| TinyCLIP | wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M @ a2a8c6e, plus the 39M/16 and 40M/32 variants |

## Gemma 4 E2B on the phone

Gemma was measured on box prompts, then removed from the verify path because every vision call takes seconds.

| Measure | Result |
| --- | --- |
| First-ever load | 9,892 ms |
| Warm load (GPU program cache present) | 4,006 ms |
| Memory after load (PSS) | 2,084 to 2,100 MB |
| Peak memory during a call (PSS) | 2,889 to 2,921 MB |
| Free system memory after load | 2,915 MB |
| Vision call, first after load | 4,393 ms |
| Vision call, warm | 2,312 / 2,407 / 2,540 / 2,886 ms |

- **Box output:** a red oak photo drawn only in the top-right quadrant of a white canvas came back as `box_2d [0, 500, 497, 1000]`, which is `[ymin, xmin, ymax, xmax]` on a 0 to 1000 grid. That's the right quadrant.
- **Custom JSON schema:** asking for `{"boxes":[{"x1":…}]}` was ignored; Gemma still replied in native `box_2d` (`[0, 500, 496, 1000]`).
- **Why it left verify:** a live camera needs an answer every 200 ms or so. Gemma takes 2.3 to 2.9 s warm, and image reading dominates, so a shorter yes/no prompt wouldn't close the gap.

## BioCLIP 2.5 Mobile on the phone

### fp16 vs fp32

The pinned fp16 file returned garbage on the phone, while the laptop was fine.

| Run | First three embedding values |
| --- | --- |
| Laptop, fp16, all graph optimizations | 0.014453, -0.000195, 0.037192 |
| Laptop, fp32 | 0.014505, -0.000136, 0.037053 |
| Phone, fp16, all graph optimizations | NaN (all 1,024 values) |
| Phone, fp16, optimizations off | -0.018356, -0.030457, -0.001832 (wrong; signs flip) |
| Phone, fp32, all graph optimizations | 0.014505, -0.000136, 0.037053 |

BioCLIP is now pinned to the fp32 file (46,986,589 bytes).

### Parity and speed (fp32)

| Measure | Result |
| --- | --- |
| Model load | 128 to 138 ms |
| One embedding | 58 to 60 ms in most runs; one run took 132 ms |
| Cosine, phone vs laptop (both normalized) | 0.9999999988 |
| Top-1 on the red oak fixture | oak, 0.5199 (same scores as the laptop) |

## Label text format

Fixture: a CC0 white oak, [iNaturalist 211670015](https://www.inaturalist.org/observations/211670015). Scores are cosine similarity.

| Prompt format | Model | Top-1 | Top three |
| --- | --- | --- | --- |
| Common name ("a photo of oak.") | Mobile | **poison oak** | poison oak 0.456, poison ivy 0.400, oak 0.365 |
| Common name | Teacher | **poison oak** | poison oak 0.388, poison ivy 0.352, oak 0.329 |
| Scientific ("a photo of Quercus.") | Mobile | oak | oak 0.478, poison oak 0.456, poison ivy 0.393 |
| Scientific | Teacher | oak | oak 0.414, poison ivy 0.345, poison oak 0.331 |
| Full taxonomic string | Mobile | oak | oak 0.477, poison oak 0.457, poison ivy 0.395 |
| Taxonomic plus common name | Mobile | **poison oak** | poison oak 0.457, oak 0.406, poison ivy 0.398 |

- Mobile vs teacher image embedding on that photo: cosine 0.811.
- On the red oak fixture used for the parity reference ([iNaturalist 363799243](https://www.inaturalist.org/observations/363799243)), oak wins by 0.136 over Carolina horsenettle.
- Plant labels now embed by scientific name.

## Non-plant photos

22 CC0 or public-domain photos of screens, people, roads, sidewalks, cars, dogs, rooms, and lawns, plus 16 CC0 iNaturalist plant photos (oak, fern, clover, pine, dandelion, grass, moss, poison ivy).

**BioCLIP Mobile can't reject non-plants.** Against 5 plant targets, 5 hazards, and 6 scene labels:

| Photo | Reticle-crop top-1 | Score |
| --- | --- | --- |
| Portrait of a woman (1) | oak | 0.592 |
| Portrait of a woman (2) | oak | 0.516 |
| Asphalt road (1) | oak | 0.540 |
| Asphalt road (2) | oak | 0.555 |
| Car | fern | 0.508 |
| Red oak fixture (real plant) | oak | 0.572 |

- 5 of 22 non-plants came out as plant targets on the reticle crop, and 7 of 22 on the full frame.
- A portrait scored higher for oak than a real oak did, so no score floor can separate them.
- Scene labels misfired too: a child playing scored "screen", a computer monitor "person", and a laptop "lawn".

**TinyCLIP gates plants cleanly.** Scores are cosine similarity times TinyCLIP's learned scale (exp(logit_scale) = 50.0 for ViT-8M/16), softmaxed; a frame counts as a plant when the plant labels' combined share is over 0.5.

| Model | Image-side size | Plants kept | Non-plants passed |
| --- | --- | --- | --- |
| TinyCLIP ViT-8M/16 | 8.28M parameters, about 33 MB fp32 | 16 / 16 | 1 / 22 (a mowed lawn, 0.65, which is grass) |
| TinyCLIP ViT-39M/16 | 38M parameters | 16 / 16 | 1 / 22 (the same lawn, 0.57) |
| TinyCLIP ViT-40M/32 | 39M parameters | 16 / 16 | 2 / 22 (the lawn, 0.62; a smartphone display, 0.51) |

TinyCLIP ViT-8M/16 is the plant gate. Its phone-vs-laptop parity is still to be measured (S06).

## Camera

The back camera (ID 0, 6.3 mm) reports focus distance with calibration `APPROXIMATE`. The "walk closer" threshold still has to be set on the phone (S09).

## Still unmeasured

- Level-2 hint latency (S05)
- A 20-minute live-camera heat run (S05)
- TinyCLIP parity on the phone (S06)
- The full Gemma download, resume, and low-storage handling (S07)
