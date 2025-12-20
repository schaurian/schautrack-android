# Claude Code Guidelines

## Version Requirements

- Always verify that SDK versions, dependencies, and API targets are current before making changes
- Search online for the latest stable versions of dependencies before updating
- Google Play requires targeting the latest API level (currently API 35 for 2025)

## Key Files

- `android/app/build.gradle` - SDK versions and dependencies
- `.gitlab-ci.yml` - CI/CD pipeline and Docker image version
- `android/app/src/main/java/to/schauer/schautrack/MainActivity.kt` - Main app code

## CI/CD

- The CI image version should match the target SDK (e.g., `ghcr.io/cirruslabs/android-sdk:35`)
- Required GitLab CI/CD variables for Google Play publishing:
  - `PLAY_SERVICE_ACCOUNT_JSON_B64`
  - `ANDROID_UPLOAD_KEYSTORE_B64`
  - `ANDROID_UPLOAD_KEYSTORE_PASS`
  - `ANDROID_UPLOAD_KEY_ALIAS`
  - `ANDROID_UPLOAD_KEY_PASS`

## Deprecation Checks

- When updating SDK versions, check for deprecated APIs and update to modern alternatives
- Always search online for current best practices when encountering deprecation warnings
