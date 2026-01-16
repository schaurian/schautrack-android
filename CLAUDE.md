# Claude Code Guidelines

## Workflow Rules

- **Strict Branching:** NEVER change anything directly on the `main` branch.
- **Development Branch:** ALWAYS make changes on the `staging` branch.
- **Production Deploy:** ONLY merge/push to `main` when explicitly instructed by the user.

## Version Requirements

- Always verify that SDK versions, dependencies, and API targets are current before making changes
- Search online for the latest stable versions of dependencies before updating
- Google Play requires targeting the latest API level (currently API 35 for 2025)

## Key Files

- `android/app/build.gradle` - SDK versions and dependencies
- `.github/workflows/` - GitHub Actions CI/CD workflows
- `android/app/src/main/java/to/schauer/schautrack/MainActivity.kt` - Main app code

## CI/CD

- GitHub Actions workflows handle building and publishing to Google Play
- Required GitHub secrets for Google Play publishing:
  - `PLAY_SERVICE_ACCOUNT_JSON_B64`
  - `ANDROID_UPLOAD_KEYSTORE_B64`
  - `ANDROID_UPLOAD_KEYSTORE_PASS`
  - `ANDROID_UPLOAD_KEY_ALIAS`
  - `ANDROID_UPLOAD_KEY_PASS`

## Deprecation Checks

- When updating SDK versions, check for deprecated APIs and update to modern alternatives
- Always search online for current best practices when encountering deprecation warnings
