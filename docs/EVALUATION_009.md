# Evaluation 009 - Skill Factory v0 JSON Pipeline

Date: 2026-05-05

## Scope

This pass moved MacroPilot away from "record once, replay workflow" and toward the requested Skill Factory v0:

```text
sampling / recording / trial data
-> fine-grained SkillCandidate JSON
-> dry-run
-> test reports
-> PromotionGate
-> APPROVED Skill JSON
-> single app_config export
```

This is not model training. Runtime still has zero AI execution authority.

## Implemented

- Fine-grained Skill JSON candidate store:
  - `FactorySkillJsonStore`
  - candidate JSON, dry-run reports, app_config exports, and Factory AI job logs
- Rule-generated WeChat current-chat candidates:
  - `wechat.chat.find_chat_input`
  - `wechat.chat.type_chat_input`
  - `wechat.chat.find_send_button`
  - `wechat.chat.click_send_button`
  - `wechat.chat.verify_message_sent`
  - `wechat.chat.send_in_current_chat`
- Static dry-run engine:
  - rejects fake `APPROVED`
  - requires `implements`, `capabilityRequired`, `action`, `verify`, `resultPolicy`
  - enforces input-channel declarations for input skills
  - validates coordinate fallback confidence
  - enforces macro skills as `UseSkill` references
- Factory-only AI instruction pipeline:
  - calls existing AI client with `runtimePath=false`
  - saves prompt snapshot, raw response, parsed intent, model, baseUrlHost, and accepted state
  - does not generate candidates when the AI call is disabled, blocked, or failed
- App config exporter:
  - exports one config JSON with `atomicSkills`, `macroSkills`, `runtimePolicy`, and `factoryReports`
- Skill JSON test and promotion:
  - `SkillJsonExecutor`
  - `SkillJsonTestRunner`
  - `SkillJsonPromotionGate`
  - side-effect tests are blocked by default unless `allowSideEffect=true`
  - approved JSON is copied to `files/macro_v2/runtime/approved/apps/{package}/skills`
  - runtime-approved `config.json` is refreshed after promotion
- UI:
  - Factory dashboard now shows Skill JSON, dry-run, app_config, and AI job counts
  - app rows now include per-app Factory Skill JSON candidates
  - tapping JSON skill shows details for action/verify/resultPolicy/factory and buttons for dry-run, safe test, and promote
- Built-in self-test:
  - `SkillFactorySelfTest`
  - `API_FACTORY_SELF_TEST`
  - validates rule generation, dry-run fake-approval rejection, and PromotionGate thresholds

## Verification

- `:app:assembleDebug` passes.
- `:app:testDebugUnitTest` could not be used because Maven returned HTTP 403 while resolving JUnit; no external test dependency is left in the project.
- `adb devices` currently returns no connected devices, so broadcast smoke testing and in-app UI testing are pending.

## Required Device Smoke Test

When a device is online, run:

```powershell
adb shell am broadcast -a com.macropilot.app.API_GENERATE_WECHAT_SKILL_JSON -n com.macropilot.app/.api.MacroPilotApiReceiver
adb shell am broadcast -a com.macropilot.app.API_LIST_SKILL_JSON -n com.macropilot.app/.api.MacroPilotApiReceiver --es packageName com.tencent.mm
adb shell am broadcast -a com.macropilot.app.API_DRY_RUN_SKILL_JSON -n com.macropilot.app/.api.MacroPilotApiReceiver --ez dryRunAll true --es packageName com.tencent.mm
adb shell am broadcast -a com.macropilot.app.API_FACTORY_SELF_TEST -n com.macropilot.app/.api.MacroPilotApiReceiver
adb shell am broadcast -a com.macropilot.app.API_TEST_SKILL_JSON -n com.macropilot.app/.api.MacroPilotApiReceiver --es skillId wechat.chat.type_chat_input --es message FACTORY_TEST
adb shell am broadcast -a com.macropilot.app.API_PROMOTE_SKILL_JSON -n com.macropilot.app/.api.MacroPilotApiReceiver --es skillId wechat.chat.type_chat_input
adb shell am broadcast -a com.macropilot.app.API_LIST_APPROVED_SKILL_JSON -n com.macropilot.app/.api.MacroPilotApiReceiver --es packageName com.tencent.mm
adb shell am broadcast -a com.macropilot.app.API_EXPORT_APP_CONFIG_JSON -n com.macropilot.app/.api.MacroPilotApiReceiver --es packageName com.tencent.mm --ez approvedOnly true
adb shell am broadcast -a com.macropilot.app.API_FACTORY_DASHBOARD -n com.macropilot.app/.api.MacroPilotApiReceiver --es packageName com.tencent.mm
```

Expected result:

- WeChat JSON generation creates 6 `CANDIDATE` skills.
- Dry-run promotes static-valid skills to `DRY_RUN_PASSED`.
- Safe-test of `type_chat_input` either writes a real test report or a blocked precondition report if the device is locked/offline/wrong app.
- Promotion only succeeds after enough passing test runs.
- Approved-only export writes one runtime-approved app_config JSON.
- Runtime still does not execute these candidates.
