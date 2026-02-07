<p align="center">
  <img src="assets/app-icon.png" alt="Schautrack" width="120" height="120">
</p>

<h1 align="center">Schautrack Android</h1>

<p align="center">
  The Android app for <a href="https://schautrack.com">Schautrack</a> - a self-hostable, open-source, AI-powered calorie tracker for you and your friends.
</p>

<p align="center">
  <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Coming soon to Google Play" height="80" style="opacity: 0.5; filter: grayscale(100%);">
</p>

> **Want to test?** The app is currently in closed testing. Write "hi" with your Google Play email to [getschautrackapp@schauer.to](mailto:getschautrackapp@schauer.to) and I'll add you and send you the link. I really appreciate early testers - this will change to open access once we have enough users!

## About

This is a native Android wrapper for the Schautrack web application. It provides a seamless mobile experience with native features like camera access for food scanning.

By default, the app connects to [schautrack.com](https://schautrack.com), but you can change the server URL on the login screen to use your own self-hosted instance.

## Screenshots

<p align="center">
  <img src="assets/screenshot-1.png" width="200">
  <img src="assets/screenshot-2.png" width="200">
  <img src="assets/screenshot-3.png" width="200">
</p>

## Related Projects

- [Schautrack](https://github.com/schaurian/schautrack)

## Building

```bash
cd android
./gradlew assembleProdDebug
```

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

AGPL-3.0 - See [LICENSE](LICENSE) for details.
