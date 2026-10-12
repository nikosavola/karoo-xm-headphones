# XM Headphones

A [Hammerhead Karoo](https://www.hammerhead.io/) extension for Sony headphones. It talks to the
headset over BLE with Sony's own protocol and puts the controls you would otherwise reach for the
Sound Connect app onto the Karoo, plus a few data fields for the ride.

## What it does

On a ride page:

- **Headset battery** as a number.
- **Noise cancelling** as text (off, cancelling, ambient, ambient with voice) — a custom field, since
  the mode has no number to show.
- **Ambient level** as a number.
- A **controller action** that toggles noise cancelling, assignable to a button.
- An in-ride alert when the headset drops.

In the app screen: the three noise control modes, the ambient level, focus-on-voice, the equalizer
preset, the headset's model and firmware, and forgetting the headset so a different one is picked up.

## Modules

| Module     | Type             | What it is                                                                                     |
| ---------- | ---------------- | ---------------------------------------------------------------------------------------------- |
| `protocol` | Pure Kotlin/JVM  | The Sony frame codec and the command/payload definitions. No Android types, so it can back any client. |
| `client`   | Android library  | The BLE transport and GATT session, exposed as a `StateFlow` of headset state plus suspend commands. |
| `app`      | Android app      | The Karoo extension: data types, the controller action, the alert and the control screen.        |

`protocol` and `client` are published so a third-party app (a Wear OS companion, say) can drive a Sony
headset without reimplementing the protocol.

## Using the client

```kotlin
dependencies {
    // JitPack builds the tags, so this needs no token.
    implementation("com.github.nikosavola.karoo-xm-headphones:client:<tag>")
    // Or GitHub Packages, which does:
    // implementation("fi.nikosavola.xmheadphones:client:<version>")
}
```

Both libraries are published; `protocol` carries no Android types, so a non-Android client can take
just that one.

```kotlin
val client = XmHeadphonesClient.get(context)
client.start() // begins looking for a headset
client.state.collect { state -> /* link, battery, noise cancelling, ... */ }
client.setNoiseCancelling(NoiseCancelling.Ambient(level, focusOnVoice = false))
```

Every command returns false rather than throwing when there is no link, or when the headset never
advertised the feature, so a caller that is merely early does not have to handle an exception.

## Building

```sh
./gradlew :app:assembleDebug    # the extension APK
./gradlew test                  # every module's tests
```

Needs an Android SDK with platform 37, with `sdk.dir` in `local.properties`. Gradle runs on JDK 21,
which `gradle/gradle-daemon-jvm.properties` pins and the foojay resolver provisions; a JDK 27 daemon
fails to initialise one of AGP's tasks, so `:client:bundleReleaseAar` and anything that depends on it
(the publication) breaks without that pin.

## What has and has not been checked against real hardware

**Nothing here has been run against a real headset.** No device was available while it was written,
which shapes what follows.

Checked against hardware, via the packet captures committed to
[mos9527/SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient):

- the frame layer — markers, escaping, checksum, length — round-trips every packet of a WH-1000XM3
  session byte for byte;
- the V1 handshake replies and the V1 noise-control and battery reports decode to the states that
  headset was actually in.

Not checked:

- **Everything in the write direction.** The set frames are built from the payload definitions in
  that project, field for field, but no capture of a headset being told to change mode was available.
- **The V2 layouts** (WH-1000XM5, WF-1000XM4/XM5, WH-CH720N and the rest). They come from the same
  generated headers, cross-checked against Mercury000/SonyPods, which read them out of the Sound
  Connect app.
- **Whether V1 headsets answer on BLE at all.** On a desktop they are driven over the classic serial
  profile. Their BLE channel is the third service `SonyGatt` lists, and no reference implementation
  has confirmed that a V1 headset answers there. If the extension connects to an XM3 or XM4 but never
  gets a handshake, that is the first thing to look at.
- **Whether every model advertises the same GATT table.** The UUIDs are Sony's, taken from the
  SongPal app by two independent projects, but only the two services this client uses are tried.

The code fails closed at every point where this matters: a headset that does not answer is reported as
such rather than guessed at, and a reply that does not fit its shape is logged and acknowledged rather
than acted on.

## Credits

The protocol was worked out from these projects, none of which is Sony's:

- [mos9527/SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient) — the payload
  definitions and the packet captures the tests replay
- [Mercury000/SonyPods](https://github.com/Mercury000/SonyPods) — a byte-level audit of the Sound
  Connect app, and the GATT table
- [marconvcm/sony-device-center](https://github.com/marconvcm/sony-device-center)
- [AmitRajput-Dev/SonyBridge](https://github.com/AmitRajput-Dev/SonyBridge)
- [VyomJain6904/sony-headphones-linux](https://github.com/VyomJain6904/sony-headphones-linux)

Testing uses [nikosavola/karoo-ext-testing](https://github.com/nikosavola/karoo-ext-testing), which
supplies the fake Karoo system and the simulated BLE stack.

## Licence

Apache-2.0.
