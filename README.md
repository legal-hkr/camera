# <img src="metadata/en-US/icon.png" width="42"/> Camera

A private camera for Android: Ultra HDR photos, motion photos, HDR video and QR codes, with no Internet access.

**Current version**: v0.1.0

Source code and releases: [git.hnet.sh/hkr/camera](https://git.hnet.sh/hkr/camera)

Bug reports and feature requests: [github.com/legal-hkr/camera](https://github.com/legal-hkr/camera/issues)

## Overview

Camera takes photos and videos at the best quality the phone’s hardware allows and keeps them only on the phone. It has no Internet permission: files are saved to the DCIM/Camera folder, where any gallery app can find them, in standard formats (Ultra HDR, Google’s motion photo format, HLG video) that don’t need a specific gallery. It can be the phone’s only camera app: it works over the lock screen, for other apps and from shortcuts.

## Features

### Photos

- Ultra HDR at each camera’s full default resolution, with zero shutter lag
- Motion photos (off, auto or on), in Google’s standard format
- 4:3, 16:9 and 1:1 aspect ratios, flash with adjustable strength, a screen flash for selfies, and optional mirrored selfies
- A 3- or 10-second timer, optionally counted down with the flash LED
- Tap to focus and meter, touch and hold to lock exposure and focus, exposure compensation, face detection

### Video

- 4K and 1080p at 24, 30 or 60 fps, HLG HDR up to 30 fps, HEVC or AVC
- Stereo sound, pause and resume, photos while recording, the flashlight

### Zoom

- Only the phone’s own lenses: a separate button for the ultrawide, and smooth zoom with haptic stops at the other lenses
- A note when the phone switches to its automatic macro mode

### And also

- QR codes recognized on the phone: links, Wi-Fi networks to join and business cards to add to your contacts
- Opens over the lock screen with a double press of the power button, showing only the shots from that session
- Photos and videos for other apps, with a review before they are handed over
- Selfie, Video and QR code shortcuts
- Grid, level, and the volume keys as the shutter or zoom
- If the phone overheats, a call comes in or storage fills up, recording stops and the video is saved; a low battery turns the flash off
- Backup and restore of settings to a file you choose
- English or Polish, independent of the system language
- Material 3 Expressive design with your phone’s colors

## Requirements

- Android 14 (API level 34) or higher

## Building

```bash
git clone https://git.hnet.sh/hkr/camera.git
cd camera
./gradlew :app:assembleDebug
```

Set the Android SDK path in `local.properties` (`sdk.dir=…`) first. Unit tests run with `./gradlew test`.

The repository also holds `:diag` (Camera diagnostics, `sh.hnet.camera.diag`), a separate tool that reads what the phone’s cameras offer and tests it with real captures, and `:capabilities`, the capability detection both apps share. The diagnostics app is not released.

## Contributing

Development happens on [git.hnet.sh](https://git.hnet.sh/hkr/camera). The [GitHub repository](https://github.com/legal-hkr/camera) is a read-only mirror, updated on every push, and the place to report problems:

- **Issues**: Open them on [GitHub](https://github.com/legal-hkr/camera/issues), with the app version and your Android version
- **Pull requests**: Welcome on GitHub as well, but they can’t be merged there – accepted changes are applied on git.hnet.sh and reach the mirror from there, so the pull request itself ends up closed rather than merged

## Tech stack

- **Language**: Kotlin
- **Min SDK**: Android 14 (API 34), **target SDK**: API 37
- **UI**: Jetpack Compose with Material 3 Expressive
- **Camera**: Its own engine on the platform’s Camera2 and MediaCodec

## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.en.html)

## Acknowledgments

- QR codes are read with [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp), available under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [Claude](https://claude.ai) by Anthropic – AI assistant that provided valuable support during the development process, helping with code implementation, architecture decisions, and documentation
