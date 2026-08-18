# MacroPilot 当前测试成果

更新时间：2026-08-19（本次整理未触发目标 App）

## 先说结论

当前不能写成“20 个 App 全部通过”。最新可审计证据显示：

- 20 App 广泛批次：手机端 `success=2, failed=15, blocked=3`；电脑侧复核 `PASS=3, FAIL=14, UNKNOWN=3`。
- 拼多多失败/超时子集修复后复测：`8/8` 成功，执行通过率 `100.0%`。
- 3 App 深层任务验证（哔哩哔哩、虎扑、拼多多）：`1/3` 成功，`33.3%`；其中哔哩哔哩成功，虎扑超时，拼多多在修复前超时。
- Skill Factory 3 App 批次：生成 Skill JSON `36` 个，dry-run `36/36`，但 UI probe 成功率 `0%`，3 个都被判定为 blocked/partial。
- 本机当前 `adb devices -l` 没有设备，因此 2026-08-19 没有新的手机实测结果。

## 已验证通过的部分

### Skill Factory 产线

已能形成以下链路并落报告：

```text
app-side instruction
-> AppUI graph
-> AI/相似 Skill 计划
-> 关键 slot patch
-> atomic/macro Skill JSON
-> dry-run
-> SkillJsonExecutor
-> runtime report
-> goal check/finalOutcome
```

3 App 批次统计：

| 指标 | 结果 |
|---|---:|
| 生成 Skill JSON | 36 |
| dry-run 通过 | 36/36（100%） |
| app_config 导出 | 3/3（100%） |
| UI probe 尝试 | 3 |
| UI probe 成功 | 0 |
| UI probe blocked/partial | 3 |

这说明 JSON 生成和静态检查已经可用，但不能把 dry-run 通过当成手机任务完成。

### 拼多多失败子集

报告：`docs/evidence/SUMMARY.md`

只复测前一轮失败/超时的 8 个任务，结果：

```text
taskCount=8
success=8
failed=0
blocked=0
passRate=100.0%
```

已验证的修复包括：离开拼多多后恢复目标包、跳过失效坐标、允许丰富的 PDD UI 图谱通过质量判断、按 `taskIds` 定向复测。这里主要是 UI 图谱/低风险入口探测，不代表已完成支付或真实下单。

### 哔哩哔哩

在 2026-05-21 的 3 App 深层验证中，`bili_search_open_first_video` 有成功证据；验收要求是进入视频播放页，而不是停在搜索结果页。保留证据：

- `docs/evidence/bili_final_flow.json`
- `docs/evidence/bili_final_runtime_open_first.json`
- `docs/evidence/bili_final_clickcoordinate_skill.json`
- `docs/evidence/bili_final_app_config.json`
- `docs/evidence/bili_final2.png`

但在更宽的 20 App 批次中，哔哩哔哩仍出现 `FAILED_GOAL_UNVERIFIED`。因此结论是“专项样例通过，批次稳定性未通过”。

### 小红书、虎扑、抖音

- 小红书已有成功发布/训练记录和截图样例，但 20 App 宽批次仍出现目标校验失败。
- 虎扑 UI 图谱能读到大量节点、底部 tab、顶部/侧边/图形化入口，但存在 90 秒超时和小字弹窗识别问题；不能把进入编辑器当成发帖成功。
- 抖音已有配置和 runtime 样例，但当前证据不足以证明“视频已真正发布”。

保留的代表性文件位于 `docs/evidence/`。

## 20 App 广泛批次

来源：`docs/POPULAR_20_APP_BATCH_VERIFICATION_20260521.md`

| 指标 | 结果 |
|---|---:|
| 手机端成功 | 2 |
| 手机端失败 | 15 |
| 手机端阻断 | 3 |
| 执行尝试 | 19 |
| 电脑侧 PASS | 3 |
| 电脑侧 FAIL | 14 |
| 电脑侧 UNKNOWN | 3 |
| v4pro/AI jobs | 38 |
| AI jobs 成功 | 9 |
| HTTP 402 | 28 |
| 本地 PNG | 65 |
| 拉取文件 | 1932 |
| 拉取失败 | 3401 |

主要问题不是“没有 JSON”，而是：v4pro 费用/HTTP 402 导致计划降级、UI 图谱探索超时、输入通道不可用、最终 goal verifier 证据不足，以及历史证据路径大量缺失。

安全阻断的支付宝、云闪付、密码/交易类任务不应算失败；它们被 guard 正确挡住，但也不等于业务任务完成。

## 当前未通过项

1. 设备当前未连接，无法在本次整理中重新验证。
2. 20 App 复杂任务没有达到稳定通过。
3. UI probe 的“覆盖/完成”与“质量/成功”仍需分开，不能用收集到节点数量替代入口和目标验证。
4. 小字 Toast、图标-only 控件、弹窗、红点和发布后的最终状态仍需要多证据验证。
5. AI 供应商返回 HTTP 402 时，必须明确报告降级，不应让用户以为仍在使用 v4pro。

## 建议下一轮验收

设备连接后只跑失败子集：

1. 拼多多失败任务回归，确认 8/8 结果可复现。
2. 哔哩哔哩搜索并打开首视频，检查最终 Activity/页面和截图。
3. 虎扑先识别开屏广告及“小字提示/选择专区”弹窗，再继续发帖；失败时记录 blocker 和下一步。
4. 抖音/小红书只在最终发布状态可见时计成功。

完整历史报告仍在 `docs/`；本目录下的 `docs/evidence/` 只保留可复核的代表性 JSON 和截图，避免再次把 GitHub 变成手机缓存仓库。
