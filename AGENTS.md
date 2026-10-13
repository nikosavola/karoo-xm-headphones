# AGENTS.md - XM Headphones

A Hammerhead Karoo extension that controls Sony headphones over classic Bluetooth, plus the protocol
and client libraries behind it.

## Layout

| Module     | Plugin              | Contents                                                                        |
| ---------- | ------------------- | ------------------------------------------------------------------------------- |
| `protocol` | `kotlin.jvm`        | Sony frame codec, opcodes and payload codecs. No Android types at all.           |
| `client`   | `android.library`   | The classic Bluetooth transport, connection lifecycle, `XmHeadphonesClient`. Depends on `protocol`. |
| `app`      | `android.application` | The Karoo extension, its data types, bonus actions and the control screen.      |

## Rules

- `protocol` stays free of Android imports. It is published on its own for other clients (a Wear OS
  app is planned), so it has to work on any JVM target.
- `client` keeps its public API deliberate: other repos consume it.
- Never add Android types to `protocol` to make a caller simpler. Put the Android part in `client`.
- `client` owns the list of permissions reaching a paired headset needs (`requiredPermissions`). A
  caller asks it rather than keeping its own copy, because a copy that drifts leaves a screen asking
  for something nothing can grant. `ManifestPermissionsTest` pins the app's manifest to that list at
  every API level the extension supports.
- The extension id (`xm-headphones`) is in two places: `res/xml/extension_info.xml` and
  `XmHeadphonesExtension.EXTENSION_ID`. Keep them in sync. The data type ids in `extension_info.xml`
  must match the constants in `XmHeadphonesDataType`.
- The app is XM Headphones, but names for the wire format keep Sony in them (`SonyRequest`,
  `SonySession`, `SonyCapabilities`), because it is Sony's protocol they describe. Only the app's own
  names take `XmHeadphones`.

## Build

```sh
./gradlew :app:assembleDebug
./gradlew test
```

AGP 9 has Kotlin built in, so do **not** apply `org.jetbrains.kotlin.android` in the Android modules.
The serialization plugin still has to be applied explicitly, at the Kotlin version pinned in the root
`buildscript` block.

The Gradle daemon runs on JDK 21, pinned in `gradle/gradle-daemon-jvm.properties`. On JDK 27 one of
AGP's tasks fails to initialise: the app still builds, but the library publication breaks, so it shows
up only when publishing.

`protocol` and `client` set their own `group` and `version`. Without them the publication lands under
the root project's name at version `unspecified`, which no consumer can resolve.

## Testing

Tests run under Robolectric against
[karoo-ext-testing](https://github.com/nikosavola/karoo-ext-testing), which supplies a fake Karoo
system. Drive the real client against a simulated headset rather than mocking the client away: the
fakes write the wire format by hand, so they disagree with the code under test when the code is wrong.

The channel itself is the one thing a fake replaces, through `HeadsetTransportFactory` on the client.
Everything above it is the real code, and the socket underneath it is covered separately by
`RfcommTransportTest`, which drives Robolectric's own Bluetooth stack.

The harness is pinned to 0.1.0 because JitPack's build of 0.1.1 fails. Its fake BLE stack is no longer
used here, since the headset is reached over classic Bluetooth, and with it went the reason the client
suite could not run below SDK 33. That suite now runs at 32 and 34, the first being the platform the
Karoo in hand reports.

The README's screenshots are drawn by `ControlScreenScreenshotTest` as lossless WebP into
`app/build/screenshots/`, never over the tracked copies, so a test run leaves the tree clean on any
machine. `./gradlew :app:recordScreenshots` runs the tests and copies the result into
`docs/screenshots/`, and is the only thing that writes there. CI renders them and reports a
disagreement rather than failing on it, because the committed images came from macOS and that runner
has never been compared with it. Run it with the test task excluded and it copies nothing and still
reports success, since Gradle skips an empty copy before its own actions, and the images are simply
left as they were.

Text alone is not enough to call an image right. The first version of that test published a screen
whose chip labels had been squeezed to one letter per line, which no text assertion can see. Each
state's test also checks that the slider and the switch exist, which control is selected, and that no
label wrapped. A squeezed chip grows several times taller than the 32dp one line needs, and a wrapped
segment takes the mode row past the 48dp it is when it fits. Assert geometry, not just presence.

Two things to know before writing one of those checks, both of which cost a round of getting it
wrong. A clickable node is padded out to the 48dp a finger needs, so measuring the merged node says
nothing about whether the text inside it wrapped; measure the unmerged tree. And a weighted child of
a Row is measured against its share of the row rather than the whole of it, which is enough on its
own to wrap a line that fits perfectly well once the weight comes off. The headset name is the one
that caught both: a model name and a firmware version is within a character or two of the Karoo's
256dp, so it is unweighted and a size down from the body default.

Colour is the same class of thing, so the battery gauge is read back from its own pixels: a fuller
gauge than the one a nearly flat headset draws, in green, is what says the level reached the drawing
rather than only the string beside it.

Two things the screen never shows are pinned by drawing them instead. The activity theme decides
what the framework draws above and behind the screen: a title bar holding the activity label over a
screen that says the same name in its own headline, and a window background that flashes a colour
the app does not use at launch and behind the Android 12 splash. The launcher icon is drawn by
Karoo's launcher rather than by this app, so the test renders it and measures where the glyph lands
in the 72dp an adaptive icon keeps clear of whatever mask it is cut with.

The render is 480x800 at 280 dpi, set by `@Config(qualifiers = ...)`, on API 33. The Karoo in hand
reports the same size at 300 dpi and API 32, so its viewport is a little narrower and its platform
older than the committed images assume. One test overrides the qualifiers to the Karoo's own width
and repeats the mode and chip checks there without writing an image, since that is the width the rider
sees and the committed images are not recorded at it. It asserts the width it rendered at, so it cannot drift
back to 274dp and keep passing. Capturing more than a viewport tall does not work here:
Robolectric paints only a window-sized band, so the lower half of the screen is a separate scrolled
capture. `@GraphicsMode(NATIVE)` is what makes the rendering work at all.

Tests that render a custom view need
`@Config(instrumentedPackages = ["io.hammerhead.karooext.internal"])` and `RobolectricPump.advanceBy`
between frames, because the view emitter drops an update that lands within 900 ms of the last one.
