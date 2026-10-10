"""Self-review fix for R1h: a toxic blocker that fills only the reticle shouldn't be diluted by the full-frame mean.

Targets keep R1h's mean of reticle and full-frame scores; each blocker scores the larger of that mean and its reticle
score alone. Tunes the margin on TUNE (boundary plus 0.01, as R1h did) and scores TEST once.

Run: uv run --project pipeline python -I docs/results/day-5/target_pass/blocker_guard.py
"""

import sys
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import numpy as np
import rules as r


class GuardScorer(r.Scorer):
    """R1h with blockers floored at their reticle score."""

    def per_view(self, p: dict, rule: r.Rule, competitors: list[int] | None = None) -> tuple[float, str]:
        """Mean for targets; max(mean, reticle) for blockers."""
        if rule.name.startswith("G"):
            vs = self.views(p, rule)
            s = np.mean([p["scores"][v] for v in vs], axis=0)
            s[self.n_e :] = np.maximum(s[self.n_e :], p["scores"]["ret60"][self.n_e :])
            return self.lead(s, rule, competitors)
        return super().per_view(p, rule, competitors)


def main() -> int:
    """Print TUNE and TEST for R1h, the retuned guard, and the guard at R1h's margin."""
    h, tune, _ = r.prepare(r.TUNE_SETS)
    scorer = GuardScorer(h)
    r1h = next(x for x in r.RULES if x.name == "R1h ret+full mean +0.01")
    probe = replace(r1h, name="G0 guard boundary", tau=None)
    boundary = r.boundary(r.evaluate(tune, probe, 0.0, scorer, "warn_b"), 0.001)
    guard = replace(r1h, name="G1 guard +0.01", tau=round(boundary + 0.01, 3), note=f"boundary {boundary:.3f} + 0.01")
    # Retuning lets the guard's own boundary drop the margin; keeping R1h's 0.034 only ever adds blocks.
    kept = replace(r1h, name="G2 guard at 0.034", tau=0.034, note="R1h margin kept; blockers floored at reticle")
    print(f"# guard boundary on TUNE {boundary:.3f}; margin {guard.tau}")
    for split, sets in (("tune", r.TUNE_SETS), ("test", r.TEST_SETS)):
        _, photos, _ = r.prepare(sets)
        picked = (r1h, guard, kept)
        results = [(x, x.tau, r.summary(r.evaluate(photos, x, x.tau, scorer, "warn_b"), x.tau)) for x in picked]
        r.header(split, sets)
        r.table(results, split.upper())
    return 0


if __name__ == "__main__":
    sys.exit(main())
