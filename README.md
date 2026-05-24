# snorb / MacroPilot

MacroPilot is an Android automation prototype focused on app-side AI planning, Skill JSON generation, accessibility-driven execution, input-method support, dry-run checks, runtime reports, and UI exploration records.

## Project Layout

- `app/` - Android application source.
- `docs/` - architecture notes, verification reports, and task catalogs.
- `tools/` - local evidence and verification helper scripts.
- `gradle/` - Gradle wrapper files.

## Build

```powershell
./gradlew :app:assembleDebug
```

Local files such as `local.properties`, build outputs, APKs, screenshots, flow reports, and device evidence artifacts are intentionally excluded from Git.
