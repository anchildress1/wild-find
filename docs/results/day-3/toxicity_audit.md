# Toxicity flag audit, West Georgia (2026-10-08)

Why each of the 279 toxic-flagged West Georgia local species is flagged, a rule change that drops flags where the toxicity word is about something else, and what that change does to the verify row 4 blocker measurement. Per-species detail is in `toxicity_audit.csv`; raw numbers and inputs are in `toxicity_audit.log`. Nothing here has been applied to `toxicity.py` or `toxicity.json`.

## Why the flags fired

Each species gets one primary class. Precedence runs human, then animals-only, then irrelevant, then USDA, then stub.

| Class | Species | Meaning | Recommendation |
| --- | --- | --- | --- |
| stub | 129 | Article under 1,500 characters, no toxicity sentence | keep |
| human | 102 | Sentence or Toxicity section says harm to people, children, eating or touching, or "all parts poisonous" | keep |
| animals-only | 22 | Every claim is about livestock, pets, or wildlife | 18 user-decides, 4 keep (USDA or stub also fires) |
| irrelevant | 18 | The word is used in a sense that doesn't apply (see below) | 8 unflag, 1 user-decides, 9 kept by the rule |
| usda | 4 | USDA moderate or severe is the only signal (*Quercus alba*, *Sorghum halepense*, *Artemisia vulgaris*, *Vinca minor*) | keep |
| no-article | 3 | No English article (includes the moss *Rhodobryum ontariense*) | keep |
| other | 1 | *Melothria pendula*, "unknown toxicity" (also a stub) | keep |

Irrelevant senses: allelopathy (*Juglans nigra*), toxic to fish (*Verbascum*, *Cyperus*), internal parasites (*Cichorium*), food poisoning or antidote (*Berberis bealei*, *Lespedeza capitata*), herbicide or heavy metals (*Myriophyllum aquaticum*, *Pistia stratiotes*), plant toxicity experiments (*Ceratophyllum*), insects (*Clitoria*), missed negations (*Vernonia noveboracensis* "nor is it toxic", *Rubus phoenicolasius* "no poisonous look-a-likes"), a different plant (*Gleditsia*, *Chenopodium album*, *Maianthemum racemosum*, *Symphyotrichum novae-angliae*), the common name "crow poison" (*Nothoscordum*), and no claim at all (*Collinsonia*).

Headings are not a safe thing to drop. Eight local species are flagged only because their article has a "Toxicity" heading. Five of those sections describe real harm to people: *Peltandra* (calcium oxalate), *Apios* (raw tubers), *Galium* (skin), *Rumex crispus* (oxalic acid), and *Panax* (adverse effects). The other three are about livestock and are counted as animals-only: *Acer negundo*, *Cynodon*, and *Hypochaeris*. The heading stays a trigger.

## Proposed rule change (not applied)

```diff
--- a/pipeline/src/wild_find_pipeline/toxicity.py
+++ b/pipeline/src/wild_find_pipeline/toxicity.py
@@
 # Words that say the opposite; removed too, so "non-toxic" or "not toxic" isn't read as a toxicity claim.
-NOT_TOXIC = re.compile(r"\b(?:non-?|not )(?:toxic|poisonous)\b", re.I)
+NOT_TOXIC = re.compile(r"\b(?:non-?|not |nor (?:is|are) (?:it|they) |no )(?:toxic|poisonous)\b", re.I)
+# Senses that claim no harm from eating or touching the plant: harm to fish or gut parasites, food poisoning, antidotes.
+OTHER_SENSE = re.compile(
+    r"\b(?:toxic|poisonous) to (?:fish|carp|internal parasites)\b|\bfood poisoning\b|\bantidotes? for poisons?\b", re.I
+)
+# Sentences about sprayed chemicals or pollution; their keyword is about the chemical, not the plant.
+CHEMICAL = re.compile(r"\b(?:herbicides?|heavy metals?|pollut\w*|fuels?)\b", re.I)
@@
 SKIP_SECTIONS = re.compile(r"^(?:references|notes|citations|sources|further reading|external links|see also)$", re.I)
+# Sections on harm to other plants, at any heading level.
+ALLELOPATHY = re.compile(r"^\s*allelopath", re.I)
@@ def plain(wikitext: str) -> str:
         if SKIP_SECTIONS.match(heading):
             code.remove(section)
+    for section in code.get_sections(matches=ALLELOPATHY.pattern, flags=re.I):
+        with contextlib.suppress(ValueError):
+            code.remove(section)
@@ def toxic_sentence(text: str) -> str | None:
     for sentence in SENTENCE.findall(text):
-        if TOXIC.search(NOT_TOXIC.sub("", OTHER_PLANTS.sub("", sentence))):
+        claim = OTHER_SENSE.sub("", NOT_TOXIC.sub("", OTHER_PLANTS.sub("", sentence)))
+        if not CHEMICAL.search(sentence) and TOXIC.search(claim):
             return " ".join(sentence.split())
```

- I replayed the rule against all 818 rows that have a Wikipedia-sentence flag or are local toxic species, using each row's pinned revid. The current rule reproduces every stored evidence string exactly (0 mismatches).
- It unflags 10 rows across the whole 4,272-row table: *Juglans nigra*, *Verbascum thapsus*, *Berberis bealei*, *Cichorium intybus*, *Lespedeza capitata*, *Myriophyllum aquaticum*, *Vernonia noveboracensis*, *Rubus phoenicolasius*, *Pistia stratiotes*, and *Nelumbo nucifera* (heavy metals in the rhizome; not a local species).
- No human-harm flag and no USDA flag is lost. Four rows stay flagged but now cite a different sentence: *Alocasia odora*, *Cyperus esculentus*, *Panicum virgatum*, *Parthenium hysterophorus*.
- The rule leaves alone flags that come from look-alike plants, common names, insects, and lab experiments. Covering them would take patterns that also match a toxic plant's own article (for example, "highly poisonous hemlock" in *Conium*).
- *CHEMICAL* deliberately leaves out "contaminated" and "pesticide". "Contaminated" sentences carry real claims (*Ailanthus* "water contaminated by the flowers", *Ageratina* "milk contaminated with the toxin"), and so do "pesticide" sentences such as *Melia*.

## Blocker measurement, before and after

This reruns `docs/results/day-2/toxic_block.py` C-margin with the same photos and embeddings. Positives are 3 CC0 photos per eligible species; unflagged species' cached Day-2 photos become positives, so nothing new was fetched. Negatives are the Day-2 negatives of species that stay flagged.

| Flag set | Eligible | Local blockers | Target pass at 0.048 | Original 23 at 0.048 | Toxic false pass at 0.048 | Smallest zero-false-pass margin |
| --- | --- | --- | --- | --- | --- | --- |
| Current | 23 | 279 | 34/69 (49%) | 34/69 | 0/180 | 0.0477 |
| Proposed rule | 24 (+*Juglans nigra*) | 270 | 35/72 (49%) | 34/69 | 0/171 | 0.0477 |
| Proposed + animals-only unflagged | 26 (+*Acer negundo*, *Acer rubrum*) | 252 | 37/78 (47%) | 35/69 | 0/150 | 0.0477 |

The numbers are the same with or without the row-1 hazard step.

- The rule fix leaves recall where it was. *Juglans* passes 1 of its 3 photos. Two *Rhus copallinum* photos that lost to *Juglans* as a blocker now land on *Juglans* as a wrong-genus target, so they still fail.
- Among the 35 lost positives, the best blocker is human-harm 14 times, a stub or missing article 8 times, animals-only 7 times, irrelevant 4 times, and *Melothria* 2 times. The losses come from BioCLIP Mobile confusing look-alikes. Flag quality is a small part of it.
- The zero-false-pass margin is still 0.0477, because the negative that sets it belongs to a species that stays flagged.

## Animals-only flags (user decides)

- There are 22 animals-only species. Prunus serotina, Quercus stellata, Helenium autumnale, and Crotalaria spectabilis stay flagged anyway through USDA or stub.
- The other 18 would unflag: *Acer negundo*, *Acer rubrum*, *Perilla frutescens*, *Glechoma hederacea*, *Rudbeckia laciniata*, *Onoclea sensibilis*, *Senna obtusifolia*, *Quercus marilandica*, *Saururus cernuus*, *Panicum virgatum*, *Achillea millefolium*, *Rumex obtusifolius*, *Asclepias verticillata*, *Osmundastrum cinnamomeum*, *Hypochaeris radicata*, *Cynodon dactylon*, *Oxypolis rigidior*, *Oxalis stricta*.
- Two of them clear the floor and become targets: box elder and red maple. The net gain is 3 passing photos (1 original target, plus 1 each for *Juglans* and *Acer rubrum*), and false passes stay at 0/150.
- These were classified by hand. The proposed regex does not do it.

## Do not unflag

- *Pistia stratiotes*: the rule unflags it, but water lettuce is an aroid with calcium oxalate raphides, like taro and arrow arum. The article just doesn't say so. Hold it by hand or accept the gap.
- *Achillea millefolium*: it is in the animals-only class, but the article warns that confusing it with poison hemlock can be deadly. That makes it a look-alike risk.
- *Oxypolis rigidior* and *Maianthemum racemosum*: both are look-alikes of very toxic plants (water hemlock and *Veratrum*).
- *Asimina triloba*, *Triadica sebifera*, *Gomphocarpus physocarpus*, *Menispermum canadense*, *Actaea pachypoda*, *Pontederia crassipes*: the Day-2 list doubted these flags, but every one has a sentence about harm to people. *Menispermum* and *Actaea* fruit can be fatal.
- *Quercus alba*: flagged by USDA moderate only, through GBIF synonym widening. It stays flagged under every proposal.
- The stubs above the floor, *Persicaria longiseta* (184 sightings) and *Eupatorium serotinum* (92), stay flagged. A stub is silence, not evidence.
