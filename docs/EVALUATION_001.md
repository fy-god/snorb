# MacroPilot Evaluation 001

Date: 2026-05-02
Device: `373bb463`
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Build And Install

- `:app:assembleDebug`: passed.
- `adb install -r`: passed.
- App launch: passed.
- Accessibility service: enabled through the visible Android permission flow.

## Runtime Red Lines

- Runtime and Factory packages are separate.
- Runtime has no candidate execution path.
- Runtime gate rejects non-approved skills.
- Runtime gate rejects service/root unavailable.
- Runtime gate rejects D capability.
- Runtime gate rejects missing input channel for text tasks.
- Runtime gate rejects unverifiable high side-effect tasks.
- Coordinate fallback is present, but only as an explicit low-confidence path.
- Runtime AI calls remain zero.

## Real Node Capture Results

| Target | Package | Nodes | Clickable | Editable | Scrollable | Level | Input | Verify |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | --- |
| MacroPilot | `com.macropilot.app` | 16 | 3 | 0 | 1 | B | NONE | MEDIUM |
| Android Settings | `com.android.settings` | 85 | 15 | 1 | 2 | A | SET_TEXT | HIGH |

## Sample Store

- Samples are stored under `files/macro_training/raw_samples/{package}/`.
- Current retained samples:
  - `com.macropilot.app/sample_1777698940591.json`
  - `com.android.settings/sample_1777698762833.json`
- Both retained samples have `redactionStatus = basic_redacted`.
- Earlier unredacted bring-up samples were removed.

## Issues Found

- Node Inspector initially captured its own long debug text recursively; fixed with text truncation and compact display labels.
- Redaction is basic regex redaction only. It is not enough for cloud training.
- Local sample storage is private app storage but not encrypted yet.
- Debug broadcast receiver is useful for development sampling but must be removed, guarded, or disabled for non-debug builds.
- UI is intentionally debug-first and currently oversized on this device; it is not a production UX.
- AppUiModuleStore and MacroTemplateStore can write JSON but do not parse it back yet.
- SkillTestRunner, TraceGeneralizer, real TrialRunner execution, and PromotionGate integration are still skeleton-level.

## Next Engineering Steps

1. Add sample retention/delete/export UI before broader data collection.
2. Add AppUiModule JSON parsing and schema migration.
3. Add a hand-authored approved Settings search template as the first non-social test skill.
4. Add real SkillTestRunner with repeated start-state tests.
5. Test WeChat only after adding stronger redaction and a test account/contact boundary.
