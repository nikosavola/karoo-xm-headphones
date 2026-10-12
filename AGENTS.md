# AGENTS.md — XM Headphones

A Hammerhead Karoo extension that controls Sony headphones over BLE, plus the reusable protocol
and client libraries behind it.

## Layout

| Module     | Plugin              | Contents                                                                        |
| ---------- | ------------------- | ------------------------------------------------------------------------------- |
| `protocol` | `kotlin.jvm`        | Sony frame codec, opcodes and payload codecs. No Android types at all.           |
| `client`   | `android.library`   | GATT transport, connection lifecycle, `XmHeadphonesClient`. Depends on `protocol`. |
| `app`      | `android.application` | The Karoo extension, its data types, bonus actions and the control screen.      |

## Rules

- `protocol` must stay free of Android imports. It is published standalone for third-party clients
  (a Wear OS app is planned) and must remain usable from any JVM target.
- `client` exposes its public API deliberately: it is consumed outside this repo.
- Never add Android-only types to `protocol` to make a caller simpler; put the Android-specific
  part in `client`.
- The extension id (`xm-headphones`) is baked into `AndroidManifest.xml`,
  `res/xml/extension_info.xml` and `XmHeadphonesExtension.EXTENSION_ID`. Keep them in sync; the
  data type ids in `extension_info.xml` must match the constants in `XmHeadphonesDataType`.

## Build

```sh
./gradlew :app:assembleDebug
./gradlew test
```

AGP 9 has Kotlin built in: do **not** apply `org.jetbrains.kotlin.android` in the Android modules.
The serialization plugin still has to be applied explicitly and must match the Kotlin version
pinned in the root `buildscript` block.

The Gradle daemon runs on JDK 21, pinned in `gradle/gradle-daemon-jvm.properties`. On JDK 27 one of
AGP's tasks fails to initialise and the library publication breaks, while the app still builds, so the
failure shows up only when publishing.

`protocol` and `client` set their own `group` and `version`; without them the publication lands under
the root project's name at version `unspecified`, which no consumer can resolve.

## Testing

Tests run under Robolectric against [karoo-ext-testing](https://github.com/nikosavola/karoo-ext-testing),
which supplies the fake Karoo system and a fake BLE stack (`FakeBle`, `FakeBlePeripheral`). Prefer
driving the real client against a simulated headset over mocking the client away: the fakes in the
tests are written to the wire format by hand, so they disagree with the code under test when it is
wrong.

The harness is pinned to 0.1.0 because JitPack's build of 0.1.1 fails. On that version the API-33
notification callback is the one that carries the value, so the client overrides both variants; a
newer harness would not change that, since Karoo 2 is API 26.

The README's screenshots are recorded by `ControlScreenScreenshotTest`, which writes lossless WebP
into `docs/screenshots/`. Running `./gradlew :app:testDebugUnitTest --tests '*ControlScreenScreenshotTest'`
regenerates them, and every `./gradlew test` rewrites them too, so they cannot go stale on the
machine that recorded them. That is also the hazard: on a host whose rasterization differs, a test
run leaves six tracked files modified. CI reports that rather than failing on it.

Assert text alone is not enough to call an image right: the first version of that test published a
screen whose chip labels had been squeezed to one letter per line, which no text assertion can see.
Each state's test now also checks that the slider and the switch exist, which chip is selected, and
that no chip's label wrapped (a chip squeezed narrow grows several times taller than the 32dp one
line needs). Assert geometry, not just presence.

The render is the Karoo 3's own 480x800 at 280 dpi, set by `@Config(qualifiers = ...)`, on API 33
because that is the Karoo 3's platform. Capturing more than a viewport tall does not work here:
Robolectric paints only a window-sized band, so the lower half of the screen is a separate scrolled
capture. `@GraphicsMode(NATIVE)` is what makes the rendering work at all.

Tests that render a custom view need `@Config(instrumentedPackages = ["io.hammerhead.karooext.internal"])`
and `RobolectricPump.advanceBy` between frames: the view emitter drops an update that lands within
900 ms of the last one.
