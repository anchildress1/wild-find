"""The pinned USDA PLANTS traits archive and its measurement-to-taxon join, shared by every USDA-reading step."""

import csv
import io
import sys
import tarfile
import urllib.request
from collections.abc import Callable, Iterable
from pathlib import Path

from wild_find_pipeline.gbif import USER_AGENT
from wild_find_pipeline.paths import MODEL_CACHE, file_sha256

# USDA PLANTS traits as published to Zenodo on Dec 11, 2025; pinned by bytes, like the models.
USDA_URL = "https://zenodo.org/api/records/17903503/files/usda_plant_traits.tar.gz/content"
USDA_SHA256 = "d646ab96b3308a51f66bf5adfe3ac7e31abd68223c9436734789c96da9df014b"


def archive() -> bytes:
    """The pinned USDA PLANTS traits archive, cached in .models and checked by SHA-256."""
    MODEL_CACHE.mkdir(exist_ok=True)
    path = MODEL_CACHE / "usda_plant_traits.tar.gz"
    if not path.is_file() or file_sha256(path) != USDA_SHA256:
        request = urllib.request.Request(USDA_URL, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=300) as response:
            path.write_bytes(response.read())
    if file_sha256(path) != USDA_SHA256:
        raise ValueError(f"{path} does not match its pinned SHA-256")
    return path.read_bytes()


def measurements(data: bytes, keep: Callable[[dict], bool]) -> tuple[list[tuple[str | None, dict]], dict[str, dict]]:
    """Kept measurement rows in archive order, each with its taxonID (None when unjoined), and taxon rows by taxonID."""
    csv.field_size_limit(sys.maxsize)
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        # The published archive stores members as ./name.tab.
        members = {Path(m.name).name: m for m in tar.getmembers()}

        def rows(member: str):
            text = tar.extractfile(members[member]).read().decode()
            return csv.DictReader(io.StringIO(text), delimiter="\t", quoting=csv.QUOTE_NONE)

        kept = [r for r in rows("measurement_or_fact_specific.tab") if keep(r)]
        wanted = {r["occurrenceID"] for r in kept}
        taxon_of = {
            r["occurrenceID"]: r["taxonID"] for r in rows("occurrence_specific.tab") if r["occurrenceID"] in wanted
        }
        taxa = {r["taxonID"]: r for r in rows("taxon.tab")}
    return [(taxon_of.get(r["occurrenceID"]), r) for r in kept], taxa


def species_name(taxon: dict) -> str:
    """A taxon row's scientific name cut to its first two words, so infraspecific rows land on their species."""
    return " ".join(taxon["scientificName"].split()[:2])


def by_binomial[V](found: Iterable[tuple[str | None, V]], taxa: dict[str, dict]) -> dict[str, list[V]]:
    """Values grouped by binomial, from its species row when it has any, else from its infraspecific rows.

    Genus rows and values without a taxon row are dropped.
    """
    species: dict[str, list[V]] = {}
    infra: dict[str, list[V]] = {}
    for taxon, value in found:
        row = taxa.get(taxon)
        if row is None or row["taxonRank"] == "genus":
            continue
        (species if row["taxonRank"] == "species" else infra).setdefault(species_name(row), []).append(value)
    return infra | species
