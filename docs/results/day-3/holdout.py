"""S51 holdout: the shipped per-capture verify on fresh photos never used in calibration or any earlier run.

Run from the repo root after make assets and calibration.py: uv --project pipeline run python -I
docs/results/day-3/holdout.py

Same verify path, hunt, and asset snapshot as calibration.py (rows 1, 2, 4 per capture; focus rows 3 and 5 cannot be
simulated on a still, so every photo counts as focused). Photos:
  target: iNaturalist research-grade CC0, 1 per West Georgia October eligible target plus a second for the 7 targets
    that got only one in calibration (30)
  toxic: 1 per local toxic or hazard blocker, every local hazard species first, then the most-sighted toxic rows, up to
    TOXIC_PHOTOS species
  non_plant: Wikimedia Commons Unsplash files licensed CC0 or public domain, first hits per search, 500 px thumbnails
    like Day 1, never a file page Day 1 used; files filled by plants (VEGETATION, screened by eye) skipped
No observation, iNat photo, Commons page, or SHA-256 named anywhere in docs/results (calibration included) is reused.
Bars (S51): own-target pass >= 45% of target photos; 0 toxic photos pass as any target; non-plant and wrong-genus
false pass <= 5%; hazard false alarms on target photos recorded (H6). Writes holdout.csv (one verify row per photo)
and holdout.photos.csv (manifest: species, observation or file page, URL, license, SHA-256); prints the summary.
"""

import csv
import importlib.util
import json
import re
import sys
import urllib.parse
from pathlib import Path

from wild_find_pipeline.paths import MODEL_CACHE

OUT = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("calibration", OUT / "calibration.py")
cal = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cal)

PHOTOS = MODEL_CACHE / "holdout-photos"
MANIFEST = OUT / "holdout.photos.csv"
TOXIC_PHOTOS = 15
COMMONS = "https://commons.wikimedia.org/w/api.php"
# (search, photos): screens, people, and pavement first, as S51 names them, then other everyday non-plants.
NON_PLANT_QUERIES = (
    ("computer monitor", 2),
    ("laptop screen", 2),
    ("smartphone", 2),
    ("television", 1),
    ("woman portrait", 2),
    ("man portrait", 2),
    ("child", 1),
    ("people street", 2),
    ("asphalt", 2),
    ("crosswalk", 1),
    ("parking lot", 1),
    ("sidewalk", 2),
    ("sneakers", 1),
    ("dog", 1),
    ("car", 1),
    ("kitchen", 1),
    ("building", 1),
    ("bicycle", 1),
    ("living room", 1),
)

# Screened by eye before the run: plants or painted flowers fill these, so a pass would not be a false pass.
VEGETATION = {
    "File:Mother_Nature,_Summer_(Unsplash).jpg",
    "File:Brunette_woman_portrait_(Unsplash).jpg",
    "File:Flower_Child_(Unsplash).jpg",
    "File:Alone_in_the_unspoilt_wilderness_(Unsplash).jpg",
    "File:Urban_fenced_sidewalk_(Unsplash).jpg",
}


def commons_files(search: str, skip_pages: set[str]) -> list[dict]:
    """Commons bitmap files matching `search` whose license is CC0 or public domain, in search order."""
    query = {"action": "query", "format": "json", "generator": "search", "gsrnamespace": 6,
             "gsrsearch": f"{search} Unsplash filetype:bitmap", "gsrlimit": 50, "prop": "imageinfo",
             "iiprop": "url|extmetadata", "iiurlwidth": 500,
             "iiextmetadatafilter": "LicenseShortName|License"}  # fmt: skip
    pages = json.loads(cal.tb.get(f"{COMMONS}?{urllib.parse.urlencode(query)}"))["query"]["pages"].values()
    found = []
    for page in sorted(pages, key=lambda p: p["index"]):
        info = page["imageinfo"][0]
        meta = info.get("extmetadata", {})
        code = meta.get("License", {}).get("value", "").lower()
        short = meta.get("LicenseShortName", {}).get("value", "")
        if not (code == "cc0" or code.startswith("pd") or short.lower().startswith("public domain")):
            continue
        title = page["title"].replace(" ", "_")
        # EFTA files are scanned evidence releases, not everyday scenes.
        if title in skip_pages | VEGETATION or "thumburl" not in info or title.startswith("File:EFTA"):
            continue
        stem = re.sub(r"[^A-Za-z0-9._-]", "_", title.removeprefix("File:"))
        found.append({"file": f"non_plant_{stem}", "taxon": "", "rank": "", "observation": "", "photo_id": "",
                      "photo_url": info["thumburl"].split("?")[0], "license": short or code,
                      "source": info["descriptionurl"]})  # fmt: skip
    return found


def build_manifest(h: dict) -> list[dict]:
    """Fresh holdout photos: targets, local toxic and hazard blockers, and Commons non-plants."""
    skip_obs, skip_photos, skip_shas, skip_pages = cal.used_elsewhere()
    for row in csv.DictReader(cal.MANIFEST.open()):
        skip_obs.add(int(row["observation"]))
        skip_photos.add(int(row["photo_id"]))
        skip_shas.add(row["sha256"])
    wanted = []
    for k, i in enumerate(h["eligible"]):
        name = h["inat"][i]
        found = cal.inat_candidates(name, h["taxon_ids"][name], skip_obs, skip_photos)
        found = [c for c in found if c["taxon"] == name]
        take = 1 if k < cal.CALIBRATION_EXTRA or k >= 2 * cal.CALIBRATION_EXTRA else 2
        wanted += [{"set": "target", "species": h["names"][i], **c} for c in found[:take]]
        print(f"  target {h['names'][i]}: {min(take, len(found))} of {take}", file=sys.stderr)
    order = sorted(h["blockers"], key=lambda i: (not h["is_hazard"][i], -h["count"][i], i))
    toxic = 0
    for i in order:
        if toxic == TOXIC_PHOTOS:
            break
        name = h["inat"][i]
        found = cal.inat_candidates(name, h["taxon_ids"].get(name), skip_obs, skip_photos)
        found = [c for c in found if c["taxon"] == name]
        if found:
            wanted.append({"set": "toxic", "species": h["names"][i], **found[0]})
            toxic += 1
        print(f"  toxic {h['names'][i]} (iNat {name}): {min(1, len(found))}", file=sys.stderr)
    for search, n in NON_PLANT_QUERIES:
        found = commons_files(search, skip_pages)[:n]
        skip_pages |= {c["source"].rsplit("/", 1)[1] for c in found}
        wanted += [{"set": "non_plant", "species": search, **c} for c in found]
        print(f"  non-plant {search!r}: {len(found)} of {n}", file=sys.stderr)
    return cal.download(wanted, PHOTOS, skip_shas)


def rate(n: int, d: int) -> str:
    """`n/d (p%)`."""
    return f"{n}/{d} ({n / d:.0%})" if d else "0/0"


def main() -> int:
    """Run S51 on the holdout photos; write holdout.csv."""
    shas = cal.snapshot_assets()
    h = cal.hunt(cal.ASSETS)
    if len(h["eligible"]) != 23:
        raise ValueError(f"expected 23 eligible West Georgia targets, got {len(h['eligible'])}")
    if not MANIFEST.exists():
        cal.write(MANIFEST, build_manifest(h))
    photos = cal.cached(list(csv.DictReader(MANIFEST.open())), PHOTOS)
    calibration = {r["sha256"] for r in csv.DictReader(cal.MANIFEST.open())}
    if calibration & {p["sha256"] for p in photos}:
        raise ValueError("a holdout photo is also a calibration photo")
    cal.header(shas, [MANIFEST, cal.DAY2 / "threshold_regions.csv", cal.DAY2 / "inat_species_oct.csv"])
    print(f"West Georgia October: {len(h['eligible'])} eligible, {len(h['blockers'])} local toxic or hazard blockers")
    v = cal.Verifier(cal.ASSETS, h)
    out = []
    for p in photos:
        r = {k: p[k] for k in ("photo", "set", "species", "observation", "license", "source")}
        r |= v.run(PHOTOS / p["photo"])
        own = h["row_of"].get(p["species"]) if p["set"] == "target" else None
        r["right_genus"] = own is not None and r["top1_genus"] == h["genus"][own]
        r["pass_own"] = own is not None and r["found_any"] and r["right_genus"]
        r["wrong_genus_pass"] = own is not None and r["found_any"] and not r["right_genus"]
        r["raw_found_any"] = r["reticle_plant"] and r["margin_ok"]  # rows 2 and 4 only, before the hazard row
        out.append(r)
    cal.write(OUT / "holdout.csv", out)

    t = [r for r in out if r["set"] == "target"]
    x = [r for r in out if r["set"] == "toxic"]
    n = [r for r in out if r["set"] == "non_plant"]
    hz = [r for r in x if h["is_hazard"][h["row_of"][r["species"]]]]
    print(f"photos: {len(t)} target ({len({r['species'] for r in t})} species), {len(x)} toxic or hazard "
          f"({len(hz)} hazard species), {len(n)} non-plant")  # fmt: skip
    passed = sum(r["pass_own"] for r in t)
    verdict = "MET" if passed / len(t) >= 0.45 else "MISSED"
    print(f"\nS51 own-target pass (bar >= 45%): {rate(passed, len(t))} -> {verdict}")
    for cause in ("hazard", "not_plant", "no_match"):
        print(f"  target lost to {cause}: {sum(r['verdict'] == cause for r in t)}")
    tox = sum(r["found_any"] for r in x)
    print(f"toxic pass as any target (bar 0): {rate(tox, len(x))} -> {'MET' if tox == 0 else 'MISSED'}; "
          f"before row 1: {sum(r['raw_found_any'] for r in x)}; caught by row 1: {sum(r['hazard_warn'] for r in x)}; "
          f"hazard species warned: {rate(sum(r['hazard_warn'] for r in hz), len(hz))}")  # fmt: skip
    np_pass = sum(r["found_any"] for r in n)
    wrong = sum(r["wrong_genus_pass"] for r in t)
    both = np_pass + wrong
    gate_no = sum(not r["reticle_plant"] for r in n)
    print(f"non-plant false pass: {rate(np_pass, len(n))}; reticle gate rejects {rate(gate_no, len(n))}, "
          f"full frame {rate(sum(not r['full_plant'] for r in n), len(n))}")  # fmt: skip
    print(f"wrong-genus false pass on target photos: {rate(wrong, len(t))}")
    print(f"non-plant + wrong-genus false pass (bar <= 5%): {rate(both, len(n) + len(t))} -> "
          f"{'MET' if both / (len(n) + len(t)) <= 0.05 else 'MISSED'}")  # fmt: skip
    print(f"H6 hazard false alarm on target photos: {rate(sum(r['hazard_warn'] for r in t), len(t))}; "
          f"on non-plants: {rate(sum(r['hazard_warn'] for r in n), len(n))}")  # fmt: skip
    for r in out:
        bad = (r["set"] == "target" and not r["pass_own"]) or (r["set"] != "target" and r["found_any"])
        if bad or (r["set"] != "toxic" and r["hazard_warn"]):
            print(f"  {r['set']} {r['photo']}: {r['verdict']}, top1 {r['top1']} {r['top1_score']}, pool top1 "
                  f"{r['pool_top1']}, blocker {r['best_blocker']} gap {r['gap']}, shares {r['reticle_share']}/"
                  f"{r['full_share']}, hazard ranks {r['reticle_hazard_rank']}/{r['full_hazard_rank']} "
                  f"({r['reticle_best_hazard']})")  # fmt: skip
    return 0


if __name__ == "__main__":
    sys.exit(main())
