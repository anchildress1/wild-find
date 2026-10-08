"""Old vs proposed flag on every fetched row; writes flags_proposed.json for the simulation."""
import importlib.util, json, sys
from pathlib import Path
from wild_find_pipeline import toxicity as OLD
D = Path.cwd() / ".models/toxicity-audit"
spec = importlib.util.spec_from_file_location("toxicity_proposed", D / "scripts/toxicity_proposed.py")
NEW = importlib.util.module_from_spec(spec); spec.loader.exec_module(NEW)
tox = json.loads((D / "toxicity.json").read_text())["species"]
wt = json.loads((D / "wikitext.json").read_text())
usda = json.loads((D / "usda_with_synonyms.json").read_text())
out, mism = {}, 0
for n, w in sorted(wt.items()):
    o = OLD.flag(OLD.plain(w), usda.get(n))
    t = NEW.plain(w)
    nw = NEW.flag(t, usda.get(n))
    if o[1] != tox[n]["evidence"]:
        mism += 1; print("REPRO MISMATCH", n, o[1][:60], "|", tox[n]["evidence"][:60])
    out[n] = {"old": o[0], "new": nw[0], "new_evidence": nw[1]}
    if o[0] != nw[0]:
        print(f"FLIP {n}: {o[1][:150]}")
(D / "flags_proposed.json").write_text(json.dumps(out, indent=1))
print("repro mismatches", mism, "| rows", len(out), "| flips", sum(v["old"] != v["new"] for v in out.values()))
changed = [n for n, v in out.items() if v["old"] and v["new"] and tox[n]["evidence"] != v["new_evidence"]]
print("still flagged, evidence changed:", len(changed))
for n in changed: print(f"  {n}: {out[n]['new_evidence'][:140]}")
