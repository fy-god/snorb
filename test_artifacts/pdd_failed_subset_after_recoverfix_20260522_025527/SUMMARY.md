# PDD Failed-Subset Retest Summary

Request: `pdd_failed_subset_after_recoverfix_20260522_025527`

Result: `SUCCESS`

Retest scope: only the 8 PDD tasks that failed or timed out in the previous run.

Counts:
- taskCount: 8
- success: 8
- failed: 0
- blocked: 0
- passRateText: 100.0%

Code fixes verified:
- `AppUiExplorer` now restores the target package if capture detects it has left PDD, and skips stale coordinate taps instead of tapping the launcher/system UI.
- `AppUiExplorer` now accepts rich PDD UI graphs as usable when enough screens, edges, labels, and candidates were collected even if bottom-tab visual change is low.
- `PopularAppTaskBatchService` and `MacroPilotApiReceiver` now support `taskIds`, so failed tasks can be retested directly.

Evidence directory:
`D:\controlphone\MacroPilot\test_artifacts\pdd_failed_subset_after_recoverfix_20260522_025527_evidence`

Evidence files:
- `1-1_SUCCESS_UI_GRAPH_EXPLORED_com.xunmeng.pinduoduo_pdd_coupon_probe.png`
- `1-2_SUCCESS_UI_GRAPH_EXPLORED_com.xunmeng.pinduoduo_pdd_duoduo_farm_probe.png`
- `1-3_SUCCESS_UI_GRAPH_EXPLORED_com.xunmeng.pinduoduo_pdd_coupon_center_probe.png`
- `1-4_SUCCESS_UI_GRAPH_EXPLORED_com.xunmeng.pinduoduo_pdd_group_buy_pre_submit.png`
- `1-5_SUCCESS_UI_GRAPH_EXPLORED_com.xunmeng.pinduoduo_pdd_order_status_probe.png`
- `1-6_SUCCESS_UI_GRAPH_EXPLORED_com.xunmeng.pinduoduo_pdd_chat_customer_service.png`
- `1-7_SUCCESS_UI_GRAPH_EXPLORED_com.xunmeng.pinduoduo_pdd_bargain_activity_probe.png`
- `1-8_SUCCESS_UI_GRAPH_EXPLORED_com.xunmeng.pinduoduo_pdd_buy_vegetables_probe.png`

Final report:
`D:\controlphone\MacroPilot\test_artifacts\pdd_failed_subset_after_recoverfix_20260522_025527\report_final.json`

