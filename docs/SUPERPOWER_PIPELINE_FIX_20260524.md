# MacroPilot Superpower Pipeline Fix 2026-05-24

## 结论

本轮没有继续做单个 App 的坐标补丁，而是按 superpower 路线修共享中枢：

1. App-side flow report 现在写入 `finalOutcome`，包含最终状态、目标校验、最后一步、最终 UI 快照。
2. Popular 20 App 单任务报告现在写入 `outcome`，把 blockerType、nextActionHint、lastStep 从 flow report 里抽出来给前端/报告展示。
3. 关键复杂任务校验函数补了结构化 blocker 和下一步建议：
   - Bilibili 打开第一个视频
   - Hupu 发帖
   - Douyin 发视频
   - WeChat 朋友圈
   - Xiaohongshu 草稿
   - 搜索入口

这样下一轮 20 App x 20 请求失败时，不会只得到 `FAILED` 或一段模糊 message，而会看到机器可读的：

```json
{
  "blockerType": "BILIBILI_SEARCH_SURFACE",
  "nextActionHint": "先退出搜索结果页的输入框和 tabs，再点第一个视频候选。",
  "lastStep": {},
  "goalCheckPassed": false
}
```

## 改动文件

- `D:\controlphone\MacroPilot\app\src\main\java\com\macropilot\app\factory\AppSideInstructionFlowRunner.kt`
- `D:\controlphone\MacroPilot\app\src\main\java\com\macropilot\app\factory\PopularAppTaskBatchService.kt`

## 修复点

### 1. 任务是否成功不再只看执行状态

`AppSideInstructionFlowRunner.finishReport(...)` 新增 `finalOutcome`：

- `status`
- `summary`
- `goalCheck`
- `lastStep`
- `finalUiSummary`

这把“候选 JSON 执行完了”和“用户目标完成了”分开。

### 2. block 不再只是一句话

`PopularAppTaskBatchService.taskReport(...)` 新增 `outcome`：

- `blockerType`
- `nextActionHint`
- `goalCheckPassed`
- `goalCheckMandatory`
- `lastStep`
- `hasFinalOutcome`

前端可以直接展示：卡在哪、最后一步是什么、下一步该怎么修。

### 3. 关键复杂任务补硬判定

典型 blocker：

- `BILIBILI_SEARCH_SURFACE`：仍在搜索结果页，不能说第一个视频已打开。
- `BILIBILI_SHARE_PANEL_VISIBLE`：误入分享面板，下一步应返回，不要点分享项。
- `HUPU_SECTION_REQUIRED`：虎扑发帖缺专区，下一步必须选专区，不要继续 Wait。
- `DOUYIN_EDITOR_VISIBLE`：仍在发布编辑页，不能把发视频报成功。
- `WECHAT_MOMENT_EDITOR_VISIBLE`：仍在朋友圈编辑页，不能把已发表报成功。
- `XHS_DRAFT_TEXT_MISSING`：草稿文本未出现在当前 UI，不能报完成。
- `SEARCH_ENTRY_MISSING`：没看到搜索入口/搜索框。

## 构建验证

使用 Android Studio JBR 直接执行 Gradle wrapper：

```text
:app:compileDebugKotlin PASS
:app:assembleDebug PASS
```

APK：

```text
D:\controlphone\MacroPilot\app\build\outputs\apk\debug\app-debug.apk
LastWriteTime=2026-05-24 00:17:18
```

## 当前阻塞

`adb devices` 当前没有连接设备：

```text
List of devices attached
```

所以本轮没有安装 APK，也没有启动手机端 20 App x 20 请求测试。下一次设备连接后，应先安装新版 APK，再触发 batch。

## 下一轮测试建议

先小批量验证 3 个高价值任务：

1. Bilibili：搜索并打开第一个视频。
2. Hupu：进入发帖，遇到专区提示时必须输出 `HUPU_SECTION_REQUIRED` 或完成专区选择。
3. PDD：搜索商品并进入详情，失败时必须输出搜索/详情 blocker。

如果三项报告都带 `outcome.blockerType`、`outcome.nextActionHint`、`finalOutcome.finalUiSummary`，再跑 20 App x 20 请求。

