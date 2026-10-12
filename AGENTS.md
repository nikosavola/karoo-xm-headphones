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

## Testing

Tests run under Robolectric against [karoo-ext-testing](https://github.com/nikosavola/karoo-ext-testing),
which supplies the fake Karoo system and a fake BLE stack (`FakeBle`, `FakeBlePeripheral`). Prefer
driving the real client against a simulated headset over mocking the client away.
