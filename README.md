# XM Headphones

A [Hammerhead Karoo](https://www.hammerhead.io/) extension for Sony headphones. It talks to the
headset over BLE with Sony's proprietary protocol and puts the controls you would otherwise reach
for the Sound Connect app onto the Karoo, plus data fields for the ride.

## Modules

| Module     | Type               | What it is                                                                                     |
| ---------- | ------------------ | ---------------------------------------------------------------------------------------------- |
| `protocol` | Pure Kotlin/JVM    | The Sony frame codec and the command/payload definitions. No Android types, so it can back any client. |
| `client`   | Android library    | The BLE transport and GATT session lifecycle, exposed as a coroutine `Flow` of headset state.   |
| `app`      | Android app        | The Karoo extension: data fields, the settings/control screen and the in-ride alerts.           |

`protocol` and `client` are published so a third-party app (for example a Wear OS companion) can
drive a Sony headset without reimplementing the protocol. See **Using the client** below.

## Using the client

```kotlin
// Resolved from JitPack, or from GitHub Packages with a token.
dependencies {
    implementation("com.github.nikosavola.karoo-xm-headphones:client:<tag>")
}
```

```kotlin
val client = XmHeadphonesClient.get(context)
client.start() // begins scanning and connecting
client.state.collect { state -> /* connection, battery, noise cancelling, ... */ }
client.setNoiseCancelling(NoiseCancelling.Ambient) // one-shot command
```

## Building

```sh
./gradlew :app:assembleDebug    # the extension APK
./gradlew test                  # all unit tests
```

Requires JDK 17+ and an Android SDK with platform 37. Put `sdk.dir` in `local.properties`.

## Credits

The protocol was worked out from these reverse-engineering projects:

- [marconvcm/sony-device-center](https://github.com/marconvcm/sony-device-center)
- [mos9527/SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient)
- [AmitRajput-Dev/SonyBridge](https://github.com/AmitRajput-Dev/SonyBridge)
- [VyomJain6904/sony-headphones-linux](https://github.com/VyomJain6904/sony-headphones-linux)

## Licence

Apache-2.0.
