import pytest

from wild_find_pipeline.gbif import match_url, retry_after


@pytest.mark.parametrize(
    ("header", "seconds"),
    [("30", 30.0), (None, 10.0), ("soon", 10.0), ("Wed, 07 Oct 2020 23:00:00 GMT", 0.0)],
)
def test_retry_after_reads_seconds_or_a_past_date(header, seconds):
    assert retry_after(header) == seconds


def test_match_url_is_the_exact_string_the_gbif_cache_is_keyed_by():
    assert (
        match_url("Quercus alba")
        == "https://api.gbif.org/v1/species/match?name=Quercus+alba&kingdom=Plantae&strict=true"
    )
