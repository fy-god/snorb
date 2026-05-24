# 20 App 批次电脑侧复核报告

源报告：D:\controlphone\MacroPilot\popular20_connectivity_20260521_001.json

## 总览

- 手机端批次状态：PARTIAL
- 手机端统计：success=2, failed=15, blocked=3, executedAttempts=19
- 电脑侧复核：PASS=3, FAIL=14, UNKNOWN=3
- v4pro/AI：jobs=38, success=9, failed=31, HTTP402=28
- 证据：localPngs=65, pulledFiles=1932, failedPulls=3401

## 逐 App 结果

| App | Task | Phone | Computer | AI | Evidence | Main Blocker |
| --- | --- | --- | --- | --- | --- | --- |
| 哔哩哔哩 | bili_search_open_first_video | FAILED_GOAL_UNVERIFIED | FAIL HIGH | jobs 2, 402 2 | png 3, snap 10 | Phone task status is FAILED_GOAL_UNVERIFIED. |
| 小红书 | xhs_search_keyword | FAILED_GOAL_UNVERIFIED | FAIL HIGH | jobs 3, 402 3 | png 8, snap 26 | Phone task status is FAILED_GOAL_UNVERIFIED. |
| 抖音 | douyin_search_keyword | FAILED_GOAL_UNVERIFIED | FAIL HIGH | jobs 2, 402 2 | png 8, snap 24 | Phone task status is FAILED_GOAL_UNVERIFIED. |
| 虎扑 | hupu_close_ads_home | PARTIAL_TIMEOUT | UNKNOWN MEDIUM | jobs 0, 402 0 | png 0, snap 10 | UI graph exploration timed out or was partial. |
| 微博 | weibo_search_hot | FAILED_GOAL_UNVERIFIED | FAIL HIGH | jobs 3, 402 3 | png 8, snap 26 | Phone task status is FAILED_GOAL_UNVERIFIED. |
| QQ | qq_login_state | PARTIAL_TIMEOUT | FAIL MEDIUM | jobs 0, 402 0 | png 0, snap 0 | UI graph exploration timed out or was partial. |
| 微信 | wechat_bottom_tabs_snapshot | SUCCESS_UI_GRAPH_EXPLORED | UNKNOWN MEDIUM | jobs 0, 402 0 | png 0, snap 0 | Phone marked UI graph success but events include FAILED_NO_AFTER_SCREEN. |
| 美团 | meituan_search_food | FAILED_GOAL_UNVERIFIED | FAIL HIGH | jobs 3, 402 3 | png 8, snap 18 | Phone task status is FAILED_GOAL_UNVERIFIED. |
| 大众点评 | dianping_search_restaurant | FAILED_GOAL_UNVERIFIED | FAIL HIGH | jobs 3, 402 3 | png 8, snap 16 | Phone task status is FAILED_GOAL_UNVERIFIED. |
| 淘宝 | taobao_search_item | FAILED_GOAL_UNVERIFIED | FAIL HIGH | jobs 3, 402 3 | png 8, snap 18 | Phone task status is FAILED_GOAL_UNVERIFIED. |
| 京东 | jd_search_item | FAILED_GOAL_UNVERIFIED | FAIL HIGH | jobs 3, 402 3 | png 8, snap 14 | Phone task status is FAILED_GOAL_UNVERIFIED. |
| 拼多多 | pdd_search_item | FAILED_GOAL_UNVERIFIED | FAIL HIGH | jobs 3, 402 3 | png 2, snap 26 | Phone task status is FAILED_GOAL_UNVERIFIED. |
| 知乎 | zhihu_search_question | FAILED_TIMEOUT | FAIL HIGH | jobs 5, 402 3 | png 0, snap 0 | Task timed out before writing a flow report. |
| 网易云音乐 | music_search_song | FAILED_TIMEOUT | FAIL HIGH | jobs 4, 402 0 | png 0, snap 0 | Task timed out before writing a flow report. |
| 百度地图 | baidumap_search_place | SUCCESS | PASS MEDIUM | jobs 2, 402 0 | png 4, snap 24 | Phone flow reports success with goal/verification text and local screenshot evidence. |
| 高德地图 | amap_search_place | BLOCKED_APP_NOT_INSTALLED | UNKNOWN HIGH | jobs 0, 402 0 | png 0, snap 0 | Target app is not installed or package name did not resolve. |
| 支付宝 | alipay_home_icons | BLOCKED_HIGH_RISK_GUARD_ONLY | PASS HIGH | jobs 0, 402 0 | png 0, snap 0 | High-risk payment/trade task was safely blocked. |
| 云闪付 | unionpay_home_icons | BLOCKED_HIGH_RISK_GUARD_ONLY | PASS HIGH | jobs 0, 402 0 | png 0, snap 0 | High-risk payment/trade task was safely blocked. |
| 同花顺 | hexin_search_stock | FAILED_TIMEOUT | FAIL HIGH | jobs 2, 402 0 | png 0, snap 0 | Task timed out before writing a flow report. |
| 钉钉 | dingtalk_login_state | PARTIAL_TIMEOUT | FAIL MEDIUM | jobs 0, 402 0 | png 0, snap 0 | UI graph exploration timed out or was partial. |

## 关键结论

- 这轮确实由 MacroPilot 手机端通过无障碍/输入法/API 执行；ADB 只用于触发、轮询和拉取证据。
- v4pro 已被调用，但多数 AI job 返回 HTTP 402，导致任务退回本地 archetype/旧 Skill 组合，复杂任务大面积失败。
- 批次中存在 Skill JSON、dry-run、app_config、runtime report、UI snapshot 和 PNG 证据，但成功判定仍偏弱，需要继续修目标校验和批次 verifier。
- 支付宝、云闪付等高风险入口按安全策略拦截，这类不能自动输入密码、支付或交易。

