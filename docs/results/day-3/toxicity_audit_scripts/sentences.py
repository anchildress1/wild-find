import json
from pathlib import Path
from wild_find_pipeline import toxicity as T
D = Path.cwd() / ".models/toxicity-audit"
local = json.loads((D / "local_toxic.json").read_text())
arts = json.loads((D / "articles.json").read_text())
usda = json.loads((D / "usda_local.json").read_text())
tox = json.loads((D / "toxicity.json").read_text())["species"]
mismatch = 0
out = {}
for r in local:
    n = r["name"]; a = arts.get(n)
    text = a["text"] if a else None
    flg, ev = T.flag(text, usda[n]["level"])
    if ev != tox[n]["evidence"]:
        mismatch += 1; print("MISMATCH", n, "|", ev[:80], "|", tox[n]["evidence"][:80])
    sents = []
    if text:
        for s in T.SENTENCE.findall(text):
            if T.TOXIC.search(T.NOT_TOXIC.sub("", T.OTHER_PLANTS.sub("", s))):
                sents.append(" ".join(s.split()))
    out[n] = {"count": r["count"], "common": r["common"], "evidence": tox[n]["evidence"], "usda": usda[n]["level"],
              "len": len(text) if text else None, "sentences": sents}
(D / "sentences.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))
print("mismatch", mismatch)
from collections import Counter
print(Counter(v["evidence"].split(":")[0] for v in out.values()))
print("usda-any", sum(1 for v in out.values() if v["usda"]))
