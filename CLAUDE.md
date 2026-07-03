# Claude Code Guidelines

## Workflow Rules

- **Strict Branching:** NEVER change anything directly on the `main` branch.
- **Development Branch:** ALWAYS make changes on the `staging` branch.
- **Production Deploy:** ONLY merge/push to `main` when explicitly instructed by the user.

## Version Requirements

- Always verify that SDK versions, dependencies, and API targets are current before making changes
- Search online for the latest stable versions of dependencies before updating
- Google Play requires new apps and updates to target a recent API level (Play raises this each year). This app currently uses `targetSdk = 36` and `compileSdk = 37` — check the current Play requirement before changing.

## Key Files

- `android/app/build.gradle` - SDK versions and dependencies
- `.github/workflows/` - GitHub Actions CI/CD workflows
- `android/app/src/main/java/to/schauer/schautrack/MainActivity.kt` - Main app code

## CI/CD

- Pull requests are validated by `.github/workflows/ci.yml` (assemble + lint on `android/**` changes); keep lint error-clean so this stays green
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
