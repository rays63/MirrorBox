# MirrorBox

Mirrorbox turns an Android TV box into an AirPlay and DLNA receiver. Mirror your iPhone, iPad or Mac screen with sound, or play videos from any DLNA app.

## Features

- **AirPlay screen mirroring** from iPhone, iPad and Mac, with sound (Control Centre → Screen Mirroring).
- **AirPlay audio** from any app's AirPlay button (Music, Spotify, podcasts), with title, artist and cover art on the TV.
- **DLNA renderer**: play a video, song or photo from apps like BubbleUPnP, VLC or Windows "Cast to Device",
  with play/pause, seek, stop and volume from the app.
- **No ads, no time limits, no account.**
- **Runs on modest hardware**: built for a 32-bit ARM Android 10 box with 2 GB RAM and no Google services.
  Video is decoded by the TV's hardware decoder; idle, it uses about 15 MB of memory and no CPU.
- **Always ready**: starts at boot and stays discoverable in the background.
- **Rename the TV** from the app (default name: **NetTV**).

## Why no Google Cast or Miracast?

- **Google Cast** receivers need device certificates that Google only issues to certified hardware makers,
  so no app can be a Chromecast target.
- **Miracast** needs Android's Wi-Fi Display system APIs, which only the system itself can use.

If your TV has these built in, they keep working alongside MirrorBox.

Not supported yet: AirPlay *video casting* from the YouTube app or Safari ("AirPlay video") and 4K/H.265 mirroring.

## Build

Needs, on macOS:

- Android SDK with build-tools 36.1.0 and platform `android-36.1` (`~/Library/Android/sdk`, or set `ANDROID_HOME`)
- Android NDK (any recent one under `sdk/ndk/`)
- Android Studio (its bundled JDK is used)
- git (the first build downloads and compiles OpenSSL into `build-cache/`, a few minutes)

```sh
./build.sh
```

This produces `mirrorbox.apk` (armeabi-v7a; also runs on 64-bit ARM). No Gradle: it uses ndk-build, javac, d8,
aapt2 and apksigner directly. The first build creates `debug.keystore`; keep it, because updates must be signed
with the same key.

## Install

Turn on network debugging on the TV box, then:

```sh
./install.sh 192.168.1.77:5555
```

Use your box's address and port (`adb mdns services` lists it). Besides installing, the script runs
`appops set tv.local.airreceiver SYSTEM_ALERT_WINDOW allow`: without it, Android 10 won't let MirrorBox bring the
mirrored screen to the front while another app is open.

## Use

Open **MirrorBox** once from the app list to see the TV's name and address. Then:

- iPhone / iPad / Mac: Control Centre → Screen Mirroring → **NetTV**
- Music: the AirPlay button in any app → **NetTV**
- DLNA apps: pick **NetTV** as the renderer
- **Back** on the remote stops mirroring. In the DLNA player: OK = play/pause, ◀ ▶ = 10 s back/forward, Back = stop.
- **Rename this TV** in the app changes the name phones see.

If your home screen closes idle apps automatically, exempt `tv.local.airreceiver` so the receiver stays discoverable.

Logs: `adb logcat -s MirrorBox MirrorBoxNative UxPlay`

## How it works

- `jni/uxplay/` is the protocol core of [UxPlay](https://github.com/FDH2/UxPlay): the AirPlay/RTSP server, FairPlay
  and pairing, mirroring and audio streams, and a self-contained mDNS responder that advertises `_airplay._tcp` and
  `_raop._tcp`.
- `jni/airreceiver_jni.c` runs it inside the app and hands decrypted H.264 frames and audio packets to Java.
- `VideoDecoder` shows each mirrored frame as soon as the hardware decoder outputs it. `AudioPlayer` decodes
  AAC-ELD/AAC with MediaCodec (ALAC natively) and plays it with a short buffer.
- `DlnaRenderer` is a small UPnP MediaRenderer (SSDP, AVTransport, RenderingControl, ConnectionManager, events);
  `PlayerActivity` plays the media with the system player.
- `ReceiverService` is a foreground service started at boot that keeps everything running and discoverable.

## Licence

GPL-3.0-or-later, see [`LICENSE`](LICENSE) and [`NOTICES.md`](NOTICES.md) for the bundled libraries.

Built on UxPlay (GPL-3.0). AirPlay is a trademark of Apple Inc.; this project is not affiliated with Apple.
