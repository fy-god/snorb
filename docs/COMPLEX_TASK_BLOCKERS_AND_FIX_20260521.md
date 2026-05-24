# 复杂任务处理不了的原因与本轮修复

时间：2026-05-21 23:15  
目标：解释为什么复杂任务总失败，并修复最底层的两个阻塞点。

## 结论

复杂任务不是一个“多点几下”的问题。之前主要卡在四层：

1. **任务互相打架**：App UI probe / app-side instruction 同时发多个时，第二个直接 `FAILED_BUSY`，导致 20 App 批测天然乱。
2. **输入能力误判**：任务里包含搜索/评论/发帖时，系统在“还没进入输入框页面”就因为 `inputCapability=NONE` 直接拦截，复杂任务根本走不到找入口阶段。
3. **图标语义弱**：图形化 UI 能读到位置和候选，但很多图标-only 元素还只能叫 `bottom_tab_3`、`graphical_ui_13`，需要 OCR/视觉模型补语义。
4. **目标验证不够强**：打开搜索结果页、看到目标文字、真正点开详情页、真正发出内容，这几种状态以前容易混在一起。

本轮已经修复 1 和 2，并验证通过。3 和 4 是下一轮要继续补的能力。

## 已改代码

| 文件 | 改动 |
|---|---|
| `D:\controlphone\MacroPilot\app\src\main\java\com\macropilot\app\factory\AppUiProbeService.kt` | `FAILED_BUSY` 改为串行队列；连续触发 UI probe 时后续请求进入 `QUEUED`，前一个完成后继续跑 |
| `D:\controlphone\MacroPilot\app\src\main\java\com\macropilot\app\factory\AppSideInstructionService.kt` | 重写为干净的手机端任务队列服务；保留 `AppSideInstructionFlowRunner`，但连续复杂任务不再被忙状态直接拒绝 |
| `D:\controlphone\MacroPilot\app\src\main\java\com\macropilot\app\factory\AppSideInstructionFlowRunner.kt` | 输入能力检查延迟到输入步骤；启动时不再因任务包含“搜索/评论/发帖”就直接被 `inputCapability=NONE` 拦死 |

## 编译和安装

| 子任务 | 结果 |
|---|---|
| `assembleDebug` | PASS |
| 安装 `app-debug.apk` | PASS |
| 安装后无障碍状态 | PASS：`serviceConnected=true`，`nodeReadability=A` |
| 当前输入能力 | 仍为 `inputCapability=NONE`，但不会在任务开头误杀 |

## 验证结果

### UI Probe 队列

连续触发：

- `tv.danmaku.bili`
- `com.xunmeng.pinduoduo`

结果：

| App | requestId | 结果 |
|---|---|---|
| 拼多多 | `app_ui_probe_com.xunmeng.pinduoduo_1779376375525` | `SUCCESS`，`queueRemaining=1` |
| 哔哩哔哩 | `app_ui_probe_tv.danmaku.bili_1779376375542` | `SUCCESS`，`queueRemaining=0` |

说明：第二个不再 `FAILED_BUSY`，串行队列生效。

### App-side Instruction 队列

连续触发：

- `打开哔哩哔哩并读取底部几个页面`
- `打开拼多多并读取首页图形化入口`

结果：

| App | requestId | 队列状态 | 完成状态 |
|---|---|---|---|
| 哔哩哔哩 | `app_side_instruction_1779376375923` | `QUEUED_STARTING` | `SUCCESS` |
| 拼多多 | `app_side_instruction_1779376375969` | `QUEUED` | `SUCCESS` |

证据：

- `/data/user/0/com.macropilot.app/files/macro_v2/factory/flow_reports/tv.danmaku.bili/app_side_instruction_1779376375923_queued.json`
- `/data/user/0/com.macropilot.app/files/macro_v2/factory/flow_reports/tv.danmaku.bili/app_side_instruction_1779376375923_completion.json`
- `/data/user/0/com.macropilot.app/files/macro_v2/factory/flow_reports/com.xunmeng.pinduoduo/app_side_instruction_1779376375969_queued.json`
- `/data/user/0/com.macropilot.app/files/macro_v2/factory/flow_reports/com.xunmeng.pinduoduo/app_side_instruction_1779376375969_completion.json`

## 现在复杂任务应该怎么继续做

下一步不要再从 20 App 大批量复杂任务开始。应该按这个顺序补齐：

1. **输入通道验证**
   - 进入真实输入框后再判断 `ACTION_SET_TEXT` / `MacroPilot IME` 是否可用。
   - 如果不可用，报告必须写“卡在输入通道”，不要把前面打开 App 当成功。

2. **目标状态验证**
   - 搜索任务：必须区分“搜索结果页出现”和“已点开第一个结果”。
   - 发帖/评论：必须区分“编辑器写入”和“最终发布成功”。
   - 点赞/关注：必须记录点击前后按钮状态、红点/数字/选中态。

3. **图形化 UI 语义补全**
   - 现有图谱已经能找到 `graphical_ui_*` 和坐标。
   - 还需要 OCR/视觉模型给图标命名，例如“加号、购物车、我的、消息、发布、搜索”。

4. **失败后局部重规划**
   - 失败后不要重跑整条旧计划。
   - 保存当前 UI 快照，把 `currentUi + appUiGraph + failedStep + screenshot` 给 AI，只让它返回 1-5 步修补动作。

## 当前可继续测试的复杂任务

适合现在测：

- 哔哩哔哩：搜索并打开第一个视频。
- 拼多多：打开首页、进入搜索、打开商品详情。
- 虎扑：进入发帖入口、识别专区选择弹窗，但暂不保证发出帖子。

不适合直接测到最终发布成功：

- 发评论、发帖、发朋友圈、发视频。

原因不是执行队列了，而是 `inputCapability=NONE` 仍需在真实输入页面验证并解决。

