# MacroPilot Evaluation 007

Date: 2026-05-03
Device: `373bb463`
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Scope

This pass focused on the path required for WeChat without pretending WeChat already works:

- Add a target-app capability probe API.
- Verify WeChat node readability on the real device.
- Add a real third-party AI provider interface for Factory use.
- Expose Factory state in the native UI.

Runtime remains AI-free and still executes only Approved skills.

## Capability Probe

Added:

```text
com.macropilot.app.API_PROBE_APP_CAPABILITY
```

The probe opens a requested package, waits for that exact package to become foreground, profiles the Accessibility node tree, saves an optional redacted sample, and returns node highlights.

Important fix:

```text
requested package != observed package
=> ok=false
```

This prevents MacroPilot from accidentally reporting its own UI tree as a successful target-app probe.

## Verified Results

Settings:

```text
requestedPackageName=com.android.settings
observedPackageName=com.android.settings
nodeCount=85
clickableCount=15
editableCount=1
nodeReadability=A
inputCapability=SET_TEXT
verificationCapability=HIGH
```

WeChat:

```text
requestedPackageName=com.tencent.mm
observedPackageName=com.tencent.mm
nodeCount=1
clickableCount=0
editableCount=0
nodeReadability=C
inputCapability=NONE
verificationCapability=LOW
```

Cross-check:

```text
uiautomator dump
package=com.tencent.mm
single empty node
```

Conclusion:

```text
This device's current WeChat surface cannot support a high-confidence node-based send-message skill yet.
```

No WeChat skill was promoted.

## AI Provider

Added AI provider selection:

```text
CUSTOM_JSON
OPENAI_RESPONSES
DASHSCOPE_COMPAT_CHAT
```

`OPENAI_RESPONSES` uses:

```text
https://api.openai.com/v1/responses
```

Verified:

```text
AI_GET_CONFIG => provider=OPENAI_RESPONSES
AI_REQUEST without API key => DISABLED / config incomplete
AI_REQUEST runtimePath=true => BLOCKED
```

Qwen/DashScope smoke test:

```text
provider=DASHSCOPE_COMPAT_CHAT
endpoint=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
model=qwen3-vl
result=HTTP 404 model_not_found
```

Using the same saved key with the available model name:

```text
model=qwen3-vl-plus
result=HTTP 200
status=SUCCESS
parsedJson=true
durationMs=4208
```

Runtime boundary after enabling Qwen:

```text
AI_REQUEST runtimePath=true => BLOCKED
```

After adding `DASHSCOPE_COMPAT_CHAT`, MacroPilot also supports base64 AI input:

```text
AI_REQUEST --es inputJsonBase64 <base64-json>
```

This is required for node trees because raw JSON extras are easily broken by shell quoting.

## WeChat Calibration

Added:

```text
com.macropilot.app.API_CREATE_WECHAT_CALIBRATION_REQUEST
```

The API first runs a WeChat probe, then saves a non-executable calibration request in:

```text
files/macro_v2/factory/pending_skills/
```

Verified with MacroPilot IME disabled:

```text
nodeReadability=C
inputCapability=NONE
verificationCapability=LOW
status=FAILED_NEEDS_CALIBRATION
```

Added a minimal `MacroPilotImeService` and verified Android lists it:

```text
com.macropilot.app/.automation.MacroPilotImeService
```

After enabling and selecting it:

```text
default_input_method=com.macropilot.app/.automation.MacroPilotImeService
WeChat probe => C / CUSTOM_IME / LOW
```

This removes the hard text-input blocker, but WeChat still requires coordinate calibration and low-confidence gating because its visible node tree is still empty.

This means third-party AI is now connected at the Factory boundary, but still cannot enter Runtime execution.

## Factory UI

Added a `Factory` tab with:

- AI Assist status.
- WeChat probe.
- Settings probe.
- AI Patch request.
- Candidate Skill/Patch/Module counts.
- Test report count.
- Candidate and AI log file previews.

Screenshot captured:

```text
D:\controlphone\MacroPilot\macropilot_factory_tab2.png
```

## Current WeChat Blocker

The blocker is not missing UI code. The blocker is:

```text
WeChat exposes no useful Accessibility nodes or editable input on this device.
```

To satisfy WeChat tasks, the next implementation must add:

- Explicit WeChat calibration mode.
- Coordinate candidates with low-confidence status.
- Input-channel alternatives such as paste/custom IME/Shizuku-ADB mode.
- A WeChat-specific candidate flow that must pass test runs before promotion.

Until one of those paths is verified, MacroPilot must not claim WeChat message sending success.
