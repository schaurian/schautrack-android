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

IzzyOnDroid inclusion requests are filed on their GitLab. This step needs a
GitLab account, so it must be done by a maintainer.

1. Go to <https://gitlab.com/IzzyOnDroid/repo/-/issues/new>.
2. Pick the **"Request packaging"** issue template.
3. Fill in the details (a ready-to-paste version is below).
4. Submit. The IzzyOnDroid maintainer reviews, the bot scans the APK for
   trackers/proprietary blobs, and the app usually appears within a few days at:
   <https://apt.izzysoft.de/fdroid/index/apk/to.schauer.schautrack>

### Ready-to-paste request

```
App name: Schautrack
Package ID: to.schauer.schautrack
Source code: https://github.com/schaurian/schautrack-android
License: AGPL-3.0
Upstream/website: https://schautrack.com
Issue tracker: https://github.com/schaurian/schautrack-android/issues

Release channel: GitHub Releases
APK asset pattern: schautrack-<version>.apk (prod flavor, release build, signed)
Latest release: https://github.com/schaurian/schautrack-android/releases/latest

Notes:
- Native WebView wrapper for the open-source Schautrack web app; adds camera
  access for food scanning and barcode lookups.
- Fully FOSS, no Google Play Services, no Firebase, no proprietary SDKs
  (deps: AndroidX + Google Material only).
- Fastlane metadata (title, descriptions, screenshots, changelog) is in the
  repo at fastlane/metadata/android/en-US/.
- versionCode is a Unix timestamp, so it increases monotonically.

Anti-features (please flag as appropriate):
- NonFreeNet: by default the app connects to the hosted instance at
  schautrack.com, and AI food estimation uses external providers
  (OpenAI/Claude) unless a local model is configured. The app is
  self-hostable, so users can avoid all non-free network services.
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
