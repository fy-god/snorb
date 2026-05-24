# MacroPilot Evaluation 004

Date: 2026-05-02
Device: `373bb463`
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Scope

This pass filled Runtime atom gaps that were still placeholders and verified that the existing approved Settings skill did not regress. No screenshots, VLM, model calls, Fragment fallback, Workflow fallback, or automatic exploration were used.

## Added

- `SelectItem`
  - resolves visible text from a parameter
  - waits until the text is visible
  - clicks the matching node or a clickable parent
- `ScrollUntilVisible`
  - resolves target text from a parameter
  - scrolls a specified list element when available
  - otherwise scrolls the first readable scrollable node
  - verifies target text through Accessibility nodes
- `RecoveryPolicy`
  - recovers toward a target screen using per-screen `recoverActions`
  - supports `Back`, `ClickElement`, and `WaitForText`
- Knowledge persistence fixes:
  - `UiScreenModule.recoverActions` now serializes and deserializes
  - `UiElementSpec.coordinatePack` now serializes and deserializes
  - `ScrollUntilVisible` now supports optional `listElementId`
  - `AppUiModule.packageAliases` now serializes and deserializes
- Debug validation commands:
  - `DEBUG_RUN_SETTINGS_RECOVERY_TEST`
  - `DEBUG_RUN_SETTINGS_SCROLL_TEST`
  - `DEBUG_RUN_SETTINGS_SELECT_TEST`

## Verification

Build:

```text
BUILD SUCCESSFUL
```

Architecture self-check:

```text
DEBUG_SELF_CHECK passed=6/6
```

Settings recovery test:

```text
DEBUG_RUN_SETTINGS_RECOVERY_TEST prep=true:ACTION_BLUETOOTH_SETTINGS sent result=SUCCESS confidence=HIGH
```

Settings scroll test:

```text
DEBUG_RUN_SETTINGS_SCROLL_TEST target=显示与亮度 result=SUCCESS confidence=HIGH
```

Settings select test:

```text
DEBUG_RUN_SETTINGS_SELECT_TEST item=WLAN result=SUCCESS confidence=HIGH
```

## Regression Found And Fixed

After selecting `WLAN`, the foreground package became `com.oplus.wirelesssettings`, not `com.android.settings`. This caused `OpenApp(com.android.settings)` to time out. The fix was to add `AppUiModule.packageAliases` and teach both `OpenApp` verification and `ScreenClassifier` to accept approved package aliases.

The Settings home signature was also too loose: a Settings subpage containing `WLAN` or `蓝牙` could be misclassified as `settings_home`. The fix was to require the Settings search view resource and raise `settings_home` `minScore` to `0.85`.

Failed report before the fix:

```text
settings_search_1777702284324.json: success=0/10, reason=OpenApp timed out for com.android.settings
```

Passing report after the fix: `settings_search_1777702604310.json`

Final report after adding export preview: `settings_search_1777702755515.json`

| Runs | Success | Medium | Low | Failed | Timeout | Avg | P95 | Promotion |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 10 | 10 | 0 | 0 | 0 | 0 | 282 ms | 317 ms | APPROVED_A |

Export preview:

```text
DEBUG_EXPORT_PREVIEW samples=5 redacted=5 personal=5 bytes=205752 allowed=true
```

Inventory after the run:

```text
samples=5 approved=4 factory=9 runLogs=16 bytes=218195
```

Approved now contains one AppUiModule and three low-risk Settings MacroTemplates:

- `settings_search`
- `settings_scroll_visible`
- `settings_select_visible`

Promotion decisions and repeated-run outputs still stay in Factory reports.

## Limitations

- Recovery currently executes only configured screen actions; it does not do broad uncontrolled backtracking.
- Coordinate fallback is persisted, but there is not yet a calibration UI.
- Export is local JSONL only; there is still no cloud upload or external share flow.

## Next Gate

- Add a calibration UI for CoordinatePack.
- Add retention metadata and cleanup policy for exports.
- Add multi-app capability profiling after data export controls are in place.
