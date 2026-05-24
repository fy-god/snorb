# MacroPilot 自主工厂复测报告

更新时间：2026-05-20 21:50 Asia/Shanghai

## 结论

本轮已完成一处关键纠偏：`PopularAppTaskBatchService` 不再把搜索、打开第一个视频、草稿、评论草稿等复杂任务直接登记为 `PENDING_INPUT_FLOW_SINGLE_TASK`。这些任务现在会进入手机端 `AppSideInstructionFlowRunner`，由 app-side AI / Skill JSON / 无障碍流程真实执行；如果设备不满足条件，则写出明确 blocker，不再假成功。

当前手机仍处于锁屏息屏状态：

```text
serviceConnected=true
interactive=false
keyguardLocked=true
currentPackage=com.android.systemui
inputCapability=NONE
```

因此本轮没有控制目标 App，也没有用 ADB 手动点击目标 App UI。ADB 只用于安装 APK、触发 MacroPilot API、读取状态、拉报告和截图。

## 本轮代码改动

- 修改 `D:\controlphone\MacroPilot\app\src\main\java\com\macropilot\app\factory\PopularAppTaskBatchService.kt`
- 删除复杂任务的 PENDING 短路：
  - `runDeferredInputFlowTask`
  - `isInputOrLongFlowTask`
  - `runOneTask` 中对上述短路的调用
- 结果：
  - `search`
  - `search_open`
  - `draft_create`
  - `comment_draft`
  - `route_probe`

这些任务不再只登记计划，而是进入 `AppSideInstructionFlowRunner.runInstructionFlow(...)`。

## 构建与安装

```text
BUILD SUCCESSFUL
adb install -r app-debug.apk -> Success
```

本机 Gradle wrapper 只有 `gradlew`，我用临时 JDK shim 跑通了构建。

## API 响应可见性

`API_GET_STATUS` 已经会写入手机端 flow report：

```text
/data/user/0/com.macropilot.app/files/macro_v2/factory/flow_reports/com.macropilot.app/api_response_com.macropilot.app.api_get_status_1779284219742.json
```

这说明“用户看不到 API 反馈”的底层报告通路已经接上。

## 小批次验证

触发请求：

```text
API_RUN_POPULAR_APP_TASK_BATCH
requestId=verify_complex_tasks_not_pending_1779285000000
packages=tv.danmaku.bili,com.xingin.xhs
limitApps=2
tasksPerApp=2
```

结果：

```text
status=FAILED_DEVICE_NOT_READY
executed=0
failed=4
blocker=手机仍在锁屏，不能执行目标 App 任务。
```

这次验证的重点不是跑通目标 App，而是确认复杂任务不会再被直接挂成 PENDING。报告里没有出现 `PENDING_INPUT_FLOW_SINGLE_TASK`。

## 本机证据文件

- `D:\controlphone\MacroPilot\verify_complex_tasks_not_pending_1779285000000.json`
- `D:\controlphone\MacroPilot\verify_complex_tasks_not_pending_1779285000000_catalog.json`
- `D:\controlphone\MacroPilot\verify_complex_tasks_not_pending_screen.png`

## 仍未通过项

1. 设备仍锁屏/息屏，`interactive=false`，手机端不能控制目标 App。
2. 当前默认输入法不是 MacroPilot，`inputCapability=NONE`，涉及输入的任务会被 app-side 前置门挡住。
3. 20 App 全量复杂任务需要在手机解锁、屏幕可交互、输入法可用后重新跑。

## 下一步验收方式

手机解锁并切到 MacroPilot 输入法后，重新触发：

```text
API_RUN_POPULAR_APP_TASK_BATCH
limitApps=20
tasksPerApp=10
```

验收标准：

- Bilibili `bili_search_open_first_video` 必须进入视频播放页，不能只停在搜索结果页。
- 小红书、微博、虎扑、抖音等复杂任务必须产出 app-side flow report。
- 每个任务失败时必须写 `readiness`、`failedStep`、`currentUi`、`aiDiagnosis` 或明确 blocker。
- 不允许 Wait/OpenApp 被记为业务成功。
- 支付、密码、下单、交易类任务只能生成 guard report，不能输入密码或提交交易。
