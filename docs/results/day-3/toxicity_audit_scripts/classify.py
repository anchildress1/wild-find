"""Hand classification of why each West Georgia local toxic flag fired; writes toxicity_audit.csv rows."""
import csv, json, sys
from pathlib import Path
ROOT = Path.cwd(); D = ROOT / ".models/toxicity-audit"
S = json.loads((D / "sentences.json").read_text())
usda = json.loads((D / "usda_with_synonyms.json").read_text())
prop = json.loads((D / "flags_proposed.json").read_text())

# Species whose every keyword sentence (or Toxicity-section body) is about animals only: livestock, pets, wildlife.
ANIMALS = {
    "Acer negundo": "Toxicity section: seeds' hypoglycin A causes seasonal pasture myopathy in horses",
    "Acer rubrum": "leaves extremely toxic to horses",
    "Perilla frutescens": "toxic to cattle, ruminants, horses",
    "Glechoma hederacea": "toxic to livestock/horses, rodents; borax toxic to ants",
    "Rudbeckia laciniata": "toxic to animals/livestock (horses, sheep, pigs)",
    "Onoclea sensibilis": "equine poisoning; human toxicity not defined",
    "Senna obtusifolia": "toxic to cattle, sheep, goats",
    "Prunus serotina": "wilted leaves poison livestock (also USDA severe)",
    "Quercus marilandica": "tannic acid poisoning in cattle",
    "Quercus stellata": "toxic to cattle (also USDA moderate)",
    "Helenium autumnale": "poisonous to ruminants (also USDA severe)",
    "Saururus cernuus": "toxic to livestock if overeaten",
    "Crotalaria spectabilis": "toxic to livestock (also USDA moderate, stub)",
    "Panicum virgatum": "toxicity in horses, sheep, goats; ethanol-fuel toxicity potential",
    "Achillea millefolium": "ASPCA: toxic to dogs, cats, horses; hemlock look-alike",
    "Rumex obtusifolius": "seeds toxic to chickens; cattle and horses; 'not generally considered poisonous'",
    "Asclepias verticillata": "toxic to livestock",
    "Osmundastrum cinnamomeum": "toxic to most herbivores; accumulated lanthanum toxic to most mammals",
    "Hypochaeris radicata": "Toxicity section: stringhalt in horses; otherwise non-poisonous",
    "Cynodon dactylon": "Toxicity section: Tifton 85 hybrid cyanide, livestock deaths",
    "Oxypolis rigidior": "poisonous to some mammals incl. cattle; disputed",
    "Oxalis stricta": "common name 'sheep poison'",
}
# Species whose every keyword sentence uses the word in a sense that claims no harm from the plant to people or animals.
IRRELEVANT = {
    "Juglans nigra": ("allelopathy", "walnut toxicity to other plants; juglone poisoning of plants"),
    "Verbascum thapsus": ("fish", "seed compounds toxic to fish (piscicide)"),
    "Cyperus esculentus": ("fish", "toxic to carp; fish death not tiger nut poisoning"),
    "Cichorium intybus": ("parasites", "toxic to internal parasites"),
    "Berberis bealei": ("etiology", "treats food poisoning"),
    "Lespedeza capitata": ("etiology", "antidote for poison"),
    "Myriophyllum aquaticum": ("herbicide", "absorption of the poison (herbicide)"),
    "Pistia stratiotes": ("pollution", "herbicide effects; accumulates toxic heavy metals"),
    "Ceratophyllum demersum": ("lab", "toxicity experiments in plants"),
    "Clitoria ternatea": ("insects", "extract's toxic effects on insects"),
    "Vernonia noveboracensis": ("negation", "'nor is it toxic'"),
    "Rubus phoenicolasius": ("negation", "'no poisonous look-a-likes'"),
    "Gleditsia triacanthos": ("other plant", "unlike the black locust, which is toxic"),
    "Chenopodium album": ("other plant", "poisonous black nightshade looks similar"),
    "Maianthemum racemosum": ("other plant", "resembles highly toxic Veratrum"),
    "Symphyotrichum novae-angliae": ("other plant", "poisonous skin state caused by Rhus (sumac)"),
    "Nothoscordum bivalve": ("name", "common name 'crow poison'"),
    "Collinsonia canadensis": ("no claim", "'toxic or otherwise' (also stub)"),
}
# Rule-unflagged but known irritant outside Wikipedia: never auto-unflag.
CAUTION = {"Pistia stratiotes": "Araceae; calcium oxalate raphides like taro/arrow arum, not in the article"}
OTHER = {"Melothria pendula": "'unknown toxicity' (also stub)"}

def snippet(v):
    if v["sentences"]:
        return " | ".join(v["sentences"])[:300]
    return v["evidence"]

rows, animals_only = [], []
for n, v in S.items():
    u = usda.get(n); stub = v["evidence"].startswith(("stub", "no article"))
    if n in OTHER:
        cls, sub, note = "other", "", OTHER[n]
    elif n in IRRELEVANT:
        cls, (sub, note) = "irrelevant", IRRELEVANT[n]
    elif n in ANIMALS:
        cls, sub, note = "animals-only", "", ANIMALS[n]
    elif v["sentences"]:
        cls, sub, note = "human", "", ""
    elif u:
        cls, sub, note = "usda", "", ""
    elif v["evidence"] == "no article":
        cls, sub, note = "no-article", "", ""
    else:
        cls, sub, note = "stub", "", ""
    new = prop[n]["new"] if n in prop else True
    if cls in ("human", "usda", "stub", "no-article", "other"):
        rec = "keep"
    elif cls == "animals-only":
        rec = "keep (usda/stub)" if (u or stub) else "user-decides"
        if not (u or stub):
            animals_only.append(n)
    else:
        rec = "keep (stub)" if stub else ("unflag" if not new else "keep (rule can't separate)")
        if n in CAUTION:
            rec, note = "user-decides", f"{note}; CAUTION: {CAUTION[n]}"
    rows.append({"species": n, "common": v["common"], "sightings": v["count"], "reason_class": cls, "sense": sub,
                 "usda": u or "", "stub": stub, "article_chars": v["len"] or 0, "proposed_rule_flag": new,
                 "recommendation": rec, "note": note, "evidence": snippet(v)})
missing = (set(ANIMALS) | set(IRRELEVANT) | set(OTHER)) - set(S)
assert not missing, missing
with (ROOT / "docs/results/day-3/toxicity_audit.csv").open("w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
(D / "animals_only.json").write_text(json.dumps(sorted(animals_only)))
from collections import Counter
print(Counter(r["reason_class"] for r in rows))
print(Counter(r["recommendation"] for r in rows))
print("animals-only unflag set:", sorted(animals_only))
print("usda present in any class:", sum(1 for r in rows if r["usda"]), "stub any:", sum(1 for r in rows if r["stub"]))
fl = max(3, 0.005 * 11101)
