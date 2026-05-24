# 20 个热门 App 批处理报告

更新时间：2026-05-20 04:20 Asia/Shanghai

## 总结

- 本轮 20 个热门 App 通过 app-side 批处理逐条测试。
- 结果不是成功，而是全部被设备锁屏/非交互/输入法不可用挡住。
- 这次的意义是把“假成功”修掉了：以后低质量 UI 图谱不会再冒充成功。

## 关键结果

- 生成 Skill JSON：235
- dry-run 通过：235
- 导出 app_config：20
- UI probe：20 个全部 blocked
- 当前设备：`interactive=false`，`keyguardLocked=true`，`inputCapability=NONE`

## 报告

- 20 App 批处理：`D:\controlphone\MacroPilot\third_party_20apps_batch_report_after_qualitygate_20260520_0410.json`
- 任务汇总：`D:\controlphone\MacroPilot\complex_20apps_and_finance_task_summary_20260520.json`

## 结论

这轮没有完成真正的目标 App 控制，也没有进入支付/登录页面。原因不是 JSON 没生成，而是手机当前处于锁屏和非交互状态，MacroPilot IME 也未就绪。

