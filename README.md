<p align="center">
  <img src="assets/app-icon.png" alt="Schautrack" width="120" height="120">
</p>

<h1 align="center">Schautrack Android</h1>

<p align="center">
  The official Android app for <a href="https://schautrack.schauer.to">Schautrack</a> - an open-source, AI-powered calorie tracker.
</p>

<p align="center">
  <a href="https://play.google.com/apps/testing/to.schauer.schautrack">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">
  </a>
</p>

## About

This is a native Android wrapper for the Schautrack web application. It provides a seamless mobile experience with native features like camera access for food scanning.

By default, the app connects to [schautrack.schauer.to](https://schautrack.schauer.to), but you can change the server URL on the login screen to use your own self-hosted instance.

## Screenshots

<p align="center">
  <img src="assets/screenshot-1.png" width="200">
  <img src="assets/screenshot-2.png" width="200">
  <img src="assets/screenshot-3.png" width="200">
</p>

## Related Projects

- [Schautrack Web App](https://schautrack.schauer.to)

## Building

```bash
cd android
./gradlew assembleProdDebug
```

## License

AGPL-3.0 - See [LICENSE](LICENSE) for details.
