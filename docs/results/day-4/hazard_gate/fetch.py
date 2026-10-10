"""S55 ship gate photos: contact hazards and safe look-alikes from outside the US Southeast.

Run from the repo root: uv run --project pipeline python -I docs/results/day-4/hazard_gate/fetch.py

With photos.csv present, re-downloads any photo missing from .models/hazard-gate-photos (gitignored) and checks every
SHA-256; nothing else. Without it, builds the set: iNaturalist research-grade observations with a CC0 photo, at about
1 request per second, taxon exact or below it, one photo per observation, a new observer per photo where one exists,
taken round-robin from Georgia (the country), Europe, Asia, and Canada, Georgia first. Every species is a row of
species_labels.json; hazard rows are contact or handling hazards (stinging hairs, phototoxic or irritant sap, latex),
safe rows are neither toxic nor hazard-flagged there and look like a hazard. No observation, iNat photo, or SHA-256
named anywhere else in docs/results is reused. Writes photos.csv (holdout.photos.csv columns) and prints each pick's
region.
"""

import csv
import importlib.util
import json
import re
import sys
import urllib.parse
from pathlib import Path

from wild_find_pipeline.paths import GENERATED_ASSETS, MODEL_CACHE

OUT = Path(__file__).resolve().parent
RESULTS = OUT.parents[1]
_spec = importlib.util.spec_from_file_location("calibration", RESULTS / "day-3" / "calibration.py")
cal = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cal)

PHOTOS = MODEL_CACHE / "hazard-gate-photos"
MANIFEST = OUT / "photos.csv"
FIELDS = ["photo", "set", "species", "taxon", "rank", "observation", "photo_id", "photo_url", "license", "source",
          "sha256"]  # fmt: skip
REGIONS = {"Georgia": 8857, "Europe": 97391, "Asia": 97395, "Canada": 6712}
HAZARD = {
    "Urtica dioica": 5,
    "Urtica urens": 4,
    "Laportea canadensis": 4,
    "Heracleum mantegazzianum": 5,
    "Heracleum sosnowskyi": 5,
    "Heracleum sphondylium": 4,
    "Heracleum maximum": 4,
    "Pastinaca sativa": 5,
    "Euphorbia cyparissias": 4,
    "Euphorbia virgata": 4,
    "Chelidonium majus": 4,
    "Ficus carica": 4,
}
SAFE = {
    name: 2
    for name in (
        "Lamium album",
        "Lamium purpureum",
        "Lamium maculatum",
        "Galeopsis tetrahit",
        "Stachys sylvatica",
        "Ballota nigra",
        "Mentha longifolia",
        "Melissa officinalis",
        "Filipendula ulmaria",
        "Carum carvi",
        "Astrantia major",
        "Angelica atropurpurea",
        "Smyrnium olusatrum",
        "Geranium robertianum",
        "Leucanthemum vulgare",
    )
}


def check_table() -> None:
    """Fail unless every hazard species is a table row and every safe one is a row flagged neither toxic nor hazard."""
    rows = {r["scientific"]: r for r in json.loads((GENERATED_ASSETS / "species_labels.json").read_text())}
    missing = [n for n in [*HAZARD, *SAFE] if n not in rows]
    flagged = [n for n in SAFE if n in rows and (rows[n]["toxic"] or rows[n]["hazard"])]
    if missing or flagged:
        raise ValueError(f"not in species table: {missing}; safe but flagged: {flagged}")


def used_elsewhere() -> tuple[set[int], set[int], set[str]]:
    """Observation ids, iNat photo ids, and SHA-256s named in any docs/results file outside this directory."""
    obs, photos, shas = set(), set(), set()
    for path in RESULTS.rglob("*"):
        if not path.is_file() or OUT in path.parents or path.suffix in (".pyc", ".png"):
            continue
        text = path.read_text(errors="ignore")
        obs |= {int(m) for m in re.findall(r"observations/(\d+)", text)}
        photos |= {int(m) for m in re.findall(r"photos/(\d+)/", text)}
        shas |= set(re.findall(r"\b[0-9a-f]{64}\b", text))
        if path.suffix in (".csv", ".tsv"):
            for row in csv.DictReader(path.open(), delimiter="\t" if path.suffix == ".tsv" else ","):
                for key in ("observation", "observation_id", "obs_id"):
                    if (row.get(key) or "").isdigit():
                        obs.add(int(row[key]))
    return obs, photos, shas


def candidates(name: str, place: int, skip_obs: set[int], skip_photos: set[int]) -> list[dict]:
    """Research-grade observations of `name` (or below it) in `place` with a CC0 photo, one photo each."""
    query = {"taxon_name": name, "place_id": place, "quality_grade": "research", "photo_license": "cc0",
             "per_page": 50, "order_by": "id"}  # fmt: skip
    found = []
    for obs in json.loads(cal.tb.get(f"{cal.API}?{urllib.parse.urlencode(query)}"))["results"]:
        taxon = obs["taxon"]["name"]
        if obs["id"] in skip_obs or not (taxon == name or taxon.startswith(f"{name} ")):
            continue
        photo = next((p for p in obs["photos"] if p.get("license_code") == "cc0"), None)
        if photo is None or photo["id"] in skip_photos:
            continue
        found.append({"taxon": taxon, "rank": obs["taxon"]["rank"], "observation": obs["id"], "photo_id": photo["id"],
                      "photo_url": photo["url"].replace("/square.", "/medium."), "license": "cc0",
                      "source": f"https://www.inaturalist.org/observations/{obs['id']}",
                      "user": obs["user"]["id"]})  # fmt: skip
    return found


def pick(name: str, want: int, pools: dict[str, list[dict]], observers: set[int]) -> list[tuple[str, dict]]:
    """Round-robin over regions, Georgia first: new observers set-wide, then new within the species."""
    picked: list[tuple[str, dict]] = []
    for strict in (True, False):
        mine = {row["user"] for _, row in picked}
        progress = True
        while len(picked) < want and progress:
            progress = False
            for region, pool in pools.items():
                row = next((r for r in pool if r["user"] not in (observers if strict else mine)), None)
                if row is None or len(picked) >= want:
                    continue
                pool.remove(row)
                picked.append((region, row))
                observers.add(row["user"])
                mine.add(row["user"])
                progress = True
    return picked


def build() -> list[dict]:
    """Pick, download, and SHA-256 every photo; prints each species' picks by region."""
    check_table()
    skip_obs, skip_photos, skip_shas = used_elsewhere()
    observers: set[int] = set()
    rows = []
    for kind, species in (("hazard", HAZARD), ("safe", SAFE)):
        for name, want in species.items():
            pools = {region: candidates(name, place, skip_obs, skip_photos) for region, place in REGIONS.items()}
            picked = pick(name, want, pools, observers)
            for region, row in picked:
                row.pop("user")
                kept = cal.download([{"set": kind, "species": name, **row}], PHOTOS, skip_shas)
                rows += kept
                print(f"{kind}\t{name}\t{region}\t{row['observation']}\t{'ok' if kept else 'duplicate sha, dropped'}")
            if len(picked) < want:
                print(f"{kind}\t{name}\tshort: {len(picked)} of {want}")
    return rows


def main() -> int:
    """Verify the cached set against photos.csv, or build it when photos.csv is absent."""
    if MANIFEST.exists():
        rows = cal.cached(list(csv.DictReader(MANIFEST.open())), PHOTOS)
        print(f"{len(rows)} photos match photos.csv")
        return 0
    rows = build()
    with MANIFEST.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"{len(rows)} photos written to {MANIFEST.relative_to(RESULTS.parent.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
