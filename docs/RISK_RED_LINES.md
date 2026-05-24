# MacroPilot Risk Red Lines

MacroPilot is not a universal visual phone agent. It is an Accessibility-first local automation engine.

## Capability Levels

- A: key nodes are readable and actionable. High-confidence execution is allowed.
- B: key nodes are partially readable. Node-first execution with coordinate fallback is allowed with medium confidence.
- C: nodes are mostly unreadable. Coordinate macro execution is low confidence and must be treated as such.
- D: missing nodes, input channel, verification signal, or coordinate fallback. Execution is blocked.

## Runtime Red Lines

- Runtime must not import or execute Factory components.
- Runtime must not execute CandidateSkill, PendingSkill, RawTrace, or TrainingSample.
- Runtime default AI call count is zero.
- Runtime must not use screenshots or VLM as the main path.
- Runtime must run Capability Check before every task.
- D-level capability must not execute.
- Missing input channel must fail with FAILED_INPUT_CHANNEL_MISSING.
- Missing verification must not return SUCCESS.
- Coordinate-only execution can only return low confidence.
- Dangerous actions are blocked by default.
- Every path must pass MacroVerifier.
- A 30-second task timebox is mandatory.
- Accessibility service loss, root null, or permission loss must stop execution.
- User touch or foreground app change must pause or cancel execution.
- Runtime red lines must be covered by executable self-checks, not only prose.

## Factory Red Lines

- Factory is developer/training mode only.
- Automatic exploration must use SafeActionPolicy.
- Model output can only create candidates or patch proposals.
- Model output must never write directly to Approved repositories.
- One successful run cannot promote a skill.
- Promotion requires static safety checks, dry run, repeated tests, reports, and PromotionGate approval.
- Dangerous domains such as payment, deletion, publishing, authorization, account changes, and uploads are blocked by default.
- PromotionGate approval is a decision artifact. It must not directly write into the Approved repository.

## Data Red Lines

- Training samples are local and sensitive by default.
- Samples must be deletable.
- Export requires explicit confirmation.
- Personal data must be redacted before export or training outside the device.
- Interrupted or unsafe samples must not enter the clean training set.

## Current Executable Checks

`ArchitectureSelfCheck` currently verifies:

- Runtime rejects non-approved skills.
- Runtime rejects capability D.
- Runtime rejects text tasks with no input channel.
- Runtime rejects implicit capability C execution unless low confidence is explicitly allowed.
- PromotionGate rejects dangerous templates.
- PromotionGate rejects candidates with fewer than 10 test runs.
