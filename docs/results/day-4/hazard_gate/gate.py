"""S55 ship gate: the hazard rule on the generated contact-hazard list, over the Day-1, S50, S51, and new non-US sets.

Run from the repo root after make assets:
    uv --project pipeline run python -I docs/results/day-4/hazard_gate/gate.py > docs/results/day-4/hazard_gate/gate.log

Rule as core HazardCheck and FrameVerifier ship it: warn when the best hazard row ranks in the top k of the whole
4,272-row species table (1 + rows scoring above it, ties warn) on the reticle or full-frame crop the plant gate passes.
The rank never depends on the hunt's local rows (HazardCheck counts every row; local rows only pick the species it
names), so no photo needs a local set: every set ranks against the whole table, exactly as the phone does. `old` flags
are the fixed 7 (labels.is_hazard), `new` the generated `hazard` column. k = 5 ships; 1 to 4 are measured, not changed.

Sets:
  Day 1: day1_rerun.py (305 photos; before must reproduce 48/52 and 1/253)
  S50, S51: day-3 calibration.py and holdout.py run unchanged except that their CSVs and logs land here
    (calibration.csv, calibration.log, holdout.csv, holdout.log) and the header date is today's; before = the same
    photos ranked with the old flags in this run, cross-checked against the day-3 CSVs
  new: photos.csv (fetch.py; 52 hazard, 30 safe from Georgia, Europe, Asia, Canada); catch is reported over all hazard
    photos and over species the generated list flags, safe warnings over all safe photos and over those still unflagged
Writes day1.csv, s50_s51.csv, new_set.csv (one row per photo, ranks per region and flag set).
"""

import contextlib
import csv
import hashlib
import importlib.util
import json
import math
import platform
import subprocess
import sys
import urllib.parse
from datetime import datetime
from pathlib import Path

import numpy as np
import onnxruntime as ort
import PIL
from wild_find_pipeline.paths import MODEL_CACHE, file_sha256, pin

OUT = Path(__file__).resolve().parent
RESULTS = OUT.parents[1]
DAY3 = RESULTS / "day-3"


def load(name: str, path: Path):
    """Import a results script as a module."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


d1 = load("day1_rerun", OUT / "day1_rerun.py")
cal = d1.cal
hold = load("holdout", DAY3 / "holdout.py")
# holdout.py loads its own calibration module; point both at this directory for output and today's header date.
NOW = datetime.now().astimezone()
for module in (cal, hold.cal):
    module.OUT = OUT
    module.RUN_DATE = NOW.date()
hold.OUT = OUT

NEW_PHOTOS = MODEL_CACHE / "hazard-gate-photos"
INAT_CACHE = MODEL_CACHE / "hazard-gate-inat"
OBSERVATIONS = "https://api.inaturalist.org/v1/observations"
# core SpeciesCountsQuery, CacheKey, and LocalSpecies constants; a US English device sends locale en-US.
SPECIES_COUNTS = "https://api.inaturalist.org/v1/observations/species_counts"
LOCALE = "en-US"
PER_PAGE = 500
MAX_PAGES = 3
RADIUS_KM = 75
WIDE_RADIUS_KM = 150
SHARE = 0.005
MIN_SIGHTINGS = 3
MAX_NAME_WORDS = 3
MIN_TARGETS = 3
# Owner's decision, Oct 10: ship rule B at k = 5; the Day-1 safe bar relaxed from <= 1 to <= 3 of 253 after the
# original bar failed under every rule and cutoff measured here.
GATE_RULE, GATE_K = "local", 5
CAUGHT_BAR, SAFE_BAR, ORIGINAL_SAFE_BAR = 48, 3, 1
CANDIDATES = ("new", "local", "tri", "tri_local")
MODEL_PINS = ("bioclip", "teacher", "tinyclip", "taxa", "taxa_labels")


def header(shas: dict[str, str]) -> None:
    """Run time, machine, package versions, model pins, asset and input SHA-256s."""
    chip = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True).stdout.strip()
    print(f"run {NOW.isoformat(timespec='seconds')}; {chip}, macOS {platform.mac_ver()[0]}, laptop CPU")
    print(f"python {sys.version.split()[0]}, onnxruntime {ort.__version__}, numpy {np.__version__}, Pillow "
          f"{PIL.__version__}")  # fmt: skip
    for key in MODEL_PINS:
        p = pin(key)
        print(f"  pin {key}: {p['repo']}@{p['revision']}" + (f" {p['file']} sha256 {p['sha256']}" if "file" in p
                                                              else ""))  # fmt: skip
    for name, sha in shas.items():
        print(f"  asset {name} sha256 {sha}")
    for path in (RESULTS.parent.parent / "pipeline/data/contact_hazards.json",
                 RESULTS.parent.parent / "pipeline/data/contact_hazards_review.json", d1.MANIFEST, cal.MANIFEST,
                 hold.MANIFEST, OUT / "photos.csv", RESULTS / "day-1/tinyclip_scores.csv",
                 RESULTS / "day-1/species_scores.csv"):  # fmt: skip
        print(f"  input {path.relative_to(RESULTS.parent.parent)} sha256 {file_sha256(path)}")
    labels = json.loads((cal.ASSETS / "species_labels.json").read_text())
    flags = d1.flag_sets(labels)
    pairs = zip(labels, flags["new"], flags["tri"], strict=True)
    changed = [f"{'+' if t else '-'}{e['scientific']}" for e, n, t in pairs if n != t]
    print(f"triage variant (contact_hazards_review.json): {flags['tri'].sum()} hazard rows; {', '.join(changed)}")
    print(f"species rows {len(labels)}; hazard rows old {flags['old'].sum()}, new {flags['new'].sum()}; "
          f"old rows missing from new: {[e['scientific'] for e, o, n in zip(labels, flags['old'], flags['new'],
                                                                            strict=True) if o and not n]}")  # fmt: skip


def section(title: str) -> None:
    """Print a section header."""
    print(f"\n## {title}\n")


def eligibility() -> None:
    """West Georgia October targets under the old and new flags; a new hazard row that was a target leaves the hunt."""
    labels = json.loads((cal.ASSETS / "species_labels.json").read_text())
    old_assets = MODEL_CACHE / "hazard-gate-old-flags"
    old_assets.mkdir(exist_ok=True)
    (old_assets / "species_labels.json").write_text(
        json.dumps([e | {"hazard": bool(f)} for e, f in zip(labels, d1.flag_sets(labels)["old"], strict=True)])
    )
    table = old_assets / "species_table.npy"
    table.unlink(missing_ok=True)
    table.symlink_to(cal.ASSETS / "species_table.npy")
    before, after = cal.hunt(old_assets), cal.hunt(cal.ASSETS)
    names = after["names"]
    lost = [names[i] for i in before["eligible"] if i not in after["eligible"]]
    gained = [names[i] for i in after["eligible"] if i not in before["eligible"]]
    print(f"West Georgia October eligible targets: old flags {len(before['eligible'])}, new {len(after['eligible'])}; "
          f"blockers {len(before['blockers'])} -> {len(after['blockers'])}")  # fmt: skip
    print(f"  targets lost: {lost or 'none'}; gained: {gained or 'none'}")
    newly = sorted(names[i] for i in after["blockers"] if i not in before["blockers"])
    print(f"  local rows that became blockers ({len(newly)}): {', '.join(newly) or 'none'}")


def ranked_rows(v, manifest: list[dict], photos: Path, kind_of, local_of) -> list[dict]:
    """app_regions for every manifest photo under every rule, rule B with `local_of(photo)`'s row mask."""
    labels = json.loads((cal.ASSETS / "species_labels.json").read_text())
    names = [e["scientific"] for e in labels]
    out = []
    for p in manifest:
        row = {"set": p["set"], "photo": p["photo"], "species": p["species"], "kind": kind_of(p)}
        out.append(row | d1.app_regions(v, photos / p["photo"], d1.flag_sets(labels, local_of(p)), names))
    return out


def table(rows: list[dict], kind: str, label: str) -> None:
    """Warned count of `kind` photos per k under each rule."""
    n = sum(r["kind"] == kind for r in rows)
    counts = {name: d1.sweep(rows, kind, f"rank_{name}") for name in d1.RULES}
    print(f"{label} ({n}): " + "; ".join(f"k={k} " + " ".join(f"{d1.RULES[name]} {c[k]}" for name, c in
                                                             counts.items()) for k in d1.TOP_KS))  # fmt: skip


def warns(r: dict, rule: str) -> bool:
    """True when `rule` warns on photo row `r` at the shipped k."""
    return r[f"rank_{rule}"] <= d1.SHIPPED_K


def listing(rows: list[dict], show) -> None:
    """One line per photo `show` selects at the shipped k, with each rule's ranks and hazard species."""
    for r in rows:
        if show(r):
            print(f"  {r['kind']} {r['set']} {r['photo']} ({r['species']}): reticle/full "
                  + "; ".join(f"{d1.RULES[n]} {r[f'reticle_rank_{n}']}/{r[f'full_rank_{n}']} "
                              f"({r[f'reticle_hazard_{n}']} / {r[f'full_hazard_{n}']})" for n in d1.RULES)
                  + f"; plant share {r['reticle_share']}/{r['full_share']}")  # fmt: skip


def s50_s51(v, local: np.ndarray) -> list[dict]:
    """Run calibration.py and holdout.py into this directory, then rank their photos under every rule."""
    for module, log in ((cal, "calibration.log"), (hold, "holdout.log")):
        with (OUT / log).open("w") as fh, contextlib.redirect_stdout(fh):
            module.main()
        print(f"{log}: " + " | ".join(line.strip() for line in (OUT / log).read_text().splitlines()
                                      if "hazard" in line.lower() and not line.startswith("  ")))  # fmt: skip
    labels = {e["scientific"]: e for e in json.loads((cal.ASSETS / "species_labels.json").read_text())}

    def kind_of(p: dict) -> str:
        if p["set"] != "toxic":
            return "safe"
        return "hazard" if labels[p["species"]]["hazard"] else "toxic"

    rows = []
    for name, manifest, photos in (("S50", cal.MANIFEST, cal.PHOTOS), ("S51", hold.MANIFEST, hold.PHOTOS)):
        part = ranked_rows(v, list(csv.DictReader(manifest.open())), photos, kind_of, lambda p: local)
        rows += [{"run": name} | r for r in part]
        day3 = {
            r["photo"]: r["hazard_warn"] == "True"
            for r in csv.DictReader((DAY3 / f"{'calibration' if name == 'S50' else 'holdout'}.csv").open())
        }
        agree = sum((r["rank_old"] <= d1.SHIPPED_K) == day3[r["photo"]] for r in part)
        print(f"\n{name}: {len(part)} photos; old-flag k=5 warnings equal to the day-3 CSV hazard_warn on "
              f"{agree}/{len(part)}")  # fmt: skip
        table(part, "safe", "  safe photos warned")
        table(part, "hazard", "  hazard-flagged toxic photos warned (generated flags decide which are hazards)")
        table(part, "toxic", "  other toxic photos warned")
        listing(part, lambda r: r["kind"] == "safe" and any(warns(r, n) for n in d1.RULES))
        listing(part, lambda r: r["kind"] == "hazard" and not all(warns(r, n) for n in CANDIDATES))
    cal.write(OUT / "s50_s51.csv", rows)
    return rows


def cached_get(url: str) -> dict:
    """GET JSON through calibration's polite fetcher, cached under INAT_CACHE so reruns send no request."""
    path = INAT_CACHE / f"{hashlib.sha256(url.encode()).hexdigest()[:24]}.json"
    if not path.exists():
        INAT_CACHE.mkdir(parents=True, exist_ok=True)
        path.write_bytes(cal.tb.get(url))
    return json.loads(path.read_text())


def region_key(lat: float, lng: float) -> tuple[int, int]:
    """core RegionKey.from: Kotlin roundToInt (ties toward +infinity), longitude wrapped into -180..179."""
    return math.floor(lat + 0.5), (math.floor(lng + 0.5) + 180) % 360 - 180


def species_counts_url(key: tuple[int, int], month: int, radius: int, page: int) -> str:
    """core SpeciesCountsQuery.url: region center, radius, calendar month, research-grade plants, locale."""
    params = {"lat": key[0], "lng": key[1], "radius": radius, "month": month, "iconic_taxa": "Plantae",
              "quality_grade": "research", "locale": LOCALE, "per_page": PER_PAGE, "page": page}  # fmt: skip
    return f"{SPECIES_COUNTS}?{urllib.parse.urlencode(params)}"


def pull(key: tuple[int, int], month: int, radius: int) -> tuple[list[dict], list[str]]:
    """Every result of one app query (pages as SpeciesCountsQuery.pages, at most 3) and the URLs sent."""
    urls = [species_counts_url(key, month, radius, 1)]
    first = cached_get(urls[0])
    pages = min(MAX_PAGES, max(1, math.ceil(first["total_results"] / PER_PAGE)))
    urls += [species_counts_url(key, month, radius, page) for page in range(2, pages + 1)]
    return first["results"] + [r for u in urls[1:] for r in cached_get(u)["results"]], urls


def local_list(results: list[dict], labels: list[dict], row_of: dict[str, int]) -> tuple[set[int], int]:
    """core LocalSpecies.of on one pull: every table row it names (any count, as blockers) and eligible genera."""
    floor = max(MIN_SIGHTINGS, SHARE * sum(r["count"] for r in results))
    by_row: dict[int, list[dict]] = {}
    for r in results:
        row = row_of.get(r["taxon"]["name"])
        if row is not None:
            by_row.setdefault(row, []).append(r)
    genera = set()
    for row, hits in by_row.items():
        if sum(h["count"] for h in hits) < floor or labels[row]["toxic"] or labels[row]["hazard"]:
            continue
        names = (
            (h["taxon"].get("preferred_common_name") or "").strip() for h in sorted(hits, key=lambda h: -h["count"])
        )
        if any(n and len(n.split()) <= MAX_NAME_WORDS for n in names):
            genera.add(labels[row]["genus"])
    return set(by_row), len(genera)


def local_sets(manifest: list[dict], labels: list[dict], row_of: dict[str, int]) -> dict[str, dict]:
    """Per photo: the app's local rows at the observation's region and month, after core LocalListSource's widen."""
    ids = ",".join(p["observation"] for p in manifest)
    obs = {str(o["id"]): o for o in cached_get(f"{OBSERVATIONS}?id={ids}&per_page=200")["results"]}
    hunts: dict = {}
    out = {}
    for p in manifest:
        o = obs[p["observation"]]
        lat, lng = (float(x) for x in o["location"].split(","))
        key, month = region_key(lat, lng), o["observed_on_details"]["month"]
        if (key, month) not in hunts:
            near, urls = pull(key, month, RADIUS_KM)
            rows, genera = local_list(near, labels, row_of)
            radius, status = RADIUS_KM, "ready"
            if genera < MIN_TARGETS:
                wide, more = pull(key, month, WIDE_RADIUS_KM)
                urls += more
                rows, genera = local_list(wide, labels, row_of)
                radius, status = WIDE_RADIUS_KM, "ready" if genera >= MIN_TARGETS else "not_enough"
            hunts[(key, month)] = {"region": f"{key[0]}_{key[1]}", "month": month, "radius": radius, "status": status,
                                   "genera": genera, "rows": rows, "urls": urls}  # fmt: skip
        out[p["photo"]] = hunts[(key, month)] | {"obscured": o.get("obscured", False)}
    print(f"{len(hunts)} region-month pulls (locale {LOCALE}); queries sent or read from {INAT_CACHE.name}:")
    for h in hunts.values():
        print(f"  {h['region']} month {h['month']}: {h['status']} at {h['radius']} km, {h['genera']} eligible genera, "
              f"{len(h['rows'])} table rows")  # fmt: skip
        for url in h["urls"]:
            print(f"    {url}")
    return out


def new_set(v) -> list[dict]:
    """Hazard catch and safe warnings on the non-US photos; rule B uses each photo's own region pull."""
    manifest = list(csv.DictReader((OUT / "photos.csv").open()))
    cal.cached(manifest, NEW_PHOTOS)
    region = {}
    for line in (OUT / "fetch.log").read_text().splitlines():
        parts = line.split("\t")
        if len(parts) == 5 and parts[4] == "ok":
            region[parts[3]] = parts[2]
    entries = json.loads((cal.ASSETS / "species_labels.json").read_text())
    labels = {e["scientific"]: e for e in entries}
    row_of = {alias: i for i, e in enumerate(entries) for alias in [e["scientific"], *e["synonyms"]]}
    hunts = local_sets(manifest, entries, row_of)
    triaged = d1.triaged(entries)
    masks = {}
    for photo, h in hunts.items():
        masks[photo] = np.zeros(len(entries), dtype=bool)
        masks[photo][list(h["rows"])] = True
    rows = ranked_rows(v, manifest, NEW_PHOTOS, lambda p: p["set"], lambda p: masks[p["photo"]])
    for r, p in zip(rows, manifest, strict=True):
        h = hunts[p["photo"]]
        r |= {"region": region[p["observation"]], "on_list": labels[r["species"]]["hazard"],
              "on_triaged_list": bool(triaged[row_of[r["species"]]]),
              "species_local": row_of[r["species"]] in h["rows"], "hunt_region": h["region"], "month": h["month"],
              "radius_km": h["radius"], "hunt_status": h["status"], "obscured": h["obscured"]}  # fmt: skip
    cal.write(OUT / "new_set.csv", rows)
    hz = [r for r in rows if r["kind"] == "hazard"]
    on = [r for r in hz if r["on_list"]]
    safe = [r for r in rows if r["kind"] == "safe"]
    # fetch.py picked safe species that were unflagged then; a rebuilt list can flag one, and its warnings are right.
    clean = [r for r in safe if not r["on_list"] and not labels[r["species"]]["toxic"]]
    off = sorted({r["species"] for r in hz if not r["on_list"]})
    flagged = sorted({r["species"] for r in safe if r not in clean})
    print(f"\n{len(hz)} hazard photos ({len(on)} of species on the shipped list; off list: {', '.join(off) or 'none'}; "
          f"{sum(r['species_local'] for r in hz)} whose own species is in the photo's local pull), {len(safe)} safe "
          f"photos (now flagged: {', '.join(flagged) or 'none'}); {sum(r['obscured'] for r in rows)} observations "
          f"have obscured coordinates; photos in an unplayable hunt: "
          f"{sum(r['hunt_status'] != 'ready' for r in rows)}")  # fmt: skip
    groups = {
        f"hazard caught, all {len(hz)}": hz,
        "hazard caught, shipped-list species only": on,
        "hazard caught, triaged-list species only": [r for r in hz if r["on_triaged_list"]],
        "safe photos warned": safe,
        "safe photos warned, still-unflagged species": clean,
    }
    for label, group in groups.items():
        table(group, "hazard" if label.startswith("hazard") else "safe", label)
    print("per species, caught at k=3/4/5, " + " | ".join(d1.RULES[n] for n in CANDIDATES) + ":")
    for species in dict.fromkeys(r["species"] for r in rows):
        g = [r for r in rows if r["species"] == species]
        ks = " | ".join("/".join(str(sum(r[f"rank_{n}"] <= k for r in g)) for k in (3, 4, 5)) for n in CANDIDATES)
        off = ", off list" if g[0]["kind"] == "hazard" and not g[0]["on_list"] else ""
        print(f"  {g[0]['kind']} {species} ({len(g)}{off}, local in {sum(r['species_local'] for r in g)}): {ks}")
    print("hazard photos missed and safe photos warned at k=5 by rule A or B:")
    listing(rows, lambda r: (r["kind"] == "hazard" and not all(warns(r, n) for n in CANDIDATES))
            or (r["kind"] == "safe" and any(warns(r, n) for n in CANDIDATES)))  # fmt: skip
    return rows


def gate_table(day1: list[dict], s5x: list[dict], new: list[dict]) -> None:
    """Both candidate rules (and the fixed 7 for reference) at every k on every set."""
    labels = {e["scientific"]: e for e in json.loads((cal.ASSETS / "species_labels.json").read_text())}
    sets = {
        "Day-1 hazards caught": (day1, "hazard"),
        "Day-1 safe warned": (day1, "safe"),
        "S50 safe warned": ([r for r in s5x if r["run"] == "S50"], "safe"),
        "S51 safe warned": ([r for r in s5x if r["run"] == "S51"], "safe"),
        "new hazards caught": (new, "hazard"),
        "new safe warned": (new, "safe"),
        "new safe warned, unflagged species": (
            [r for r in new if not r["on_list"] and not labels[r["species"]]["toxic"]],
            "safe",
        ),
    }
    print("set                                  rule     " + "  ".join(f"k={k:<6d}" for k in d1.TOP_KS))
    for label, (rows, kind) in sets.items():
        n = sum(r["kind"] == kind for r in rows)
        for name in d1.RULES:
            c = d1.sweep(rows, kind, f"rank_{name}")
            print(f"{label:36s} {d1.RULES[name]:8s} " + "  ".join(f"{f'{c[k]}/{n}':8s}" for k in d1.TOP_KS))


def verdict(day1: list[dict], s5x: list[dict], new: list[dict]) -> None:
    """The gate of record: GATE_RULE at GATE_K against the Day-1 bars, plus the recorded sets."""
    col = f"rank_{GATE_RULE}"

    def count(rows: list[dict], kind: str) -> str:
        return f"{sum(r['kind'] == kind and r[col] <= GATE_K for r in rows)}/{sum(r['kind'] == kind for r in rows)}"

    caught = sum(r["kind"] == "hazard" and r[col] <= GATE_K for r in day1)
    warned = sum(r["kind"] == "safe" and r[col] <= GATE_K for r in day1)
    print(f"gate of record: {d1.RULES[GATE_RULE]}, k={GATE_K}, as-shipped list")
    print(f"  Day-1 hazards caught {count(day1, 'hazard')} (bar >= {CAUGHT_BAR}): "
          f"{'PASS' if caught >= CAUGHT_BAR else 'FAIL'}")  # fmt: skip
    result = "PASS" if warned <= SAFE_BAR else "FAIL"
    print(f"  Day-1 safe warned {count(day1, 'safe')} (bar <= {SAFE_BAR}, relaxed by the owner on Oct 10 from <= "
          f"{ORIGINAL_SAFE_BAR}, which failed under every rule): {result}")  # fmt: skip
    for run in ("S50", "S51"):
        print(f"  {run} safe warned {count([r for r in s5x if r['run'] == run], 'safe')} (recorded)")
    print(f"  new set hazards caught {count(new, 'hazard')}, safe warned {count(new, 'safe')} (recorded)")


def main() -> int:
    """Run every set; print the gate log."""
    shas = cal.snapshot_assets()
    header(shas)
    h = cal.hunt(cal.ASSETS)
    v = cal.Verifier(cal.ASSETS, h)
    wg = d1.west_georgia(h)
    print(f"rule B local set for Day 1, S50, S51: the cached West Georgia October pull, {wg.sum()} table rows, "
          f"{(wg & d1.flag_sets(json.loads((cal.ASSETS / 'species_labels.json').read_text()))['new']).sum()} of them "
          f"generated hazards")  # fmt: skip
    section("Day 1 set (day1_rerun.py)")
    day1 = d1.run(v, wg)
    cal.write(OUT / "day1.csv", day1)
    d1.summarize(day1)
    section("West Georgia hunt under old and new flags")
    eligibility()
    section("S50 calibration and S51 holdout")
    s5x = s50_s51(v, wg)
    section("New contact-hazard set (photos.csv)")
    new = new_set(v)
    section("Gate table: warned or caught photos per rule and k")
    gate_table(day1, s5x, new)
    section("Verdict")
    verdict(day1, s5x, new)
    return 0


if __name__ == "__main__":
    sys.exit(main())
