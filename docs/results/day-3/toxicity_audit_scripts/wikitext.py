"""Raw wikitext at the pinned revid for every row the Wikipedia sentence flagged, plus the local toxic rows."""
import json, sys, time, urllib.parse
from pathlib import Path
from wild_find_pipeline import toxicity as T
D = Path.cwd() / ".models/toxicity-audit"
tox = json.loads((D / "toxicity.json").read_text())["species"]
local = {r["name"] for r in json.loads((D / "local_toxic.json").read_text())}
want = {n: s["revid"] for n, s in tox.items() if s["revid"] and (s["evidence"].startswith("wikipedia") or n in local)}
path = D / "wikitext.json"
have = json.loads(path.read_text()) if path.exists() else {}
need = [(n, r) for n, r in want.items() if n not in have]
for s in range(0, len(need), 50):
    b = need[s:s + 50]
    params = {"action": "query", "prop": "revisions", "rvprop": "ids|content", "rvslots": "main",
              "revids": "|".join(str(v) for _, v in b), "format": "json", "formatversion": 2}
    extra = {}
    byrev = {}
    while True:
        reply = T.get(f"{T.WIKIPEDIA}?{urllib.parse.urlencode({**params, **extra})}")
        for p in reply["query"]["pages"]:
            for rv in p.get("revisions", []):
                if "slots" in rv:
                    byrev[rv["revid"]] = rv["slots"]["main"]["content"]
        if "continue" not in reply:
            break
        extra = reply["continue"]; time.sleep(1)
    for n, r in b:
        if r in byrev:
            have[n] = byrev[r]
    print(f"{s + len(b)}/{len(need)} missing={sum(1 for n, _ in b if n not in have)}", file=sys.stderr)
    time.sleep(1)
path.write_text(json.dumps(have))
print(len(have), "of", len(want))
