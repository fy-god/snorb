# MacroPilot 20 App 批量测试报告

时间：2026-05-21 17:10-17:23
请求 ID：popular_app_task_batch_1779354836360
AI：DASHSCOPE_COMPAT_CHAT / qwen3-vl-flash
执行方式：手机端 MacroPilot API 执行；ADB 仅用于触发、读取报告、拉截图。

## 总结

最终状态：PARTIAL_DEFERRED

- App 数：20
- 任务数：20
- 执行尝试：31
- 成功：4
- 阻塞：4
- 延后单独执行：12
- 失败：0
- 重试：12

这次不是 20 个都跑通。回退包里的 20 App 批处理逻辑会把“搜索/输入/评论/发布类长流程”登记为 `PENDING_INPUT_FLOW_SINGLE_TASK`，要求后续用 `API_RUN_APP_SIDE_INSTRUCTION` 单独执行，避免一个长流程拖死整个批次。

## 成功项截图

### 虎扑：SUCCESS_UI_GRAPH_EXPLORED

- 图谱：9 screens / 20 edges
- 报告：success_hupu_report.json
- 截图：success_hupu.png

![虎扑成功截图](D:/controlphone/MacroPilot/popular20_qwen_evidence_20260521_001/success_hupu.png)

### QQ：SUCCESS_UI_GRAPH_EXPLORED

- 图谱：10 screens / 28 edges
- 报告：success_qq_report.json
- 说明：本轮有 UI 图谱 JSON，但没有同目录 PNG；这里放轮询过程截图。

![QQ过程截图](D:/controlphone/MacroPilot/popular20_qwen_evidence_20260521_001/success_qq_process.png)

### 微信：SUCCESS_UI_GRAPH_EXPLORED

- 图谱：5 screens / 4 edges
- 报告：success_wechat_report.json
- 截图：success_wechat.png

![微信成功截图](D:/controlphone/MacroPilot/popular20_qwen_evidence_20260521_001/success_wechat.png)

### 钉钉：SUCCESS_UI_GRAPH_EXPLORED

- 图谱：14 screens / 32 edges
- 报告：success_dingtalk_report.json
- 说明：本轮有 UI 图谱 JSON，但没有同目录 PNG；这里放最终过程截图。

![钉钉过程截图](D:/controlphone/MacroPilot/popular20_qwen_evidence_20260521_001/success_dingtalk_process.png)

## 错误/阻塞截图

当前错误/未完成现场截图：

![错误现场截图](D:/controlphone/MacroPilot/popular20_qwen_evidence_20260521_001/error_current_dingtalk.png)

过程截图：

![过程1](D:/controlphone/MacroPilot/popular20_qwen_evidence_20260521_001/screen_poll1.png)
![过程2](D:/controlphone/MacroPilot/popular20_qwen_evidence_20260521_001/screen_poll2.png)
![过程3](D:/controlphone/MacroPilot/popular20_qwen_evidence_20260521_001/screen_poll3.png)
![过程4](D:/controlphone/MacroPilot/popular20_qwen_evidence_20260521_001/screen_poll4.png)

## 20 个 App 结果

| App | 状态 | 分析 |
|---|---|---|
| 哔哩哔哩 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索并打开视频属于输入长流程，批处理只登记计划，需单独跑 API_RUN_APP_SIDE_INSTRUCTION。 |
| 小红书 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索输入长流程被延后。 |
| 抖音 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索输入长流程被延后。 |
| 虎扑 | SUCCESS_UI_GRAPH_EXPLORED | 成功读取 UI 图谱，9 屏 20 边。 |
| 微博 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索输入长流程被延后。 |
| QQ | SUCCESS_UI_GRAPH_EXPLORED | 成功读取登录/入口 UI 图谱，10 屏 28 边。 |
| 微信 | SUCCESS_UI_GRAPH_EXPLORED | 成功读取底部页面 UI 图谱，5 屏 4 边。 |
| 美团 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索输入长流程被延后。 |
| 大众点评 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索输入长流程被延后。 |
| 淘宝 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索商品属于输入长流程，被延后。 |
| 京东 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索商品属于输入长流程，被延后。 |
| 拼多多 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索商品属于输入长流程，被延后。 |
| 知乎 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索输入长流程被延后。 |
| 网易云音乐 | PENDING_INPUT_FLOW_SINGLE_TASK | 搜索输入长流程被延后。 |
| 百度地图 | PENDING_INPUT_FLOW_SINGLE_TASK | 地点搜索输入长流程被延后。 |
| 高德地图 | BLOCKED_APP_NOT_INSTALLED | 未安装或包名未匹配。 |
| 支付宝 | BLOCKED_HIGH_RISK_GUARD_ONLY | 支付类高风险入口，只生成 guard 报告，不执行付款相关操作。 |
| 云闪付 | BLOCKED_HIGH_RISK_GUARD_ONLY | 支付类高风险入口，只生成 guard 报告。 |
| 同花顺 | BLOCKED_HIGH_RISK_GUARD_ONLY | 金融/交易相关高风险任务，只生成 guard 报告，不交易。 |
| 钉钉 | SUCCESS_UI_GRAPH_EXPLORED | 成功读取登录/入口 UI 图谱，14 屏 32 边。 |

## 文件

- 完整状态：popular20_final_state.json
- App 状态摘要：popular20_app_status_summary.json
- 任务目录：popular20_catalog.json
- 成功报告：success_hupu_report.json / success_qq_report.json / success_wechat_report.json / success_dingtalk_report.json

## 结论

这轮证明：手机端无障碍、输入法、Qwen API、UI 图谱读取链路能跑；但回退包里的批量测试不会真正执行搜索/输入长流程，只会登记为单任务。这不是模型接口问题，是批处理策略限制。

下一步如果要验证哔哩哔哩搜索并打开第一个视频、小红书搜索、美团搜索等，应该逐个调用 `API_RUN_APP_SIDE_INSTRUCTION`，每个任务单独产出 flow report 和截图，而不是塞进 20 App 批处理。
