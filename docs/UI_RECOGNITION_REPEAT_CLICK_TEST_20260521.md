# MacroPilot UI 识别与重复点击测试报告

时间：2026-05-21 22:39-22:53  
测试范围：哔哩哔哩、拼多多、虎扑  
测试方式：只通过 MacroPilot app-side API 触发手机端 `API_PROBE_APP_UI_GRAPH`，ADB 仅用于状态检查、广播触发、拉取报告和截图；没有用 ADB 手动点击目标 App UI。

## 总结

| App | API 结果 | 图谱状态 | 页面数 | 有效边 | 底部分屏打开 | 图形化候选 | 顶部入口 | 侧边入口 | 结论 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 哔哩哔哩 | STARTED | PARTIAL_TIMEOUT | 16 | 18 | 5/5 | 234 | 124 | 151 | 可用但超时，重复入口跳过生效 |
| 拼多多 | STARTED | SUCCESS | 14 | 14 | 5/5 | 753 | 241 | 244 | 通过，底部分屏和图形化入口都被探测 |
| 虎扑 | STARTED 后首次 FAILED_BUSY，单独重跑 | PARTIAL_TIMEOUT | 7 | 7 | 5/5 | 487 | 144 | 148 | 可用但超时；并发请求没有排队，需补队列 |

当前设备状态：`serviceConnected=true`，`nodeReadability=A`，`inputCapability=NONE`。所以本轮验证的是 UI 图谱读取、底部分屏、图形化入口、新页面变化检测、重复点击规避；不验证输入类复杂任务。

## 子任务结果

| # | 子任务 | 具体操作/API | 结果 | 证据 |
|---:|---|---|---|---|
| 1 | 确认设备在线 | `adb devices` | PASS：设备 `373bb463` 在线 | 终端输出：`373bb463 device` |
| 2 | 确认 MacroPilot 无障碍 | `API_GET_STATUS` + `dumpsys accessibility` | PASS：`serviceConnected=true`，Bound service 为 `MacroPilot Runtime` | API 状态输出显示 `nodeReadability=A` |
| 3 | 拉取哔哩哔哩上轮探测产物 | `run-as com.macropilot.app cat ...` | PASS：已拉取 state/report/graph/screenshot | `D:\controlphone\MacroPilot\test_artifacts\ui_repeat_click_20260521\bili_*_raw.*` |
| 4 | 纠正证据拉取方式 | 从 PowerShell `>` 改为 `cmd /c adb exec-out ... > file` | PASS：避免 JSON 被 UTF-16 污染、PNG 被当文本写坏 | 原始证据文件后缀为 `_raw` |
| 5 | 触发拼多多 UI 图谱探测 | `API_PROBE_APP_UI_GRAPH packageName=com.xunmeng.pinduoduo` | PASS：API 立即返回 `STARTED`，前台 Service 执行 | `pdd_probe_state_raw.json` |
| 6 | 触发虎扑并发探测 | 在拼多多运行时触发 `com.hupu.games` | BLOCKED：返回 `FAILED_BUSY`，说明服务保护生效但没有队列 | `hupu_probe_state_raw.json` |
| 7 | 分析拼多多结果 | 拉取 `app_ui_explore_report` 和 `app_ui_graph` | PASS：`SUCCESS`，14 页、14 边、5 个底部分屏全打开 | `pdd_explore_report_raw.json`、`pdd_app_ui_graph_raw.json` |
| 8 | 单独补跑虎扑 | 等拼多多完成后再次触发虎扑 | PARTIAL：生成可用图谱，但 80 秒超时 | `hupu_probe_state_single_raw.json`、`hupu_explore_report_raw.json` |
| 9 | 检查 ANR/崩溃 | `logcat` 查 `ANR/FATAL/AppUiProbeService` | PASS：本轮未见 MacroPilot ANR 或 FATAL | logcat 只见系统/第三方噪声 |
| 10 | 检查重复点击 | 分析 edges 和 events | PASS：三个 App 所有边 `changed=true`，低变化边 0；哔哩哔哩有 11 次 `skip_primary_tab_surface_repeat` | 三个 `*_explore_report_raw.json` |

## 哔哩哔哩明细

触发记录：`app_ui_probe_tv.danmaku.bili_1779374344699`  
状态：`PARTIAL_TIMEOUT`，不是成功伪报。  
图谱：`D:\controlphone\MacroPilot\test_artifacts\ui_repeat_click_20260521\bili_app_ui_graph_raw.json`  
探索报告：`D:\controlphone\MacroPilot\test_artifacts\ui_repeat_click_20260521\bili_explore_report_raw.json`  
截图：`D:\controlphone\MacroPilot\test_artifacts\ui_repeat_click_20260521\bili_screenshot_raw.png`

具体结果：

| 检查项 | 结果 |
|---|---|
| 页面数 | 16 |
| 有效边 | 18 |
| `changed=true` 边 | 18/18 |
| 低变化边 | 0 |
| 底部分屏尝试/打开 | 5/5 |
| 打开的底部分屏 | 首页、动态/关注、发布、会员购、我的 |
| 图形化候选 | 234 |
| 顶部入口候选 | 124 |
| 侧边入口候选 | 151 |
| 输入框候选 | 9 |
| 入口类别 | `primary_bottom_tab=5`，`primary_tab_surface_entry=13` |
| 重复入口规避 | 有 11 次 `skip_primary_tab_surface_repeat` |
| 失败/限制 | 90 秒预算内未完整结束，状态为 `PARTIAL_TIMEOUT` |

结论：哔哩哔哩已经不再只重复打开私信或单一入口；它打开了底部 5 个主分屏，并探测了关注+、会员购、我的等页面入口。仍需优化超时预算和候选排序，减少在发布页/视频页内重复恢复 App 的耗时。

## 拼多多明细

触发记录：`app_ui_probe_com.xunmeng.pinduoduo_1779374757028`  
状态：`SUCCESS`。  
图谱：`D:\controlphone\MacroPilot\test_artifacts\ui_repeat_click_20260521\pdd_app_ui_graph_raw.json`  
探索报告：`D:\controlphone\MacroPilot\test_artifacts\ui_repeat_click_20260521\pdd_explore_report_raw.json`  
截图：`D:\controlphone\MacroPilot\test_artifacts\ui_repeat_click_20260521\pdd_screenshot_1_raw.png`

具体结果：

| 检查项 | 结果 |
|---|---|
| 页面数 | 14 |
| 有效边 | 14 |
| `changed=true` 边 | 14/14 |
| 低变化边 | 0 |
| 底部分屏尝试/打开 | 5/5 |
| 打开的底部分屏 | 首页、bottom_tab_3、提前优惠、聊天183、个人中心 |
| 图形化候选 | 753 |
| 顶部入口候选 | 241 |
| 侧边入口候选 | 244 |
| 输入框候选 | 1 |
| 入口类别 | `primary_bottom_tab=5`，`primary_tab_surface_entry=9` |
| 图形化入口样例 | 多多买菜、搜索、多个 `graphical_ui_*` 网格小图标 |
| 失败/限制 | 底部第二个标签文字识别成 `bottom_tab_3`，说明图标-only tab 仍需更好的语义命名 |

结论：拼多多本轮是通过样本。它能扫到底部 tab、首页图标网格、搜索顶部入口、个人中心等。问题不是“点不到图形 UI”，而是图标-only 元素需要后续用 OCR/视觉模型补语义。

## 虎扑明细

首次触发记录：`app_ui_probe_com.hupu.games_1779374757251`  
首次状态：`FAILED_BUSY`，因为拼多多 probe 正在运行。  
单独补跑记录：`app_ui_probe_com.hupu.games_1779374935108`  
补跑状态：`PARTIAL_TIMEOUT`。  
图谱：`D:\controlphone\MacroPilot\test_artifacts\ui_repeat_click_20260521\hupu_app_ui_graph_raw2.json`  
探索报告：`D:\controlphone\MacroPilot\test_artifacts\ui_repeat_click_20260521\hupu_explore_report_raw.json`  
截图：`D:\controlphone\MacroPilot\test_artifacts\ui_repeat_click_20260521\hupu_screenshot_1_raw.png`

具体结果：

| 检查项 | 结果 |
|---|---|
| 页面数 | 7 |
| 有效边 | 7 |
| `changed=true` 边 | 7/7 |
| 低变化边 | 0 |
| 底部分屏尝试/打开 | 5/5 |
| 打开的底部分屏 | bottom_tab_1、bottom_tab_2、bottom_tab_3、bottom_tab_5、bottom_tab_6 |
| 图形化候选 | 487 |
| 顶部入口候选 | 144 |
| 侧边入口候选 | 148 |
| 可勾选候选 | 6 |
| 输入框候选 | 0 |
| 入口类别 | `primary_bottom_tab=5`，`primary_tab_surface_entry=2` |
| 失败/限制 | 80 秒超时，只探测到 2 个 tab 内入口；底部 tab 文本未识别出语义名 |

结论：虎扑不是无意义 wait，也不是完全读不到 UI；它读到了 567-731 个节点、5 个底部分屏和搜索顶部入口。但虎扑页面结构更重，当前 80 秒预算只能拿到部分图谱。下一步要增加队列、延长/分片预算，并把 GKD-like 弹窗关闭规则提前注入 `reactive_overlays`，减少广告和提示层消耗。

## 当前确认通过的点

- App-side Service 已替代 BroadcastReceiver 长任务，本轮未复现上次 ANR。
- 手机端自己打开 App、读取节点、保存快照、生成图谱，没有用 ADB 手动点击目标 App UI。
- 底部分屏优先探测已经生效，三个 App 都尝试并打开了 5 个主底部分屏。
- 图形化 UI 已进入候选：哔哩哔哩 234、拼多多 753、虎扑 487。
- 新页面判断使用 `changeScore/changeReason`，三组边都是 `changed=true`，低变化边 0。
- 重复入口跳过生效：哔哩哔哩报告里有 11 次 `skip_primary_tab_surface_repeat`。
- 结果没有假成功：哔哩哔哩和虎扑都明确标记 `PARTIAL_TIMEOUT`。

## 未通过和下一步修复

| 问题 | 影响 | 下一步 |
|---|---|---|
| AppUiProbeService 无队列 | 并发触发第二个 App 会 `FAILED_BUSY` | 增加队列或在 API 层返回明确 `QUEUED`，逐个执行 |
| 图标-only 底部 tab 语义弱 | 拼多多/虎扑出现 `bottom_tab_3` 这类占位名 | 接入 OCR/视觉模型或用 tab 位置+邻近文本做语义回填 |
| 虎扑/哔哩哔哩超时 | 图谱可用但不完整 | 改成分片探测：底部分屏一轮、顶部入口一轮、图形网格一轮，每轮保存增量 |
| 输入能力仍是 NONE | 不能测试发帖/评论/搜索输入类任务 | 用户需启用 MacroPilot IME，或实现无需 IME 的可用输入通道检测与降级报告 |
| 弹窗/广告规则还不够早 | 重 App 会消耗预算 | 把 GKD-like 关闭规则前置到 open_app 后和每次点击后 |

