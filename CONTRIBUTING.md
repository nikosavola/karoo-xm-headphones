# Contributing

## Development setup

You need an Android SDK with platform 37 and `sdk.dir` in `local.properties`. Gradle runs on JDK 21, pinned in `gradle/gradle-daemon-jvm.properties`; on JDK 27 the library publication breaks.

Install the pre-commit hooks so formatting and lint run on every commit:

```sh
pre-commit install
```

## Building and testing

```sh
./gradlew :app:assembleDebug    # the extension APK
./gradlew test                  # every module's tests
```

The tests run under Robolectric against a fake Karoo system. Drive the real client against a simulated headset rather than mocking the client away, and assert geometry rather than only text where a screen is involved: a squeezed label still holds the string it was given.

## Screenshots

`ControlScreenScreenshotTest` and `NoiseCancellingFieldScreenshotTest` draw the README's images into `app/build/screenshots/`. To copy them into `docs/screenshots/`:

```sh
./gradlew :app:recordScreenshots
```

That task is the only thing that writes there.

## Module rules

- `protocol` takes no Android types. It is published on its own so any JVM target can use it.
- `client` is consumed by other repos, so its public API is deliberate.
- The extension id lives in both `res/xml/extension_info.xml` and `XmHeadphonesExtension.EXTENSION_ID`; keep them in sync.

## Running the hooks

```sh
pre-commit run --all-files
```

## AI usage policy

The use of AI tools to accelerate your development workflow, whether for prototyping, writing tests, or improving documentation, is **encouraged**.

However, as a contributor, you remain **fully responsible** for the code and content you submit. Please ensure the following:

1. **No "AI Slop"**: Do not submit unreviewed, low-quality, or redundant AI-generated content.
1. **Verify & Test**: All AI-generated code must be reviewed, tested, and verified to work as intended.
1. **Maintainability**: The content must be clear, idiomatic, and maintainable by a human.
