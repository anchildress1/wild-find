# 🌿 wild-find

wild-find sends kids 8 and up outside to find and photograph plants that grow near them. Open-weight models on the phone check each photo. No photo ever leaves the device.

**Look. Photograph. Leave it where it grows.**

## How it works

- One iNaturalist query per hunt picks targets that grow nearby this month
- Each target shows its common name and plant type
- When the kid taps Capture, TinyCLIP checks that the camera is on a plant and BioCLIP 2.5 Mobile checks that it's the target

The full spec is in [docs/PRD.md](docs/PRD.md). The build queue is in [docs/stories.md](docs/stories.md).

## Setup

You need SDKMAN!, the Android SDK, uv, lefthook, ktlint, detekt, actionlint, and gitleaks.

```sh
sdk env install
make setup
make assets   # bundled models and tables; every APK build needs them (first run pulls about 160 MB of pinned files)
make ai-checks
make install   # debug APK to a connected phone
```

## Release build

The release key stays on your machine. Create it once, outside the repo:

```sh
mkdir -p ~/keys && chmod 700 ~/keys   # keytool won't create the folder
keytool -genkeypair -v -keystore ~/keys/wild-find-release.jks -alias wild-find -keyalg RSA -keysize 4096 -validity 10000
```

Then add `keystore.properties` at the repo root (gitignored) and build:

```properties
storeFile=/Users/<you>/keys/wild-find-release.jks
storePassword=...
keyAlias=wild-find
keyPassword=...
```

```sh
./gradlew :app:assembleRelease   # signed APK in app/build/outputs/apk/release/
```

Without `keystore.properties`, the release APK builds unsigned.

## Credits

- **Models on the phone:** BioCLIP 2.5 Mobile and TinyCLIP ViT-8M/16
- **Data:** iNaturalist, Wikipedia, USDA PLANTS, GBIF, and Natural Earth
- **Build-time only:** BioCLIP 2.5 writes the tutorial label vectors on a laptop with OpenCLIP, and Gemma 4 (26b, through Ollama) writes the hints and the contact-hazard list from Wikipedia; none of them runs in the app

## License

[MIT](LICENSE)
