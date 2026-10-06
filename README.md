# 🌿 wild-find

wild-find sends kids 8 and up outside to find and photograph plants that grow near them in west Georgia. Open-weight models on the phone check each photo and write hints. No photo ever leaves the device.

**Look. Photograph. Leave it where it grows.**

## How it works

- One iNaturalist query per hunt picks targets that grow nearby this month
- While the camera is open, TinyCLIP checks that the kid is pointing at a plant, the camera's focus says whether to walk closer, and BioCLIP 2.5 Mobile checks that it's the right plant group
- Gemma 4 E2B writes hints when the kid asks for one

The full spec is in [docs/PRD.md](docs/PRD.md). The build queue is in [docs/stories.md](docs/stories.md).

## Setup

You need SDKMAN!, the Android SDK, uv, lefthook, ktlint, detekt, shellcheck, actionlint, and gitleaks.

```sh
sdk env install
cp .env.example .env   # local overrides; never committed
make setup
make ai-checks
make install   # debug APK to a connected phone
make fetch-models push-models   # sideload the pinned models to that phone
```

## Credits

Gemma, BioCLIP 2.5 Mobile, BioCLIP 2.5, TinyCLIP, OpenCLIP, iNaturalist, and Wikipedia.

## License

[MIT](LICENSE)
