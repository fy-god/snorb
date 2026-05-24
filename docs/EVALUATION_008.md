# Evaluation 008 - AI API and WeChat Type-Draft Gate

Date: 2026-05-03

## AI API

Configured provider:

```text
DASHSCOPE_COMPAT_CHAT
https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

Results:

```text
model=qwen3-vl
status=FAILED
httpStatus=404
reason=model_not_found

model=qwen3-vl-plus
status=SUCCESS
httpStatus=200
parsedJson=true
```

Runtime boundary:

```text
AI_REQUEST runtimePath=true => BLOCKED
```

Conclusion:

```text
The third-party AI API is connected and usable through Factory-only requests.
The exact model name qwen3-vl is not usable with this account/endpoint.
The app is restored to qwen3-vl-plus.
Runtime AI remains forbidden.
```

## Runtime Fixes

Added:

```text
MacroAtom.FocusTextInput
DeviceActions.isMacroPilotImeReady()
MacroPilotImeService.hasActiveInputConnection()
```

Fixed:

```text
TypeText/SearchInApp/FocusTextInput now require an input channel in preflight.
MacroExecutor now treats selected MacroPilot IME as CUSTOM_IME.
Current-screen candidate skills must start in the target package unless they explicitly OpenApp.
```

This prevents a current-chat WeChat coordinate candidate from tapping another foreground app.

## WeChat Candidate

Rebuilt:

```text
skillId=wechat_type_current_chat_candidate
atoms:
  FocusTextInput(chat_input, toggles=[keyboard_toggle, voice_toggle])
  TypeText(message, targetElementId=chat_input)
finalVerify=ALWAYS / LOW
source=CANDIDATE
runtimeExecutable=false
```

Updated bottom-bar coordinate candidate:

```text
chat_input ~= x=0.45, y=0.927
keyboard_toggle ~= x=0.865, y=0.927
voice_toggle ~= x=0.060, y=0.927
```

Dry run:

```text
API_DRY_RUN_SKILL_DRAFT => passed=true
```

## Current Blocker

After reinstall, the device entered lockscreen:

```text
interactive=false
keyguardLocked=true
currentPackage=com.android.systemui
macroPilotImeSelected=true
macroPilotImeReady=false
```

Runtime correctly refuses to execute in this state. This is the expected no-fake-success behavior.

Next executable test:

```text
Unlock device
Open WeChat File Transfer Assistant
Run API_TEST_SKILL_DRAFT with message=MP_DRAFT_001
Expected maximum result: SUCCESS_LOW_CONFIDENCE
```

Send-button testing remains out of scope until draft input is reliable.
