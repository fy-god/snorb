# MacroPilot Evaluation 006

Date: 2026-05-02
Device: `373bb463`
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Scope

This pass replaced the rough debug frontend with a native app-style UI centered on applications and their skills. It kept the existing Runtime, Factory, export, calibration, self-check, and history controls, but moved them into clearer pages.

No screenshots, VLM, Fragment fallback, Workflow fallback, runtime AI calls, or automatic exploration were added to the Runtime path.

## UI Changes

- Default page is now `Apps`.
- Bottom navigation has five pages:
  - `主界面`: user command input and current capability profile.
  - `应用`: installed launcher apps, module status, app icons, package/version details, skill counts, and expandable per-app skill controls.
  - `Skill`: Approved skill library grouped by package.
  - `历史`: recent run logs with status, confidence, and duration.
  - `设置`: accessibility, architecture self-check, data export, coordinate candidate save, and cleanup actions.
- App rows now expand in place. Expanded rows show skills under that app and expose add/delete/sample actions.
- Apps page now has search/filter by app label or package name.
- Apps page scope tabs are clickable:
  - `主机`: all launchable apps.
  - `模块`: apps with Approved AppUiModule.
  - `Skill`: apps with Approved skills.
- Skill cards are expandable and show package, params, final verification, side effects, idempotency, and atom sequence.
- `+ 添加` on arbitrary apps creates a Factory candidate request with `PENDING_RECORDING`; it does not create an Approved Runtime skill.
- Settings examples can still be seeded from the UI.
- Dark status bar and navigation bar now match the app surface.

Screenshots captured:

```text
D:\controlphone\MacroPilot\macropilot_ui_v2.png
D:\controlphone\MacroPilot\macropilot_ui_skill.png
D:\controlphone\MacroPilot\macropilot_ui_settings.png
D:\controlphone\MacroPilot\macropilot_final_apps.png
D:\controlphone\MacroPilot\macropilot_apps_filter.png
D:\controlphone\MacroPilot\macropilot_module_click.png
D:\controlphone\MacroPilot\macropilot_skill_filter_click.png
D:\controlphone\MacroPilot\macropilot_skill_detail_click.png
```

## Verification

Build:

```text
BUILD SUCCESSFUL
```

Install and launch:

```text
adb -s 373bb463 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s 373bb463 shell am start -n com.macropilot.app/.MainActivity
```

Architecture self-check:

```text
DEBUG_SELF_CHECK passed=6/6
```

Initial Settings regression while Accessibility was disabled:

```text
success=0/10
promotion=REJECTED
```

This is expected and desirable: the Runtime/Factory gates rejected execution instead of producing false success without a node tree.

After re-enabling MacroPilot AccessibilityService through ADB, Settings regression passed:

```text
DEBUG_RUN_SETTINGS_TESTS saved .../settings_search_1777707466341.json
success=10/10
p95=281
promotion=APPROVED_A
approved=true
reason=A-level promotion passed
```

After adding Apps search/filter and candidate requests, regression still passed:

```text
DEBUG_RUN_SETTINGS_TESTS saved .../settings_search_1777709017739.json
success=10/10
p95=318
promotion=APPROVED_A
approved=true
```

After fixing module/Skill clickability and expandable Skill details, regression still passed:

```text
DEBUG_RUN_SETTINGS_TESTS saved .../settings_search_1777710445604.json
success=10/10
p95=326
promotion=APPROVED_A
approved=true
DEBUG_SELF_CHECK passed=6/6
```

Candidate add behavior:

```text
files/macro_v2/factory/candidate_skills/candidate_request_1777708654347.json
type=SkillCandidateRequest
packageName=com.android.stk
status=PENDING_RECORDING
```

No `AndroidRuntime` or `FATAL EXCEPTION` entries appeared in the filtered log output during the UI pass.

## Current Limits

- The `+ 添加` action now creates a candidate request, but a full manual skill editor and recorder-driven candidate flow are still pending.
- The visual coordinate calibration editor is still pending; current coordinate saving remains a local redacted candidate capture.

## Next Gate

- Build the manual skill/candidate creation screen.
- Add visual coordinate calibration.
- Extend capability profiling to WeChat and one WebView/browser app with redacted samples only.
