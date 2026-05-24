# 电脑端任务完成复核报告

生成时间：2026-05-21

规则：不相信手机端一句 SUCCESS，必须重新读取 flow/runtime/goal evidence。证据不足就是 UNKNOWN。

- 总数：1
- PASS：0
- FAIL：0
- UNKNOWN：1

## 明细

### popular20_connectivity_20260521_001.json

- 任务类型：unknown
- 手机端状态：PARTIAL
- 电脑复核：UNKNOWN (LOW)
- 证据数量：{"steps":0,"goalSteps":0,"runtimeSteps":0,"graphSteps":0,"aiSteps":0,"dryRunSteps":0,"screenshotOrSnapshotSteps":0,"referencedImageOrSnapshotPaths":671,"localImageOrSnapshotPaths":319,"missingImageOrSnapshotPaths":352,"analyzedPngs":20,"failedSteps":0}
- 图像证据：引用 671，本地可读 319，缺失 352，已分析 PNG 20
- 未拉取示例：/data/user/0/com.macropilot.app/files/macro_v2/factory/ui_snapshots/tv.danmaku.bili/20260521_014020_141_6de63180.json；/data/user/0/com.macropilot.app/files/macro_v2/factory/ui_snapshots/tv.danmaku.bili/20260521_014026_107_3d4f5b3f.json；/data/user/0/com.macropilot.app/files/macro_v2/factory/ui_snapshots/tv.danmaku.bili/20260521_014028_438_2756782f.json
- 首张有效图：1146224640x94371840，861593 bytes，redDot={}
- 规则：通用任务至少需要目标完成校验 SUCCESS + runtime 报告；否则 UNKNOWN。
- 阻塞/不足：unknown_task_type_or_missing_evidence
