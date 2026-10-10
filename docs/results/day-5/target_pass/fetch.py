"""Day-5 target-pass TEST set: fresh West Georgia target photos and fresh toxic or blocker look-alike photos.

Run from the repo root after make assets: uv run --project pipeline python -I docs/results/day-5/target_pass/fetch.py

With photos.csv present, re-downloads any photo missing from .models/target-pass-photos (gitignored) and checks every
SHA-256; nothing else. Without it, builds the set: iNaturalist research-grade observations with a CC0 photo, at about
1 request per second, taxon exact, one photo per observation, newest observations first (Day 3 and Day 4 took the
oldest), a new observer per photo within a species where one exists.
  target: TARGET_EACH photos for each of the 23 West Georgia October eligible targets (calibration.py's hunt)
  toxic: TOXIC species and counts below; every one is a toxic-flagged or hazard row of species_labels.json. Poison
    ivy, Virginia creeper, the flagged oaks, pokeweed, and horsenettle, then the blockers that beat real targets in
    the Day-3 calibration, holdout, and toxic-block logs, then the toxic photos that came closest to passing there
No observation, iNat photo, or SHA-256 named anywhere else in docs/results is reused. Writes photos.csv
(holdout.photos.csv columns).
"""

import csv
import importlib.util
import json
import sys
import urllib.parse
from pathlib import Path

from wild_find_pipeline.paths import MODEL_CACHE

OUT = Path(__file__).resolve().parent
RESULTS = OUT.parents[1]
_spec = importlib.util.spec_from_file_location("calibration", RESULTS / "day-3" / "calibration.py")
cal = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cal)

PHOTOS = MODEL_CACHE / "target-pass-photos"
MANIFEST = OUT / "photos.csv"
FIELDS = ["photo", "set", "species", "taxon", "rank", "observation", "photo_id", "photo_url", "license", "source",
          "sha256"]  # fmt: skip
TARGET_EACH = 3
TOXIC = {
    "Toxicodendron radicans": 8,
    "Toxicodendron pubescens": 2,
    "Parthenocissus quinquefolia": 4,
    "Quercus alba": 3,
    "Quercus stellata": 3,
    "Quercus marilandica": 3,
    "Phytolacca americana": 4,
    "Solanum carolinense": 4,
    "Juglans nigra": 3,
    "Ampelopsis cordata": 2,
    "Melothria pendula": 2,
    "Asimina triloba": 2,
    "Gleditsia triacanthos": 2,
    "Menispermum canadense": 2,
    "Photinia serratifolia": 2,
    "Smallanthus uvedalia": 2,
    "Hedera helix": 2,
    "Acer negundo": 3,
    "Acer rubrum": 3,
    "Monotropa uniflora": 2,
}


def candidates(name: str, skip_obs: set[int], skip_photos: set[int]) -> list[dict]:
    """Research-grade observations of exactly `name` with a CC0 photo, newest first, one photo each."""
    query = {"taxon_name": name, "quality_grade": "research", "photo_license": "cc0", "per_page": 100,
             "order_by": "id", "order": "desc"}  # fmt: skip
    found = []
    for obs in json.loads(cal.tb.get(f"{cal.API}?{urllib.parse.urlencode(query)}"))["results"]:
        if obs["id"] in skip_obs or obs["taxon"]["name"] != name:
            continue
        photo = next((p for p in obs["photos"] if p.get("license_code") == "cc0"), None)
        if photo is None or photo["id"] in skip_photos:
            continue
        found.append({"taxon": obs["taxon"]["name"], "rank": obs["taxon"]["rank"], "observation": obs["id"],
                      "photo_id": photo["id"], "photo_url": photo["url"].replace("/square.", "/medium."),
                      "license": "cc0", "source": f"https://www.inaturalist.org/observations/{obs['id']}",
                      "user": obs["user"]["id"]})  # fmt: skip
    return found


def pick(found: list[dict], want: int) -> list[dict]:
    """Up to `want` rows, new observers first, then repeat observers in order."""
    seen, first, rest = set(), [], []
    for row in found:
        (rest if row["user"] in seen else first).append(row)
        seen.add(row["user"])
    return (first + rest)[:want]


def build(h: dict) -> list[dict]:
    """Pick, download, and SHA-256 every photo."""
    labels = {n: i for i, n in enumerate(h["names"])}
    bad = [n for n in TOXIC if n not in labels or not h["is_toxic"][labels[n]]]
    if bad:
        raise ValueError(f"not a toxic or hazard table row: {bad}")
    skip_obs, skip_photos, skip_shas, _ = cal.used_elsewhere()
    # used_elsewhere skips files named calibration* and holdout*, so the S50 and S51 manifests are added here.
    for manifest in RESULTS.rglob("*.csv"):
        if manifest.name.startswith(("calibration", "holdout")) and OUT not in manifest.parents:
            for row in csv.DictReader(manifest.open()):
                if (row.get("observation") or "").isdigit():
                    skip_obs.add(int(row["observation"]))
                if (row.get("photo_id") or "").isdigit():
                    skip_photos.add(int(row["photo_id"]))
                if row.get("sha256"):
                    skip_shas.add(row["sha256"])
    wanted = [(h["names"][i], h["inat"][i], "target", TARGET_EACH) for i in h["eligible"]]
    wanted += [(n, n, "toxic", k) for n, k in TOXIC.items()]
    rows = []
    for name, inat, kind, want in wanted:
        picked = pick(candidates(inat, skip_obs, skip_photos), want)
        for row in picked:
            row.pop("user")
            skip_obs.add(row["observation"])
        kept = cal.download([{"set": kind, "species": name, **r} for r in picked], PHOTOS, skip_shas)
        rows += kept
        print(f"  {kind} {name} (iNat {inat}): {len(kept)} of {want}", file=sys.stderr)
    return rows


def main() -> int:
    """Build photos.csv once, or verify the cached photos against it."""
    if MANIFEST.exists():
        cal.cached(list(csv.DictReader(MANIFEST.open())), PHOTOS)
        return 0
    rows = build(cal.hunt(cal.ASSETS))
    with MANIFEST.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
