"""Check the exported plant_gate.onnx against every Day-1 TinyCLIP ViT-8M reticle score.

Run from the repo root after make assets:

    uv run --project pipeline --group reference python -I docs/results/day-1/check_plant_gate_export.py
"""

import csv, json, sys
from collections import defaultdict
from pathlib import Path
import numpy as np, onnxruntime as ort
from PIL import Image
A = Path("app/generated/assets")
gate = json.loads((A / "plant_gate.json").read_text())
V = np.array([l["vector"] for l in gate["labels"]]); P = np.array([l["plant"] for l in gate["labels"]]); s = gate["logit_scale"]
sess = ort.InferenceSession(str(A / "plant_gate.onnx"))
day1 = defaultdict(float)
for r in csv.DictReader(open("docs/results/day-1/tinyclip_scores.csv")):
    if r["model"].endswith("ViT-8M-16-Text-3M-YFCC15M") and r["region"] == "reticle" and r["kind"] == "plant":
        day1[(r["set"], r["photo"])] += float(r["probability"])
rows = list(csv.DictReader(open("docs/results/day-1-photos.tsv"), delimiter="\t"))
diffs, flips, n = [], 0, 0
for r in rows:
    key = (r["set"], r["file"])
    if key not in day1: continue
    p = Path(".models/day1-photos") / r["file"] if r["set"] != "fixture" else Path("app/src/androidTest/assets/reference/fixture.png")
    img = Image.open(p).convert("RGB"); side = int(min(img.size) * 0.6)
    l, t = (img.width - side) // 2, (img.height - side) // 2
    x = (np.asarray(img.crop((l, t, l + side, t + side)).resize((224, 224), Image.Resampling.BICUBIC), dtype=np.float32) / 255).transpose(2, 0, 1)[None]
    e = sess.run(None, {"image": x})[0][0]
    z = s * (V @ e); q = np.exp(z - z.max()); q /= q.sum(); share = q[P].sum()
    diffs.append(abs(share - day1[key])); flips += (share > 0.5) != (day1[key] > 0.5); n += 1
print(f"photos {n}, max |share diff| {max(diffs):.2e}, verdict flips {flips}")
