# MacroPilot Evaluation 005

Date: 2026-05-02
Device: `373bb463`
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Scope

This pass added local coordinate calibration candidates and explicit local sample export controls. It keeps coordinate data out of the Approved Runtime path. No screenshots, VLM, model calls, Fragment fallback, Workflow fallback, or automatic exploration were used.

## Added

- `CoordinateCalibrationStore`
  - saves local CoordinatePack candidates under `files/macro_v2/calibration/coordinates`
  - marks candidates as `CANDIDATE`
  - sets coordinate status to `NEEDS_RECALIBRATION`
  - redacts node text and content descriptions before writing
  - prefers editable/clickable/readable nodes over large focused containers
- Data governance additions:
  - inventory now counts coordinate candidates and local exports
  - delete coordinate candidates
  - delete local export files
  - export training samples only after preview says all samples are redacted
- UI additions:
  - `Save Coord`
  - `Clear Coord`
  - `Clear Exports`
- Debug commands:
  - `DEBUG_SAVE_COORDINATE_CANDIDATE`
  - `DEBUG_DELETE_COORDINATE_CANDIDATES`
  - `DEBUG_EXPORT_TRAINING_SAMPLES`
  - `DEBUG_DELETE_EXPORTS`

## Verification

Build:

```text
BUILD SUCCESSFUL
```

Architecture self-check:

```text
DEBUG_SELF_CHECK passed=6/6
```

Settings regression report: `settings_search_1777703944047.json`

| Runs | Success | Medium | Low | Failed | Timeout | Avg | P95 | Promotion |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 10 | 10 | 0 | 0 | 0 | 0 | 272 ms | 332 ms | APPROVED_A |

Coordinate candidate:

```text
files/macro_v2/calibration/coordinates/com.android.settings/coord_1777703883069.json
```

The saved candidate selected the Settings search `EditText`, not a large container. Its text was redacted to `[TOKEN]`.

Export:

```text
DEBUG_EXPORT_PREVIEW samples=5 redacted=5 personal=5 bytes=205752 allowed=true
DEBUG_EXPORT_TRAINING_SAMPLES file=/data/user/0/com.macropilot.app/files/macro_exports/training_samples_1777703522997.jsonl
```

Inventory:

```text
samples=5 approved=4 factory=10 runLogs=16 coords=1 exports=1 bytes=425395
```

## Boundaries

- Coordinate candidates are not written into Approved AppUiModule.
- Coordinate candidates are not used by Runtime unless later promoted through an explicit module-edit/calibration path.
- Export remains local JSONL only; there is no upload path.

## Next Gate

- Build a visual coordinate calibration editor for selecting a node/candidate intentionally.
- Add retention metadata and age-based cleanup for exports and coordinate candidates.
- Add multi-app capability profiling only after the user confirms target apps and test accounts.
