# MacroPilot Skill Factory 自训练路线

## 当前方向纠偏

MacroPilot 不是给每个 App 手写固定流程，也不是让模型直接乱点手机。正确路线是：

1. 手机端读取目标 App 的 UI，形成 `AppUI graph`。
2. DeepSeek v4pro 只输出任务流程和关键 slot。
3. 程序从已有 Skill JSON / archetype / 成功案例中找相似模板。
4. 只改写关键字段：包名、文本别名、selector、坐标、输入参数、验证规则、安全标记。
5. 组装成细粒度 `atomic_skill` + `macro_skill`。
6. dry-run、实机测试、失败重判、PromotionGate。
7. 通过后再进 Runtime approved。

一句话：大模型提流程，MacroPilot 改模板和拼 JSON，测试结果决定是否可用。

## 训练数据结构

每个任务必须产生四类数据：

```text
AppUI graph：
  screen / tab / entry / input / clickable / edge

Skill patch assembly：
  sourceSkillId / sourcePath / changedFields / patchReason / assembledSkillId

Runtime trace：
  beforeUi / action / afterUi / result / confidence / duration / screenshot evidence

Failure sample：
  failedStep / blocker / currentUi / aiDiagnosis / patchSuggestion / retryResult
```

原始截图和完整节点树只保留少量最近证据；长期知识保存为 compact graph 和报告摘要。

## 大模型训练法则对应到本项目

### 1. 监督微调数据

训练样本不是“整条工作流”，而是更小的输入输出对：

```text
instruction + appUiGraph + availableSkills + failureHistory
→ taskPlan + skillPatchOps + verifyPlan
```

手机端现在先用 DeepSeek v4pro 在线完成这件事；等样本足够后，可以训练本地或私有模型替代 `TaskFlowPlanner`。

### 2. 负样本

失败比成功更重要。必须记录：

```text
误报成功
点错入口
卡广告
卡弹窗
需要选专区
搜索结果没有打开详情
输入通道缺失
坐标低置信
```

这些样本用于训练 verifier 和 replan policy。

### 3. 分层训练

不要一口气训练“万能手机控制模型”。按层训练：

```text
ScreenClassifier：
  当前是什么页面

ElementRoleLabeler：
  哪些节点像搜索、发布、评论、输入框、关闭广告

TaskFlowPlanner：
  用户目标拆成小步骤

SkillPatchGenerator：
  根据已有模板改关键字段

Verifier：
  判断步骤是否真的完成

RecoveryPolicy：
  失败后从当前页面怎么退回/重试
```

当前 v0 只做规则 + DeepSeek 在线辅助，所有输出仍然必须经过 dry-run/test/promotion。

### 4. 评价指标

每个 app/skill 都要有指标：

```text
screenCoverage：发现多少页面/入口
entryHitRate：入口点击后是否到达目标页面
stepSuccessRate：每个原子步骤成功率
goalTruthRate：成功报告是否真的有 UI 证据
replanRecoveryRate：失败后二次 AI 能救回多少
storageCost：每个 app 产生多少 KB/MB 长期知识
```

如果 `goalTruthRate` 不达标，禁止晋级。

## 自主训练循环

```text
1. Explore
   打开 App，关广告，记录首页、底部分屏、入口、输入框、子页面。

2. Plan
   DeepSeek v4pro 输入 instruction + AppUI graph + 可用 Skill 模板，输出小步骤。

3. Patch
   SkillTemplateMatcher 找相似模板。
   SkillJsonPatchAssembler 只改关键字段并写 assembly report。

4. Dry-run
   检查 schema、安全、输入通道、验证规则、引用关系。

5. Execute
   App 通过无障碍和输入法执行 candidate macro。

6. Verify
   每个步骤保存 before/action/after/result，不允许 Wait/OpenApp 假成功。

7. Replan
   失败或低置信时，把当前 UI、失败原因、图谱、模板发给 DeepSeek，只要下一小段补救步骤。

8. Promote
   多次测试达标才 APPROVED。
```

## 存储原则

```text
长期保留：
  app_ui_graph.json
  skill json
  assembly report
  dry-run report
  test summary
  failure cluster

短期保留：
  最近 N 个 raw ui snapshot
  最近 N 张 screenshot
  最近 N 个 AI job

不再长期堆：
  每一步完整节点树
  重复截图
  每次 Wait 的无意义 report
```

## 当前已经落地的代码入口

```text
AppUiExplorer：
  手机端探索 AppUI graph。

AppUiGraphStore：
  保存 compact AppUI graph。

SkillTemplateMatcher：
  从已有 JSON / runtime approved / archetype 找相似模板。

SkillJsonPatchAssembler：
  根据 plan 改写关键 slot 并组装 skill JSON。

AppSideInstructionFlowRunner：
  运行指令前读取/生成 AppUI graph；
  AI prompt 带 graph + availableSkillTemplates；
  plan 转 skill 时走 patch assembler。

ThirdPartyAppSkillSubsetBatcher：
  批量第三方 App 不再只探一屏，改用 AppUiExplorer。
```

## 具体训练落地路线

### v0 在线教师阶段

目标不是训练权重，而是把 DeepSeek v4pro 当“在线教师”收集高质量样本。每次用户任务必须产出一条可回放训练记录：

```json
{
  "input": {
    "instruction": "用户原始指令",
    "appPackage": "目标包名",
    "currentUi": "当前屏幕摘要",
    "appUiGraph": "已探索页面图谱",
    "availableSkillTemplates": "可复用 skill/archetype 摘要",
    "failureHistory": "最近失败原因"
  },
  "teacherOutput": {
    "taskPlan": ["小步骤"],
    "skillPatchOps": ["只改哪些 slot"],
    "verifyPlan": ["每步怎么验真"],
    "recoveryPlan": ["失败后下一小段怎么救"]
  },
  "programOutput": {
    "assemblyReport": "sourceSkillPath/changedFields/patchReason",
    "candidateSkillJson": ["atomic_skill/macro_skill"],
    "dryRunReport": "静态检查",
    "runtimeTrace": "before/action/after/result/confidence",
    "goalTruth": "目标是否真的完成"
  }
}
```

DeepSeek 只能给流程和关键 slot；`SkillJsonPatchAssembler` 负责复用模板、改写关键字段、组装 JSON。凡是没有 `assemblyReport` 的样本不能进入训练集。

### v1 数据集构建

从 v0 运行记录切五类数据集：

```text
1. screen_classifier_sft.jsonl
   input: labels/bottomTabs/entryCandidates/inputCandidates/clickTargets
   output: screenType + confidence

2. element_role_labeler_sft.jsonl
   input: node text/desc/resourceId/class/bounds/tags
   output: role(search_entry, publish_entry, first_video, comment_box, close_ad...)

3. task_flow_planner_sft.jsonl
   input: instruction + appUiGraph + availableSkillTemplates
   output: atomic step list

4. skill_patch_generator_sft.jsonl
   input: step + matched sourceSkill JSON
   output: patch ops and changedFields only

5. verifier_recovery_sft.jsonl
   input: before/action/after/result + goal
   output: pass/fail + failureDiagnosis + next 1-5 recovery steps
```

所有数据都来自手机端 app-side 执行记录，不来自 ADB 手工点击。ADB 只允许安装、触发广播、拉报告。

### v2 训练顺序

```text
第一训 Verifier：
  先解决“说成功但实际没成功”。指标 goalTruthRate >= 95%。

第二训 ElementRoleLabeler：
  解决“知道有输入框/返回键，却不知道第一个视频、评论框、发帖入口”。指标 roleTop1 >= 90%。

第三训 ScreenClassifier：
  解决“当前在哪个页面”。指标 screenTypeAccuracy >= 90%。

第四训 TaskFlowPlanner：
  让模型学会拆复杂任务。指标 planDryRunPassRate >= 90%。

第五训 RecoveryPolicy：
  让失败后不傻等，能关广告、选专区、退回首页、扫底部分屏。指标 replanRecoveryRate >= 70%。

最后训 SkillPatchGenerator：
  只输出 patch ops，不输出整坨 workflow。指标 patchDryRunPassRate >= 95%。
```

### 晋级门

```text
一个 app 基础可用：
  screenCoverage >= 3
  至少发现 bottomTabs / entryCandidates / inputCandidates 其中两类
  至少有 1 个 macro_skill dry-run 通过

一个复杂任务可用：
  goalTruthRate >= 95%
  stepSuccessRate >= 85%
  至少 3 次不同起点成功
  失败时必须有 failureDiagnosis，不允许只 Wait

一个 skill 可进 Runtime：
  dry-run 通过
  testRuns 原子 >= 10 / macro >= 20
  PromotionGate 通过
  status=APPROVED
```

### 当前代码对应关系

```text
Explore:
  AppUiExplorer + AppUiGraphStore

Plan:
  AppSideInstructionFlowRunner.requestAiPlan/requestAiPlanReview/requestAiFollowUp

Patch:
  SkillTemplateMatcher + SkillJsonPatchAssembler

Dry-run:
  SkillJsonDryRunEngine

Execute:
  SkillJsonExecutor + SkillJsonTestRunner

Verify/Replan:
  verifyInstructionGoal + requestAiFollowUp

Storage:
  UiStateSnapshotStore 上限
  FactorySkillJsonStore 报告/候选上限
  LocalDataGovernanceStore.compactFactoryArtifacts

Self-audit:
  SkillFactorySelfTest(context)
  ArchitectureSelfCheck.toJson()
```
