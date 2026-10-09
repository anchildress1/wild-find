"""Fetch inputs for the toxicity audit: local toxic list, pinned-revision article text, USDA via GBIF synonyms."""
import csv, json, sys, time, urllib.parse
from pathlib import Path
from wild_find_pipeline import toxicity as T

ROOT = Path.cwd()
D = ROOT / ".models/toxicity-audit"
labels = json.loads((D / "species_labels.json").read_text())
index = {e["scientific"]: i for i, e in enumerate(labels)}
tox = json.loads((D / "toxicity.json").read_text())["species"]
rows = [r for r in csv.DictReader((ROOT / "docs/results/day-2/threshold_regions.csv").open()) if r["place"] == "west-georgia-us"]
matched = [r for r in rows if r["name"] in index]
local = [r for r in sorted(matched, key=lambda r: -int(r["count"])) if tox.get(r["name"], {"toxic": True})["toxic"]]
print(len(local), "local toxic", file=sys.stderr)
(D / "local_toxic.json").write_text(json.dumps([{"name": r["name"], "count": int(r["count"]), "common": r["common"]} for r in local], indent=1))

# Article text at the stored revision.
art_path = D / "articles.json"
arts = json.loads(art_path.read_text()) if art_path.exists() else {}
need = [(r["name"], tox[r["name"]]["revid"]) for r in local if tox[r["name"]]["revid"] and r["name"] not in arts]
for s in range(0, len(need), 50):
    b = need[s:s+50]
    params = {"action": "query", "prop": "revisions", "rvprop": "ids|content", "rvslots": "main",
              "revids": "|".join(str(v) for _, v in b), "format": "json", "formatversion": 2}
    reply = T.get(f"{T.WIKIPEDIA}?{urllib.parse.urlencode(params)}")
    byrev = {}
    for p in reply["query"]["pages"]:
        for rv in p.get("revisions", []):
            byrev[rv["revid"]] = rv["slots"]["main"]["content"]
    for name, rev in b:
        if rev in byrev:
            arts[name] = {"revid": rev, "text": T.plain(byrev[rev])}
    print(f"wiki {s+len(b)}/{len(need)}", file=sys.stderr)
    time.sleep(1)
art_path.write_text(json.dumps(arts))

# USDA: raw ratings plus GBIF accepted name and synonyms of each local species.
raw = T.usda_ratings((ROOT / ".models/usda_plant_traits.tar.gz").read_bytes())
u_path = D / "usda_local.json"
usda = json.loads(u_path.read_text()) if u_path.exists() else {}
for r in local:
    n = r["name"]
    if n in usda:
        continue
    names = {n}
    m = T.get(f"{T.GBIF}/species/match?" + urllib.parse.urlencode({"name": n, "kingdom": "Plantae", "strict": "true"}))
    key = m.get("acceptedUsageKey") or m.get("usageKey")
    if key:
        acc = T.get(f"{T.GBIF}/species/{key}")
        syn = T.get(f"{T.GBIF}/species/{key}/synonyms?limit=1000")["results"]
        for u in [acc, *syn]:
            b2 = " ".join(u.get("canonicalName", "").split()[:2])
            if b2.count(" ") == 1:
                names.add(b2)
    hits = sorted({raw[x] for x in names if x in raw})
    usda[n] = {"level": ("severe" if "severe" in hits else hits[0]) if hits else None,
               "via": sorted(x for x in names if x in raw)}
    time.sleep(0.2)
u_path.write_text(json.dumps(usda, indent=1))
print("usda hits", sum(1 for v in usda.values() if v["level"]), file=sys.stderr)
