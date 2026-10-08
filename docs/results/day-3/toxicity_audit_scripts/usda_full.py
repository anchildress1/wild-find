import json
from pathlib import Path
from wild_find_pipeline import toxicity as T
D = Path.cwd() / ".models/toxicity-audit"
raw = T.usda_ratings((Path.cwd() / ".models/usda_plant_traits.tar.gz").read_bytes())
(D / "usda_with_synonyms.json").write_text(json.dumps(T.with_synonyms(raw), indent=1, sort_keys=True))
print("done")
