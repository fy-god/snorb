# MacroPilot 20 App 任务批次报告 2026-05-20

## 结论

本轮已经按“每个 App 至少 10 个用户任务”生成目录并由手机端 MacroPilot 执行批次。

- App 数：20
- 任务数：200
- 手机端执行/登记：190
- UI 图谱/安全探测成功：122
- 搜索、输入、草稿、评论等长流程登记为单任务待执行：40
- 支付、金融、密码、交易等安全阻断：37
- 真实失败：1
- 跳过：0

本轮没有用 ADB 手动点击目标 App UI。ADB 只用于安装 APK、触发 MacroPilot API、读取状态、拉报告和截图。

## 证据文件

- 完整批次报告：`D:\controlphone\MacroPilot\popular_app_task_batch_20x10_20260520.json`
- 任务目录：`D:\controlphone\MacroPilot\popular_app_task_catalog_20x10_run_20260520.json`
- 摘要 JSON：`D:\controlphone\MacroPilot\popular_app_task_batch_20x10_summary_20260520.json`
- 最终手机截图：`D:\controlphone\MacroPilot\popular_app_task_batch_20x10_final.png`
- 手机端 requestId：`popular_app_task_batch_1779271838092`

## 本轮行为边界

这次批次被改成分层执行：

1. UI 图谱类任务：直接由手机端 `AppUiExplorer` 打开 App、读取底部页、顶部小图标、图形化入口、子页面、侧边 UI，并保存图谱。
2. 搜索、输入、评论、发帖、草稿类长流程：登记为 `PENDING_INPUT_FLOW_SINGLE_TASK`，写出建议用 `API_RUN_APP_SIDE_INSTRUCTION` 单独执行，避免一个长任务拖死 20 App 批次。
3. 支付、金融、密码、下单、交易类：只生成 guard 报告，状态为 `BLOCKED_HIGH_RISK_GUARD_ONLY`，不输入密码、不支付、不交易。

## App 结果

| App | 包名 | UI/安全成功 | 单任务待执行 | 安全阻断 | 失败 | 图谱 |
|---|---:|---:|---:|---:|---:|---:|
| 哔哩哔哩 | `tv.danmaku.bili` | 8 | 2 | 0 | 0 | 25 屏 / 45 边 |
| 小红书 | `com.xingin.xhs` | 7 | 3 | 0 | 0 | 11 屏 / 10 边 |
| 抖音 | `com.ss.android.ugc.aweme` | 6 | 4 | 0 | 0 | 21 屏 / 20 边 |
| 虎扑 | `com.hupu.games` | 6 | 3 | 0 | 1 | 12 屏 / 11 边 |
| 微博 | `com.sina.weibo` | 7 | 3 | 0 | 0 | 14 屏 / 13 边 |
| QQ | `com.tencent.mobileqq` | 8 | 2 | 0 | 0 | 11 屏 / 57 边 |
| 微信 | `com.tencent.mm` | 6 | 3 | 1 | 0 | 3 屏 / 2 边 |
| 美团 | `com.sankuai.meituan` | 9 | 1 | 0 | 0 | 28 屏 / 51 边 |
| 大众点评 | `com.dianping.v1` | 8 | 2 | 0 | 0 | 12 屏 / 15 边 |
| 淘宝 | `com.taobao.taobao` | 8 | 1 | 1 | 0 | 13 屏 / 48 边 |
| 京东 | `com.jingdong.app.mall` | 8 | 1 | 1 | 0 | 9 屏 / 14 边 |
| 拼多多 | `com.xunmeng.pinduoduo` | 8 | 1 | 1 | 0 | 12 屏 / 21 边 |
| 知乎 | `com.zhihu.android` | 6 | 4 | 0 | 0 | 42 屏 / 41 边 |
| 网易云音乐 | `com.netease.cloudmusic` | 6 | 3 | 1 | 0 | 6 屏 / 7 边 |
| 百度地图 | `com.baidu.BaiduMap` | 6 | 3 | 1 | 0 | 5 屏 / 4 边 |
| 高德地图 | `com.autonavi.minimap` | 0 | 0 | 10 | 0 | 无 |
| 支付宝 | `com.eg.android.AlipayGphone` | 3 | 1 | 6 | 0 | 12 屏 / 20 边 |
| 云闪付 | `com.unionpay` | 2 | 1 | 7 | 0 | 8 屏 / 20 边 |
| 同花顺 | `com.hexin.plat.android` | 2 | 0 | 8 | 0 | 3 屏 / 7 边 |
| 钉钉 | `com.alibaba.android.rimet` | 8 | 2 | 0 | 0 | 12 屏 / 18 边 |

## 关键证据

哔哩哔哩图谱已覆盖底部主入口：`首页 / 动态/关注 / 发布 / 会员购 / 我的`，并记录 `关注加号 / 会员购全部分类 / 订单 / 我的服务 / 游戏中心 / 设置` 等入口。

美团、淘宝、拼多多、支付宝、云闪付等图形化入口和顶部/侧边候选已被记录到 UI 图谱。支付、交易、密码相关任务全部按安全 guard 阻断。

QQ 登录态、协议/快速登录类入口进入了 UI 图谱任务，不再只重复点私信。

## Blocker

1. 虎扑 `hupu_dialog_small_text` 超时 90 秒。下一步要为“小字提示/弹窗”做专门的轻量探测器，不走完整深探。
2. 高德地图包名 `com.autonavi.minimap` 在本机没有解析到已安装启动入口，10 个任务全部阻断。需要用当前手机的真实包名重跑，或者从安装列表里重新匹配。
3. 微信图谱只有 3 屏 / 2 边，质量不足。微信需要单独跑四底栏专项采集。
4. 搜索、发帖、评论、发布类 40 个任务已登记为单任务待执行，不能算已完成复杂业务动作。它们需要逐个调用 `API_RUN_APP_SIDE_INSTRUCTION` 并用最终 UI 证据验证。

## 代码改动

- 新增 `PopularAppTaskCatalog.kt`：20 个热门 App，每个至少 10 个真实用户任务。
- 新增 `PopularAppTaskBatchService.kt`：手机端批量任务服务，分层执行 UI 图谱、长流程登记、安全 guard。
- 扩展 `MacroPilotApiReceiver.kt`：
  - `API_LIST_POPULAR_APP_TASK_CATALOG`
  - `API_RUN_POPULAR_APP_TASK_BATCH`
  - `API_CANCEL_POPULAR_APP_TASK_BATCH`
- 扩展 `AndroidManifest.xml`：注册新 API action 和前台服务。
- 修正 `AppSideInstructionFlowRunner` 前置检查：输入通道只作为输入类任务硬门槛，非输入 UI 探索不再被 `inputCapability=NONE` 卡死。

## 下一步

优先处理：

1. 虎扑小字弹窗专用探测器。
2. 微信四底栏专项图谱。
3. 高德地图包名重匹配。
4. 从 40 个 deferred 任务里挑复杂单任务逐个跑，例如“B 站搜索并打开第一个视频”“小红书草稿”“微博草稿”“虎扑发帖专区选择”。
