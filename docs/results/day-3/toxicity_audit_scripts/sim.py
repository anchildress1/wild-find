"""Re-run the Day-2 toxic_block C-margin measurement under three flag sets: current, proposed rule, proposed + animals-only.

Reuses the Day-2 photo manifest, photo cache, and reticle-embedding cache; writes only under .models/toxicity-audit.
"""
import csv, json, sys
from pathlib import Path
import numpy as np

ROOT = Path.cwd()
D = ROOT / ".models/toxicity-audit"
DAY2 = ROOT / "docs/results/day-2"
PHOTOS = ROOT / ".models/toxic-block-photos"
SHARE, MIN_SIGHTINGS, MARGIN = 0.005, 3, 0.048

labels = json.loads((D / "species_labels.json").read_text())
table = np.load(D / "species_table.npy")
index = {e["scientific"]: i for i, e in enumerate(labels)}
names = [e["scientific"] for e in labels]
genus = [e["genus"] for e in labels]
is_hazard = np.array([e["hazard"] for e in labels])
base = {n: s["toxic"] for n, s in json.loads((D / "toxicity.json").read_text())["species"].items()}
proposed = json.loads((D / "flags_proposed.json").read_text())
animals_only = json.loads((D / "animals_only.json").read_text())
rows = [r for r in csv.DictReader((DAY2 / "threshold_regions.csv").open()) if r["place"] == "west-georgia-us"]
floor = max(MIN_SIGHTINGS, SHARE * sum(int(r["count"]) for r in rows))
matched = [r for r in rows if r["name"] in index]
photos = list(csv.DictReader((DAY2 / "toxic_block_photos.csv").open()))
saved = dict(np.load(PHOTOS / "reticle_embeddings.npz"))
scores = np.stack([saved[p["sha256"]] for p in photos]) @ table.T
warns = [bool(is_hazard[np.argsort(-s)[:5]].any()) for s in scores]

variants = {
    "current": dict(base),
    "proposed": base | {n: v["new"] for n, v in proposed.items()},
}
variants["proposed+animals"] = variants["proposed"] | {n: False for n in animals_only}

def judge(s, pool, toxic, m):
    order = pool[np.argsort(-s[pool])]
    top = order[0]
    bl = order[toxic[order]]
    gap = s[top] - s[bl[0]] if len(bl) else np.inf
    return top, gap, (not toxic[top]) and gap >= m

out_rows = []
variants_eligible = {}
for vname, tox in variants.items():
    eligible = [r["name"] for r in matched if int(r["count"]) >= floor and len(r["common"].split()) <= 3
                and not labels[index[r["name"]]]["hazard"] and not tox.get(r["name"], True)]
    toxic_all = [r["name"] for r in sorted(matched, key=lambda r: -int(r["count"])) if tox.get(r["name"], True)]
    is_toxic = np.array([tox.get(n, True) for n in names])
    targets = {}
    for t in eligible:
        targets.setdefault(genus[index[t]], []).append(t)
    pool = np.array([index[n] for n in eligible + toxic_all])
    have = {p["species"] for p in photos}
    missing = [n for n in eligible if n not in have]
    pos = [i for i, p in enumerate(photos) if p["species"] in eligible]
    neg = [i for i, p in enumerate(photos) if tox.get(p["species"], True)]
    dropped = sorted({p["species"] for p in photos if p["toxic"] == "True" and not tox.get(p["species"], True)})
    js = {i: judge(scores[i], pool, is_toxic, 0.0) for i in range(len(photos))}
    res = {}
    for tag, skip in (("raw", [False] * len(photos)), ("after row 1", warns)):
        worst = max((js[i][1] for i in neg if not skip[i] and genus[js[i][0]] in targets), default=0.0)
        mstar = float(np.nextafter(np.float32(worst), np.float32(1)))
        for mlabel, m in ((f"{MARGIN:.3f}", MARGIN), ("m*", mstar)):
            won = bad = 0
            for i in pos:
                top, gap, ok = judge(scores[i], pool, is_toxic, m)
                won += ok and photos[i]["species"] in targets.get(genus[top], []) and not skip[i]
            fp = []
            for i in neg:
                top, gap, ok = judge(scores[i], pool, is_toxic, m)
                if ok and genus[top] in targets and not skip[i]:
                    bad += 1; fp.append(f"{photos[i]['photo']}->{names[top]}")
            res[(tag, mlabel)] = (m, won, len(pos), bad, len(neg), fp)
            out_rows.append({"variant": vname, "hazard_step": tag, "margin": f"{m:.6f}", "margin_label": mlabel,
                             "pool_rows": len(pool), "eligible": len(eligible), "target_pass": won,
                             "positives": len(pos), "toxic_false_pass": bad, "negatives": len(neg),
                             "false_passes": ";".join(fp)})
    print(f"== {vname}: {len(eligible)} eligible, {len(toxic_all)} local toxic (pool {len(pool)}); "
          f"new eligible vs current: {sorted(set(eligible) - set(variants_eligible.get('current', eligible)))}; "
          f"photos missing for eligible: {missing}; negatives dropped (now unflagged): {dropped}")
    variants_eligible[vname] = eligible
    for (tag, ml), (m, won, npos, bad, nneg, fp) in res.items():
        print(f"  {tag:11s} margin {ml:5s} = {m:.4f}: target pass {won}/{npos} ({won / npos:.0%}), "
              f"toxic false pass {bad}/{nneg}" + (f"  {fp}" if fp else ""))
    lost = [photos[i]["photo"] for i in pos if not (lambda r: r[2] and photos[i]["species"] in targets.get(genus[r[0]], []))(judge(scores[i], pool, is_toxic, MARGIN))]
    print(f"  lost at {MARGIN}: {len(lost)}")
    for i in pos:
        if photos[i]["species"] not in variants_eligible["current"]:
            top, gap, ok = judge(scores[i], pool, is_toxic, MARGIN)
            print(f"    new target photo {photos[i]['photo']}: top1 {names[top]} gap {gap:.4f} pass {ok and photos[i]['species'] in targets.get(genus[top], [])}")
with (D / "sim.csv").open("w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(out_rows[0])); w.writeheader(); w.writerows(out_rows)
