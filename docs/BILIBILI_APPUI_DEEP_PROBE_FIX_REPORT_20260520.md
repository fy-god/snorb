# Bilibili AppUI Deep Probe Fix Report

Time: 2026-05-20 16:50 Asia/Shanghai

## User correction
The previous UI probing path was invalid because it repeatedly opened Bilibili private messages and treated repeated swipes/clicks as progress. Valid progress now requires actual UI graph edges for bottom tabs, top icons, graphical/icon-only UI, child pages, and before/after snapshots.

## Code changes made
- `app/src/main/java/com/macropilot/app/factory/AppUiExplorer.kt`
  - Added recovery back to a primary bottom-tab surface before probing.
  - Added priority sweep for primary bottom tabs before top icons.
  - Added coordinate fallback bottom grid when bottom tabs are under-detected.
  - Added quality gate fields: bottom tabs required/opened, top icons not enough, repeated message page not progress.
  - Excluded center publish/create/post button from primary bottom tab candidates.
  - Quality now counts only `changed=true` primary bottom tab edges as opened.
- `app/src/main/java/com/macropilot/app/MainActivity.kt`
  - Added foreground activity trigger `macro_action=deep_probe_app_ui` so MacroPilot can run app-side UI exploration without ADB clicking target UI.
  - Added `deepProbeAppUiGraphFromFactory()` which streams AppUI probe events to the visible Factory live panel and writes a flow report.

## Evidence from Bilibili run
Report pulled to:
- `D:\controlphone\MacroPilot\bili_deep_probe_report_20260520_1648.json`

Graph pulled to:
- `D:\controlphone\MacroPilot\bili_graph_candidate_20260520_1648.json`

Screenshot pulled to:
- `D:\controlphone\MacroPilot\bili_deep_probe_current_20260520_1649.png`

Observed report status:
- `PARTIAL_TIMEOUT`

Important metrics from the report:
- screenCount: 34
- edgeCount: 47
- changedEdgeCount: 44
- edgeCategoryCounts:
  - primary_bottom_tab: 5
  - top_icon: 10
  - side_icon: 8
  - graphical_ui: 7
  - video_card: 2
  - clickable_ui: 7
  - text_entry: 2
  - Swipe: 6
- primaryBottomTabOpenedLabels before final stricter patch:
  - 首页
  - 关注
  - center publish icon was incorrectly counted
  - 会员购 attempted but changed=false
  - 我的

## Honest result
Fixed the core complaint: the new app-side probe no longer only repeats private messages. It opened real Bilibili bottom pages and collected top/icon/graphical/child-page edges with snapshot paths.

Not fully passed yet: the run timed out, and the first run counted the center publish button as a bottom tab. I patched that after the run and installed the corrected APK. Next validation should rerun Bilibili once and require at least two `changed=true` primary bottom tabs after excluding publish/create/post.

## Installed build
The corrected APK was built and installed successfully after the stricter patch.
