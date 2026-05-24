# MacroPilot Evaluation 003

Date: 2026-05-02
Device: `373bb463`
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Scope

This pass verified the red-line gates and the first PromotionGate threshold. Runtime still uses Accessibility nodes and local verification only. No screenshots, VLM, model calls, Fragment fallback, Workflow fallback, or automatic exploration were used.

## Added

- `ArchitectureSelfCheck`
  - rejects Candidate skills in Runtime
  - rejects capability D
  - rejects missing text-input channel
  - rejects implicit capability C execution
  - rejects dangerous templates at PromotionGate
  - rejects promotion with fewer than 10 test runs
- Data governance controls:
  - inventory
  - delete training samples
  - delete Factory artifacts
  - delete run logs
- Debug-only broadcasts for governance and self-check commands.
- Native UI buttons for inventory, cleanup, and self-check.

## Architecture Self-Check

Command:

```text
adb -s 373bb463 shell am broadcast -a com.macropilot.app.DEBUG_SELF_CHECK -n com.macropilot.app/.debug.DebugCommandReceiver
```

Result:

| Check | Result |
| --- | --- |
| Runtime rejects Candidate skills | PASS |
| Runtime rejects capability D | PASS |
| Runtime rejects missing text-input channel | PASS |
| Runtime rejects implicit capability C | PASS |
| PromotionGate rejects dangerous templates | PASS |
| PromotionGate requires at least 10 runs | PASS |

Overall: `6/6`

## Settings Skill Regression

Command:

```text
adb -s 373bb463 shell am broadcast -a com.macropilot.app.DEBUG_RUN_SETTINGS_TESTS --ei count 10 -n com.macropilot.app/.debug.DebugCommandReceiver
```

Report: `settings_search_1777701341367.json`

| Runs | Success | Medium | Low | Failed | Timeout | Avg | P95 | Promotion |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 10 | 10 | 0 | 0 | 0 | 0 | 250 ms | 293 ms | APPROVED_A |

The PromotionGate decision is stored inside the Factory test report. It does not create or overwrite an Approved skill.

## File Isolation

Device inventory after the run:

```text
samples=2 approved=2 factory=3 runLogs=2 bytes=58724
```

Device files:

```text
files/macro_training/raw_samples/com.android.settings/sample_1777700130071.json
files/macro_training/raw_samples/com.macropilot.app/sample_1777698940591.json
files/macro_v2/approved/apps/com.android.settings/app.json
files/macro_v2/approved/skills/settings_search.json
files/macro_v2/factory/test_reports/settings_search_1777700456724.json
files/macro_v2/factory/test_reports/settings_search_1777701036559.json
files/macro_v2/factory/test_reports/settings_search_1777701341367.json
files/macro_v2/run_logs/run_1777699802935.json
files/macro_v2/run_logs/run_1777699919452.json
```

Approved remains exactly two files: one AppUiModule and one MacroTemplate.

## Limitations

- The 10-run test covers home-start repetition, but not yet every start state.
- RecoveryPolicy is still basic; wrong-subpage recovery is not complete.
- `SelectItem` and `ScrollUntilVisible` are still placeholders.
- Data export preview/confirmation is not implemented yet.
- Factory cleanup is available, but retention policy is still manual.

## Next Gate

- Implement explicit export preview/confirmation.
- Add start-state tests for wrong page, already-open Settings, already-filled search field, and MacroPilot foreground.
- Implement `SelectItem`, `ScrollUntilVisible`, and goal-oriented RecoveryPolicy before any messaging task.
