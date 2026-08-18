# GitHub 仓库整理记录

日期：2026-08-19

## 整理前

- Git 追踪文件：`5,376`
- 工作区文件体积约：`1.35 GB`
- `app/build`、`.gradle`、`test_artifacts`、`evidence_bundles`、抖音/虎扑批量截图目录和根目录 JSON/PNG/XML 占绝大多数。
- `controlphone_root_extras` 是旧工程归档，与当前 `app/src` 主实现重复。

## 保留到 GitHub

- `app/src/`：当前 MacroPilot Android 源码、资源和测试源码。
- `app/build.gradle.kts`、Gradle wrapper、根构建配置。
- `docs/`：架构、训练路线、失败复盘、测试结果和任务目录。
- `docs/evidence/`：精选 Skill、flow、runtime、app_config、PDD 摘要和少量截图。
- `tools/`：报告和证据验证脚本。
- `README.md`、`.gitignore`、`.gitattributes`。

## 从 GitHub 当前分支移除

以下内容从 Git 索引移除并加入 `.gitignore`，本地文件不自动删除，方便继续调试：

- `app/build/`、`.gradle/`、APK 和本机配置。
- 根目录散落的 PNG、JSON、JSON5、XML、ZIP 运行产物。
- `test_artifacts/`、`evidence_bundles/`、`rollback/`。
- `controlphone_root_extras/` 旧工程归档。
- `douyin_*`、`hupu_retest_caps_*`、`wechat_snapshots*`、`wechat_moments_test`、`third_party_*` 等批量设备缓存。

这些文件仍可从当前清理前提交恢复；本地备份位于：

`D:\controlphone\MacroPilot_github_before_cleanup_20260819.zip`

本次不重写 Git 历史、不 force-push，避免破坏已有提交和远端协作者引用。整理后的 GitHub 当前分支会干净，但旧大文件仍存在于历史提交中；若以后需要物理缩小仓库，再单独做一次经过确认的 history rewrite。
