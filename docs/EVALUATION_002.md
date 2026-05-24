# MacroPilot Evaluation 002

Date: 2026-05-02
Device: `373bb463`
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Scope

This pass tested the first approved, low-risk skill path. It does not use screenshots or VLM. It uses an approved AppUiModule, approved MacroTemplate, Accessibility node matching, `ACTION_SET_TEXT`, and MacroVerifier.

## Added

- Approved knowledge read/write:
  - `AppUiModuleStore.saveApproved/loadApproved/listApproved`
  - `MacroTemplateStore.saveApproved/loadApproved/listApproved`
- Built-in approved Settings module:
  - package: `com.android.settings`
  - screen: `settings_home`
  - element: `settings_search_input`
- Built-in approved skill:
  - template: `settings_search`
  - atoms: `OpenApp -> EnsureScreen -> TypeText -> Verify`
- Runtime execution v0:
  - `OpenApp`
  - `EnsureScreen`
  - `ClickElement`
  - `TypeText`
  - `WaitFor`
  - `Verify`
- Structured run logs under `files/macro_v2/run_logs`.
- SkillTestRunner:
  - repeated approved-skill runs
  - aggregate success/timeout/confidence counts
  - average and p95 duration
  - failure clusters
  - reports under `files/macro_v2/factory/test_reports`

## Results

| Run | Start State | Query | Status | Confidence | Duration |
| --- | --- | --- | --- | --- | ---: |
| `run_1777699802935` | Settings already foreground | `WLAN` | SUCCESS | HIGH | 47 ms |
| `run_1777699919452` | Launcher/home, Settings task resumed | `BTTEST` | SUCCESS | HIGH | 362 ms |

Both runs verified via Accessibility node state: the editable Settings search node contained the requested query.

## Repeated Test

Report: `settings_search_1777700456724.json`

| Runs | Success | Medium | Low | Failed | Timeout | Avg | P95 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 3 | 3 | 0 | 0 | 0 | 0 | 258 ms | 260 ms |

The repeated test returns home before each run, then opens Settings and writes a fresh query (`MPTEST1..3`).

## Privacy

- The earlier Settings sample was deleted.
- A new redacted Settings sample was saved:
  - `com.android.settings/sample_1777700130071.json`
- Redaction now masks:
  - emails
  - phone-like numbers
  - long numbers
  - mixed account tokens
  - text following privacy labels such as nickname/account/phone/address/name/code

## Limitations

- Three runs are still too few for promotion. PromotionGate requires at least 10 runs.
- The "home start" run resumed an existing Settings task; it was not a full cold Settings reset.
- `SelectItem`, `ScrollUntilVisible`, and recovery policies are still not complete.
- Debug broadcast receiver must remain debug-only or be removed before non-debug distribution.
- Redaction is stronger but still not sufficient for cloud training without a review/export flow.

## Next Gate

Extend `SkillTestRunner` for `settings_search` with repeated runs from:

- Settings already foreground
- Launcher/home
- MacroPilot foreground
- Search field already populated
- Search field cleared
- Wrong Settings subpage
