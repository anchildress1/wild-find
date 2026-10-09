"""Shared JSON HTTP fetch for the pipeline's network steps, and the GBIF backbone URLs they query."""

import email.utils
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import UTC, datetime

USER_AGENT = "wild-find-pipeline/0.1 (+https://github.com/anchildress1/wild-find)"
GBIF = "https://api.gbif.org/v1"


def retry_after(value: str | None) -> float:
    """Seconds to wait from a Retry-After header, in delta seconds or an HTTP date; 10 when absent or unreadable."""
    if value and value.strip().isdigit():
        return float(value)
    if value:
        try:
            return max(0.0, (email.utils.parsedate_to_datetime(value) - datetime.now(UTC)).total_seconds())
        except (TypeError, ValueError):
            pass
    return 10.0


def get(url: str) -> dict:
    """GET JSON with the named User-Agent, up to 5 tries: 429 and 503 wait per Retry-After, network errors back off.

    Raises the last error once the tries run out, and any other HTTP error at once.
    """
    for attempt in range(5):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as e:
            if attempt == 4 or e.code not in (429, 503):
                raise
            time.sleep(retry_after(e.headers.get("Retry-After")))
        except (urllib.error.URLError, TimeoutError):
            if attempt == 4:
                raise
            time.sleep(2**attempt)
    raise AssertionError("unreachable")


def match_url(name: str) -> str:
    """GBIF species/match URL for a plant name: strict, kingdom Plantae."""
    # Byte-identical across steps: .models/gbif caches replies by this exact string.
    return f"{GBIF}/species/match?{urllib.parse.urlencode({'name': name, 'kingdom': 'Plantae', 'strict': 'true'})}"
