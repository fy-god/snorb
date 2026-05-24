# MacroPilot 20 App 深度 UI 探索复测报告

更新时间：2026-05-20 09:55 Asia/Shanghai

## 当前结论

- 已完成代码改造并通过 Debug APK 编译。
- 本轮改造重点是让 App 端自己做深层 UI 探索，不用 ADB 手动点目标 App：
  - 首页、子页面都执行左右/上下滑动探索。
  - 子页面不再简单重开 App 打断上下文，改为用反向滑动保持当前页面。
  - 单 App 探索预算提升到 `maxScreens=120`、`maxClicks=100`、`maxSwipeActions=100`。
  - 新增 `checkable` 节点采样，用于识别协议小圆圈、复选框、QQ 快捷登录前的同意协议。
  - UI 图谱和批处理报告新增 `checkableCount`、`checkableCandidates`、`checkableCandidateCount`。
- 未完成实机 20 App 复跑：当前主机 `adb devices -l` 为空，Windows 设备列表和 `adb mdns services` 也没有发现 Android/ADB 设备。
- 因设备不可见，无法安装新版 APK、无法触发手机端批处理、无法拉取新的 20 App 图谱和截图证据。本报告不标记实测通过。

## 代码改动

- `app/src/main/java/com/macropilot/app/model/UiModels.kt`
  - `NodeSample` 新增 `checkable` 字段。
- `app/src/main/java/com/macropilot/app/automation/MacroPilotAccessibilityService.kt`
  - 无障碍节点采样接入 `node.isCheckable`。
- `app/src/main/java/com/macropilot/app/factory/AppUiExplorer.kt`
  - 深层页面也执行滑动探索。
  - 新增 `checkableCandidates`。
  - 候选排序增加 `agreement/checkable/login_entry` 权重。
  - UI 图谱质量统计增加 checkable 候选数量。
- `app/src/main/java/com/macropilot/app/factory/ThirdPartyAppSkillSubsetBatcher.kt`
  - 批处理探索预算提升到 120 屏、100 次点击、100 次滑动。
  - 批处理报告透出 checkable 候选。
- `app/src/main/java/com/macropilot/app/factory/SkillFactorySelfTest.kt`
  - 自测样本补齐 `checkable` 字段。

## 编译验证

- 命令：`:app:assembleDebug`
- 结果：`BUILD SUCCESSFUL`
- APK：`D:\controlphone\MacroPilot\app\build\outputs\apk\debug\app-debug.apk`

## 实机阻塞

- `adb devices -l` 输出为空。
- `adb mdns services` 没有发现无线调试服务。
- Windows 当前未枚举到 Android/ADB/MTP 设备。

设备恢复后，继续执行同一套 20 App app-side batch，不用 ADB 手动点目标 App UI。
