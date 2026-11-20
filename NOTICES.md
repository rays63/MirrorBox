# Licence and third-party notices

MirrorBox is free software under the **GNU General Public License, version 3 or later**
(see [`LICENSE`](LICENSE)), because it is built on UxPlay, whose FairPlay code is GPL-3.0.
The full corresponding source, including every bundled library, is in this repository; `build.sh` rebuilds the APK from it.

| Component | Where | Licence | Upstream |
|---|---|---|---|
| UxPlay protocol core (RAOP/AirPlay, pairing, mirroring, NTP, mDNS responder) | `jni/uxplay/` | LGPL-2.1+ (most files), GPL-3.0 (UxPlay as a whole) | https://github.com/FDH2/UxPlay (commit in `jni/uxplay/SOURCE.txt`) |
| playfair (FairPlay SAP handshake) | `jni/uxplay/playfair/` | GPL-3.0 | via UxPlay |
| fairplay_v2 | `jni/uxplay/fairplay_v2/` | LGPL-2.1+ | via UxPlay |
| llhttp (HTTP/RTSP parser) | `jni/uxplay/llhttp/` | MIT | https://github.com/nodejs/llhttp, via UxPlay |
| libplist (binary/XML property lists) | `jni/libplist/` | LGPL-2.1+ | https://github.com/libimobiledevice/libplist |
| Apple ALAC decoder | `jni/alac/` | Apache-2.0 | https://github.com/macosforge/alac |
| OpenSSL libcrypto 3.0 (downloaded and built by `build.sh`, linked statically) | `build-cache/` | Apache-2.0 | https://github.com/openssl/openssl |

The licence texts are kept with each component: `jni/uxplay/LICENSE-UxPlay-GPL3`, `jni/uxplay/playfair/LICENSE.md`,
`jni/uxplay/llhttp/LICENSE-MIT`, `jni/libplist/COPYING` (GPL) and `COPYING.LESSER` (LGPL), `jni/alac/APPLE_LICENSE.txt`,
and OpenSSL's `LICENSE.txt` in its source tree.

Local changes to the bundled code are listed in each `SOURCE.txt`.

AirPlay, Apple TV, iPhone, iPad and Mac are trademarks of Apple Inc. This project is not affiliated with or endorsed by Apple.
DLNA is a trademark of the Digital Living Network Alliance.
