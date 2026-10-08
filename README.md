# 🌿 wild-find

wild-find sends kids 8 and up outside to find and photograph plants that grow near them. Open-weight models on the phone check each photo. No photo ever leaves the device.

**Look. Photograph. Leave it where it grows.**

## How it works

- One iNaturalist query per hunt picks targets that grow nearby this month
- Each target shows its common name
- When the kid taps Capture, TinyCLIP checks that the camera is on a plant and BioCLIP 2.5 Mobile checks that it's the target

The full spec is in [docs/PRD.md](docs/PRD.md). The build queue is in [docs/stories.md](docs/stories.md).

## Setup

You need SDKMAN!, the Android SDK, uv, lefthook, ktlint, detekt, actionlint, and gitleaks.

```sh
sdk env install
cp .env.example .env   # local overrides; never committed
make setup
make assets   # bundled models and tables; every APK build needs them (first run pulls about 160 MB of pinned files)
make ai-checks
make install   # debug APK to a connected phone
```

## Credits

BioCLIP 2.5 Mobile, BioCLIP 2.5, TinyCLIP, OpenCLIP, iNaturalist, Wikipedia, USDA PLANTS, and GBIF.

## License

[MIT](LICENSE)
