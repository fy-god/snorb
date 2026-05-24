# MacroPilot Pass Rate Verification 2026-05-21

## Code changes

- Added `BatchReportRates` to write machine-readable and UI-readable percentages into batch summaries.
- Popular app task reports now include `passRatePct`, `failureRatePct`, `blockedRatePct`, `completionRatePct`, `executablePassRatePct`, and text forms like `33.3%`.
- Early-stop reports (`CATALOG_ONLY`, `FAILED_DEVICE_NOT_READY`) now also include percentages instead of raw counts only.
- Popular task readiness no longer blocks the whole batch just because the current screen has no editable node or IME readiness is momentarily false. Input capability is deferred to the input atom.
- Third-party Skill/UI batch reports now include `dryRunPassRateText`, `uiProbeSuccessRateText`, `uiProbeCoverageRateText`, `uiProbeCompletionRateText`, and app config export rate.
- Long-running UI probe events now carry current base percentage fields so the frontend can show progress while exploring.

## Complex task batch verification

Request: `pct_verify_1779377400`

Source report copied to:

- `D:\controlphone\MacroPilot\docs\PERCENT_PASS_RATE_VERIFICATION_20260521.json`

Summary:

| Metric | Value |
|---|---:|
| Total tasks | 3 |
| Success | 1 |
| Failed | 2 |
| Blocked | 0 |
| Executed attempts | 3 |
| Pass rate | 33.3% |
| Failure rate | 66.7% |
| Blocked rate | 0.0% |
| Completion rate | 100.0% |

Task results:

| App | Task | Result | Evidence |
|---|---|---|---|
| Bilibili | `bili_search_open_first_video` | `SUCCESS` | `D:\controlphone\MacroPilot\test_artifacts\pct_verify_1779377400_evidence\1-1_SUCCESS_tv.danmaku.bili_bili_search_open_first_video.png` |
| Hupu | `hupu_close_ads_home` | `PARTIAL_TIMEOUT` | `D:\controlphone\MacroPilot\test_artifacts\pct_verify_1779377400_evidence\2-1_PARTIAL_TIMEOUT_com.hupu.games_hupu_close_ads_home.png` |
| Pinduoduo | `pdd_search_item` | `FAILED_TIMEOUT` | `D:\controlphone\MacroPilot\test_artifacts\pct_verify_1779377400_evidence\3-1_FAILED_TIMEOUT_com.xunmeng.pinduoduo_pdd_search_item.png` |

## Skill JSON / UI graph batch verification

Request: `third_party_batch_1779377921012`

Summary:

| Metric | Value |
|---|---:|
| Apps | 3 |
| Generated Skill JSON | 36 |
| Dry-run passed | 36 |
| Dry-run failed | 0 |
| Dry-run pass rate | 100.0% |
| UI probe attempted | 3 |
| UI probe success | 0 |
| UI probe blocked/partial | 3 |
| UI probe success rate | 0.0% |
| UI probe coverage | 100.0% |
| UI probe completion | 100.0% |
| App config export rate | 100.0% |

## Current blockers to raise pass rate

- Bilibili simple search-open can pass, but Hupu and Pinduoduo still time out on deeper flows.
- UI graph exploration attempts all selected apps, but the success classifier is too strict or the graph quality result marks these runs as blocked/partial even when it collected many nodes. This is why UI probe success rate is currently 0.0% while coverage/completion are 100.0%.
- Next pass-rate improvement should target timeout classification and UI graph quality thresholds, not just more repeated tests.
