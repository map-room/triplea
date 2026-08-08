# Third-party game data — provenance

This directory ships TripleA game-data XML that the AI sidecar parses to give
`ProAi` a rules-accurate board per edition (`CanonicalGameDataRegistry`). These
files are vendored from third-party TripleA map packages, not authored here.

## WW2v3-1941.xml (Anniversary / `gameDataKey: ww2v3_1941`)

- **Source:** [`triplea-maps/world_war_ii_v3`](https://github.com/triplea-maps/world_war_ii_v3)
  (branch `master`), file `map/games/WW2v3-1941.xml`.
- **In-game identification:** `<info name="World War II v3 1941" version="1.7"/>`.
- **Attribution**, from the file's own trailing comment:
  ```
  WW2V2 XML by Zero Pilot 9.08
  Triplelk Jason Clark - baseline
  Zero Pilot Mike McCaughey - integration
  ComradeKev - custom code and rules
  Seidelin - playtesting
  Veqryn - Minor updates for engine 1.2.5.x, and again for engine 1.3.x.x
  ```
- **License:** none found. The upstream repository has no `LICENSE` file
  (confirmed via the GitHub API — `license: null`) and its `README.md` is a
  one-line stub with no grant. Flagged unverified in map-room's
  `data-sources/anniversary/README.md` (#3376) and in map-room issue #3470.
- **Why it ships anyway:** map-room's owner made an explicit decision (recorded
  on map-room#3470) to stop blocking the Anniversary AI feature on resolving
  this, and to ship the file with its provenance recorded here instead of
  silently vendoring it. This note is that record — it is not a license
  clearance, and redistribution risk has not been resolved, only made
  traceable.

## ww2global40_2nd_edition.xml (Global 1940 / `gameDataKey: ww2global40_2nd_edition`)

Added by #1734, predates this note. In-game identification: `<info name="World
War II Global 1940 2nd Edition"/>`, no author attribution embedded in the file
and no upstream source recorded anywhere in this repo or map-room's. Same
unresolved-provenance status as the file above — not addressed by this change,
called out here only so this NOTICE doesn't imply otherwise.
