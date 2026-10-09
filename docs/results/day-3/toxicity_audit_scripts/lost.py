"""Class of the best blocker for each positive lost at 0.048 under current flags, and original-23 pass per variant."""
import csv, json
from collections import Counter
from pathlib import Path
import numpy as np
ROOT = Path.cwd(); D = ROOT / ".models/toxicity-audit"; DAY2 = ROOT / "docs/results/day-2"
audit = {r["species"]: r for r in csv.DictReader((ROOT / "docs/results/day-3/toxicity_audit.csv").open())}
labels = json.loads((D / "species_labels.json").read_text()); names = [e["scientific"] for e in labels]
genus = [e["genus"] for e in labels]; index = {n: i for i, n in enumerate(names)}
table = np.load(D / "species_table.npy")
tox = {n: s["toxic"] for n, s in json.loads((D / "toxicity.json").read_text())["species"].items()}
photos = list(csv.DictReader((DAY2 / "toxic_block_photos.csv").open()))
saved = dict(np.load(ROOT / ".models/toxic-block-photos/reticle_embeddings.npz"))
scores = np.stack([saved[p["sha256"]] for p in photos]) @ table.T
eligible = sorted({p["species"] for p in photos if p["toxic"] == "False"})
pool = np.array([index[n] for n in eligible] + [index[n] for n in audit])
is_toxic = np.array([tox.get(n, True) for n in names])
c = Counter(); lines = []
for i, p in enumerate(photos):
    if p["toxic"] != "False": continue
    s = scores[i]; order = pool[np.argsort(-s[pool])]; top = order[0]
    bl = order[is_toxic[order]][0]; gap = s[top] - s[bl]
    ok = not is_toxic[top] and gap >= 0.048 and genus[top] == genus[index[p["species"]]]
    if ok: continue
    if is_toxic[top]:
        kind = "blocker top-1"
    elif genus[top] != genus[index[p["species"]]]:
        kind = "wrong genus"
    else:
        kind = "margin < 0.048"
    cls = audit[names[bl]]["reason_class"] + ("/" + audit[names[bl]]["recommendation"])
    c[(kind, audit[names[bl]]["reason_class"])] += 1
    lines.append(f"{p['photo']}: {kind}; top1 {names[top]}; best blocker {names[bl]} [{cls}] gap {gap:.4f}")
print("\n".join(lines)); print(c)
