# Publishing Schautrack on F-Droid (IzzyOnDroid)

This app is distributed on [IzzyOnDroid](https://apt.izzysoft.de/fdroid/), a
popular third-party F-Droid repository. IzzyOnDroid pulls the **signed APK
that this repo attaches to each GitHub Release**, so installs from Google Play
and from IzzyOnDroid share the same signing key and update interchangeably.

## Why IzzyOnDroid (and not the official F-Droid repo)

- It uses **your** signing key (the APK from GitHub Releases), so users can
  switch between the Play Store build and the F-Droid build without
  reinstalling. The official F-Droid repo rebuilds from source and re-signs
  with F-Droid's key, which breaks cross-updates.
- Approval takes days, not weeks.
- It is a common stepping stone to the official F-Droid repo later.

## What is already in place

- **Public AGPL-3.0 source:** <https://github.com/schaurian/schautrack-android>
- **Signed prod APK on every GitHub Release**
  (`schautrack-<version>.apk`), produced by `.github/workflows/release.yml`.
- **Monotonic `versionCode`** (a Unix timestamp from `date +%s`), which
  IzzyOnDroid requires for update detection.
- **Fastlane metadata** in `fastlane/metadata/android/en-US/`
  (title, descriptions, screenshots, changelog). IzzyOnDroid reads this
  automatically to build the listing. The app icon is taken from the APK.

No build changes are needed.

## How to request inclusion (one-time, manual)

> **Note:** IzzyOnDroid moved app inclusion requests off GitLab. They are now
> filed on **Codeberg**, using a Forgejo issue form. A Codeberg account is
> required, so this must be done by a maintainer.

1. Go to <https://codeberg.org/IzzyOnDroid/repodata/issues/new?template=.forgejo%2Fissue_template%2Fapp-inclusion-request.yaml>
   (or open a new issue in `IzzyOnDroid/repodata` and pick the
   **"App Inclusion Request"** template).
2. Fill in the form (a ready-to-paste version is below). Keep the app name in
   the title: `[AppRequest] Schautrack`.
3. Submit. A maintainer reviews, the bot runs an on-device test and an APK scan
   for trackers/proprietary blobs, and the app usually appears within a few days at:
   <https://apt.izzysoft.de/fdroid/index/apk/to.schauer.schautrack>

The inclusion policy is at <https://izzyondroid.org/docs/general/AppInclusionPolicy/>.

### Ready-to-paste request

```
Title: [AppRequest] Schautrack

Guidelines:
[x] I am the developer of the app
[x] The app complies with the App Inclusion Policy
[x] The app is not already listed in the repo or issue tracker
[x] The Fastlane folder is available in the app's repo

Link to the source code: https://github.com/schaurian/schautrack-android
Link to app in another app store: https://play.google.com/store/apps/details?id=to.schauer.schautrack
License used: AGPL-3.0
Categories: Sports & Health, Food

Summary:
Native Android app for Schautrack, a self-hostable, open-source, AI-powered
calorie and weight tracker for you and your friends.

Description:
Schautrack is the native Android app for Schautrack, a self-hostable,
open-source calorie tracker. Track what you eat, set daily goals, and watch
your weight, with optional AI nutrition estimation from a single food photo.
It wraps the Schautrack web app in a native shell and adds camera access for
food scanning and barcode lookups. By default it connects to schautrack.com,
but you can point it at your own server from the login screen.

Build instructions:
  git clone https://github.com/schaurian/schautrack-android
  cd schautrack-android/android
  ./gradlew assembleProdRelease
  # Output: app/build/outputs/apk/prod/release/schautrack-<version>.apk
A signed prod APK is also attached to every GitHub Release.

AI tools:
- Assistance Level: Moderate - used for specific tasks or modules
- Tool(s): Claude
- What it helped with: boilerplate, debugging, documentation, and the
  F-Droid/Fastlane metadata
- [x] Reviewed and edited all AI-generated outputs
- [x] Manually tested and verified all changes

Further Notices:
- versionCode is a Unix timestamp, so it increases monotonically.
- Anti-feature NonFreeNet applies: by default the app connects to the hosted
  instance at schautrack.com, and AI food estimation uses external providers
  (OpenAI/Claude) unless a local model is configured. The app is fully
  self-hostable, so users can avoid all non-free network services.
- No Google Play Services, no Firebase, no proprietary SDKs
  (dependencies: AndroidX + Google Material only).
```

## After acceptance

Add an IzzyOnDroid badge to the README (next to the Google Play badge):

```markdown
<a href="https://apt.izzysoft.de/fdroid/index/apk/to.schauer.schautrack">
  <img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height="80">
</a>
```

## Keeping it updated

Nothing to do per release: when CI publishes a new GitHub Release with a
`schautrack-<version>.apk` asset, IzzyOnDroid's bot detects it and ships the
update automatically.

To show a per-version changelog on the F-Droid listing, add a file named
after that release's `versionCode` (the timestamp) under
`fastlane/metadata/android/en-US/changelogs/`, e.g.
`fastlane/metadata/android/en-US/changelogs/1781273457.txt`. If no matching
file exists, the listing simply omits the changelog for that version.
