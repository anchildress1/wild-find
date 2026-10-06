# 📏 Day-1 results (October 6, 2026)

Measured results from the Day-1 go/no-go gate. Every number here was observed, not estimated, and every laptop number below is derived from the raw data in [day-1/](day-1/).

## Raw data

| File | Contents |
| --- | --- |
| [day-1-photos.tsv](day-1-photos.tsv) | All 307 photos: set, file, license (CC0 or public domain), source page, photo URL, SHA-256 (the experiment script rejects any photo whose bytes differ) |
| [day-1/bioclip_scores.csv](day-1/bioclip_scores.csv) | BioCLIP Mobile score for every photo, region (full frame, center 60% reticle crop), and label (6 targets, 5 hazards, 6 scenes) |
| [day-1/species_scores.csv](day-1/species_scores.csv) | Every photo and region against BioCLIP's species table (4,271 species plus 1 added): top-1, best hazard and its rank, best non-hazard, top 5 |
| [day-1/tinyclip_scores.csv](day-1/tinyclip_scores.csv) | TinyCLIP plant-gate probabilities for 3 models, every photo, both regions, all 17 gate labels |
| [day-1/label_format.csv](day-1/label_format.csv) | Label-format experiment: 4 prompt formats, mobile vs teacher |
| [day-1/species_table_check.json](day-1/species_table_check.json) | Species-table prompt-format check and appended rows |
| [day-1/run_experiments.py](day-1/run_experiments.py) | Regenerates every CSV above from the photo manifest |
| [day-1/summarize.py](day-1/summarize.py) and [summary.txt](day-1/summary.txt) | Derives every table on this page from the CSVs |
| [day-1/device-logs.md](day-1/device-logs.md) | Raw logcat lines from every on-device run |

Photo sets: 176 CC0 iNaturalist plant photos (10 each of oak, pine, maple, sweetgum, fern, clover, dandelion, moss, magnolia, violet, honeysuckle, and the 5 hazards, plus 16 earlier ones), 52 grass photos, 63 non-plant photos (Wikimedia CC0 or public domain: people, pets, vehicles, toys, screens, rocks, soil, walls), and 14 mixed scenes where real vegetation fills much of the frame. Non-photographs (paintings, a floor plan, a sketch, an illustration, logos, diagrams) and duplicate photos were removed by hand; photographs of sculptures stay, since a kid can point the camera at a statue.

## Setup

| Item | Value |
| --- | --- |
| Phone | Samsung Galaxy S24 Ultra (SM-S928U), Snapdragon 8 Gen 3 (SM8650), 11 GB RAM, Android 16 (SDK 36) |
| On-device runtimes | ONNX Runtime Android 1.30.0 (CPU); LiteRT-LM Android 0.17.1 (GPU text and vision backends) |
| Laptop | Apple M4 Max, macOS 26.5.2, Python 3.13.14 (uv 0.12.13), ONNX Runtime 1.30.0, open_clip 3.3.0, torch 2.14.1, Transformers 5.19.0, Pillow 12.3.0, NumPy 2.5.3 |
| Gemma | `gemma-4-E2B-it.litertlm`, litert-community @ b3ca0d2 |
| BioCLIP Mobile | crazedcodernate/bioclip-2.5-mobile-fastvit @ 29b474e (fp16 and fp32 files) |
| BioCLIP teacher | imageomics/bioclip-2.5-vith14 @ 6e3d04e |
| TinyCLIP | wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M @ a2a8c6e; compared against ViT-39M-16-Text-19M-YFCC15M @ 07a4b0b and ViT-40M-32-Text-19M-LAION400M @ 95ec819 |

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

Photo: a CC0 white oak, [iNaturalist 211670015](https://www.inaturalist.org/observations/211670015). Scores are cosine similarity.

| Prompt format | Model | Top-1 | Top three |
| --- | --- | --- | --- |
| Common name ("a photo of oak.") | Mobile | **poison oak** | poison oak 0.456, poison ivy 0.399, oak 0.366 |
| Common name | Teacher | **poison oak** | poison oak 0.388, poison ivy 0.352, oak 0.329 |
| Scientific ("a photo of Quercus.") | Mobile | oak | oak 0.478, poison oak 0.456, poison ivy 0.392 |
| Scientific | Teacher | oak | oak 0.414, poison ivy 0.345, poison oak 0.331 |
| Full taxonomic string | Mobile | oak | oak 0.476, poison oak 0.457, poison ivy 0.395 |
| Full taxonomic string | Teacher | oak | oak 0.412, poison ivy 0.347, poison oak 0.332 |
| Taxonomic plus common name | Mobile | **poison oak** | poison oak 0.456, oak 0.405, poison ivy 0.397 |
| Taxonomic plus common name | Teacher | oak | oak 0.378, poison ivy 0.348, poison oak 0.344 |

- Mobile vs teacher image embedding on that photo: cosine 0.810.
- Plant labels embed by scientific name.

## BioCLIP Mobile can't reject non-plants

Labels: 5 targets, 5 hazards, 6 scene labels. On 63 non-plant photos, a plant target was top-1 on **16 of 63 reticle crops** and **20 of 63 full frames**. Examples on the reticle crop: sneakers scored oak 0.605, a television pine 0.582, an asphalt road oak 0.555, an 1896 portrait photograph oak 0.516. The real red oak fixture scored oak 0.572, so no score floor separates them.

## TinyCLIP plant gate

A frame is a plant when the plant labels' combined softmax share is over 0.5 (scores are cosine times the model's learned scale, 50.0 for ViT-8M/16).

| Model | Region | Plants (176) | Grass (52) | Non-plants (63) | Mixed scenes (14) |
| --- | --- | --- | --- | --- | --- |
| **ViT-8M/16** (chosen) | Full frame | 176 | 52 | 2 | 4 |
| **ViT-8M/16** | Reticle | 175 (one pine missed) | 51 | 3 | 3 |
| ViT-39M/16 | Full frame | 176 | 52 | 4 | 4 |
| ViT-39M/16 | Reticle | 175 | 52 | 4 | 4 |
| ViT-40M/32 | Full frame | 175 | 52 | 2 | 5 |
| ViT-40M/32 | Reticle | 176 | 52 | 3 | 5 |

The table counts photos that pass as plants. Mixed scenes passing is correct when vegetation fills the frame. These are laptop numbers; the phone matches them (below).

## Hazard warnings

### Against the menu labels only (old rule, dropped)

Labels: grass, oak, fern, clover, pine, dandelion, and the 5 hazards. A photo warns when a hazard is top-1 in either region.

| Group | Photos | Warn | Group | Photos | Warn |
| --- | --- | --- | --- | --- | --- |
| Magnolia (not on the list) | 10 | **10** | Poison ivy | 12 | 11 |
| Honeysuckle (not on the list) | 10 | **9** | Poison oak | 10 | 10 |
| Maple (not on the list) | 10 | **7** | Poison sumac | 10 | 10 |
| Sweetgum (not on the list) | 10 | **6** | Pokeweed | 10 | 10 |
| Violet (not on the list) | 10 | **6** | Horsenettle | 10 | 10 |
| Grass (52 plus 2 earlier) | 54 | 9 | Clover | 12 | 2 |
| Fern | 12 | 3 | Pine | 12 | 2 |
| Oak, moss | 12 each | 1 each | Dandelion | 12 | 0 |
| Non-plants | 63 | 0 | Mixed scenes | 14 | 0 |

Plants missing from the list get forced onto the nearest label, often a hazard. A hazard-over-plant margin doesn't fix it: at 0.10, honeysuckle still warns on 5 of 10, while one poison ivy photo falls 0.093 below the best plant.

### Against BioCLIP's species table (new rule)

BioCLIP Mobile ships a table of 4,271 plant species (MIT); Atlantic poison oak (*Toxicodendron pubescens*) was missing, so one row was added in our prompt format. That format lines up with the table: our embedding of "a photo of *Toxicodendron radicans*." scores 0.975 against the table's own row.

| Rule (any region the plant gate passes) | Hazards caught (52) | Safe photos warned (253) |
| --- | --- | --- |
| Hazard species top-1 | 43 (83%) | 1 (0.4%) |
| Hazard species in the top 3 | 45 (87%) | 1 (0.4%) |
| **Hazard species in the top 5** (chosen) | **48 (92%)** | **1 (0.4%)** |
| Hazard species in the top 10 | 48 (92%) | 2 (0.8%) |

- Under top-1, every safe group warns 0 times except grass, 1 of 54.
- Hazards caught under top-1: poison ivy 10 of 12, poison oak 9 of 10, pokeweed 9 of 10, horsenettle 8 of 10, poison sumac 7 of 10.

## Grass tutorial

Grass is a kid's first target. On 52 CC0 grass photos (29 near the region, many cane and wetland grasses, plus 23 common Georgia lawn grasses):

| Rule, on the reticle crop | Grass (52) | Non-grass plants (124) | Hazard plants (52) | Non-plants (63) |
| --- | --- | --- | --- | --- |
| BioCLIP grass top-1 | 45 | 11 | 0 | 28 |
| BioCLIP grass in top 3 | 50 | 44 | 3 | 54 |
| **TinyCLIP plant gate and BioCLIP grass in top 3** (chosen) | **49** | 43 | 3 | 3 |

- Labels: the fixed 11-label tutorial set (grass, oak, fern, clover, pine, dandelion, and the 5 hazards).
- The one grass photo BioCLIP passes but the gate rejects is a lawn (`lawn_250390361.jpg`): plant share 0.387 on the reticle crop, 0.784 on the full frame.
- Against the menu labels, 9 of 54 grass photos would have shown a hazard warning, so the tutorial skips the hazard check. Against the species table, 1 of 54 grass photos still would.

## Camera

The back camera (ID 0, 6.3 mm) reports focus calibration `APPROXIMATE` and no minimum focus distance or hyperfocal distance. Live readings came from the debug focus probe (`make focus-probe`, CameraX 1.6.2): back camera, preview only, one log line every 250 ms. Subject: a soda can on a desk, indoors; the USB cable limited range to about 1 m. One tap to focus per shot; values below are the steady reading while autofocus reported `FOCUSED_LOCKED`.

| Shot | Run 1 | Run 2 portrait | Run 2 landscape | Run 3 portrait |
| --- | --- | --- | --- | --- |
| Far | 1.80 | 1.32 | 1.28 | 1.00 |
| Closer (can is the main thing) | 2.53 | 2.09 | 2.53 | 1.99 |
| Closest, whole can in frame | 3.97 | 6.37 | 5.38 | not tapped |
| Too close | not taken | 10.0 | 10.0 | 8.20 |

- Run 1's labels come from the tester's description afterward; runs 2 and 3 were taken in the order shown.
- Far is 1.80 or less and closer is 1.99 or more in every run. Rule chosen: closer at 2.0 or more.
- 10.0 is the lens's near limit; the image is blurry there.
- Unfocused frames (`PASSIVE_UNFOCUSED`, about a third of run 1) park the lens near 0.20 to 0.25 diopters whatever the subject distance, and the first frames read -1.0. Only focused or locked readings count.
- The phone's distance scale is off: about 3 ft (0.9 m) read 1.8 diopters (0.55 m).

**Zoom run** (run 4, standing still at about 1 m, pinch zoom from far-looking to full frame):

| Physical lens | Zoom | Diopters | Diopters × zoom |
| --- | --- | --- | --- |
| 5 (main) | 1.0 | 1.25 | 1.25 |
| 5 (main) | 2.7 | 0.80 to 1.25 | 2.2 to 3.4 |
| 6 | 3.8 | 0.78 | 3.0 |
| 7 | 10.0 | 1.13 | 11.3 |

- Zoom switches physical lenses; each still reports about the same distance (0.76 to 1.25), so diopters × zoom tracks how big the subject looks.
- Rule: diopters × zoom ≥ 2.0 while focused, else "walk closer".
- Raw logs: [focus-probe-run1.log](day-1/focus-probe-run1.log), [run 2](day-1/focus-probe-run2-can.log), [run 3](day-1/focus-probe-run3-soda.log), [run 4](day-1/focus-probe-run4-zoom.log). Not measured: plants, outdoors, beyond about 1 m (S52).

## Bundled plant gate and species table on the phone (S06, S08b)

`make assets` exports TinyCLIP ViT-8M/16's image encoder to `plant_gate.onnx` (fp32, 33,730,790 bytes, CLIP normalization baked in; torch 2.14.1 dynamo exporter, Transformers 5.19.0) and writes `plant_gate.json` (17 gate prompts, logit scale 50.0043). It also builds the species table: the pinned 4,271 rows plus *Toxicodendron pubescens*, 7 rows flagged as hazards (*Toxicodendron diversilobum*, *radicans*, *rydbergii*, *vernix*, *pubescens*, *Phytolacca americana*, *Solanum carolinense*). All five files load from the APK.

| Check | Result |
| --- | --- |
| Export vs the Day-1 measurement path, on all 307 Day-1 photos (reticle crop) | largest plant-share difference 0.0000024; 0 verdict flips ([check_plant_gate_export.py](day-1/check_plant_gate_export.py)) |
| Fixture plant share: laptop export, Day-1 path, phone | 0.986990, 0.986990, 0.986990 |
| Phone vs laptop embedding cosine | 0.9999999889 |
| Plant gate on the phone | load 220 ms, one embedding 40 ms |
| BioCLIP on the phone, loaded from the APK | load 353 ms (was 128 to 138 ms from a sideloaded file), one embedding 45 ms, cosine 0.9999999988 |

## Still unmeasured

- The full per-frame verify path on the phone: TinyCLIP twice plus BioCLIP up to twice (S05)
- Level-2 hint latency (S05)
- A 20-minute live-camera heat run (S05)
- The full Gemma download, resume, and low-storage handling (S07)
