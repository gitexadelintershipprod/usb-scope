# USB Scope A17 (Android compatibility fork)

## Latest update — 2026-11-01

USB diagnostics now includes a bounded, local event history of permission requests/replies, actual USB-access
changes, connection opens/closes and anonymous attachment generations. This helps distinguish an Android
permission denial from a camera that reconnects and loses its temporary grant. Copy the report after tapping
Allow, without unplugging the camera. Current global sensor privacy state is not accessible through the public
Android API; the report explicitly distinguishes capability queries from state.

[Download the Android APK with USB permission tracing](https://github.com/gitexadelintershipprod/usb-scope/releases/download/v0.3.1-a17.4/USB-Scope-A17-0.3.1-a17.4.apk)

This fork fixes USB video-camera permission handling on modern Android, including Android 17.
Android now also reads **isochronous UVC cameras**, including the reported 349C:0411 connection layout,
using the file descriptor Android authorizes. The camera needs no root or additional permissions.
See [native transport and validation](docs/isochronous-usb-a17.md) for limits and test evidence.
The latest Android build adds **USB diagnostics** on the no-picture and About screens: inspect every attached
USB device, allow access to your camera, and copy the local report when the camera does not connect.
Unsupported-camera reasons now remain visible until detach instead of turning into a misleading "No device".
Diagnostics identifies missing permissions and shows whether the native isochronous transport is available.
Android requires Camera permission for video-class USB devices: allow **Camera**, then **USB access**.
Vendor-class endoscopes still need only the per-device USB approval. Denied permissions remain retryable.
Malformed USB descriptors and oversized frames are rejected to reduce crash and memory-exhaustion risks.
See [release notes](docs/releases/release-notes.md) and [security review](docs/security-review-a17.md).

The Android app is named **USB Scope A17**, package `com.gitexadelintershipprod.usbscope`, so it can be
installed alongside the Play version. It uses an independent signing certificate. No internet, audio,
location, contacts or storage permission is requested. Original source: [TechnicallyVu](https://github.com/technicallyvu/usb-scope).

A successful build does not establish live USB compatibility: the user's Android 17 phone and camera
must still be tested. Isochronous support is implemented for Android; the desktop transport remains bulk-only.

## Upstream project

Privacy-first, open-source viewer for cheap USB endoscopes that ship with untrusted vendor apps.
The full privacy policy is in [docs/privacy-policy.md](docs/privacy-policy.md): conditional Camera permission for USB video devices, no network, no data collected.
First supported device: USB 2CE3:3828 / 0329:2022 endoscopes. Verified on hardware in the `i4season-yuv`
personality (single interface, YUYV); the `useeplus` personality (dual interface, MJPEG) is implemented
from public protocol documentation and has not been exercised on a device here.

Phase 1 = Windows dev bench. See `docs/superpowers/specs/` for the design and `docs/windows-setup.md`
for the one-time WinUSB driver step.

## Quick start (Windows)
    .\gradlew.bat :core:test                       # protocol tests
    .\gradlew.bat :desktop:probe --args="--list"   # what libusb can see
    .\gradlew.bat :desktop:run                     # the viewer

## Prior work
Protocol documentation reimplemented from: hbens/geek-szitman-supercamera (CC0),
echase/ProbeView (MIT), MAkcanca/useeplus-linux-driver (GPL-3, reference only),
jmz3/EndoscopeCamera, NinesLastGoal/supercamera_WIN10.

## Hardware-free development
    .\gradlew.bat :desktop:run --args="--replay core/src/test/resources/fixtures/i4season-yuv-320x240.upkt"

## Supported devices
| Driver | USB IDs | Layout | Picture |
|---|---|---|---|
| `i4season-yuv` | 2CE3:3828, 0329:2022 | one interface FF/F0/01 | 320x240 YUYV, ~11 fps (verified on hardware) |
| `useeplus` | 2CE3:3828, 0329:2022 | interfaces FF/F0/00 + FF/F0/01 | 640x480 MJPEG (from public docs; untested here) |
| `uvc-bulk` (historical id) | any UVC | bulk; native isochronous on Android | MJPEG or YUY2; advertised preview size/interval chosen for ISO. Unit/native tests passed; physical 349C:0411 validation pending. |

## Status
Phase 1 (Windows dev bench) accepted 2026-09-09 against an i4season "su4p-002" (2CE3:3828, firmware 5.0.13).
Phases 2, 3a and 3b followed; next is launch prep (see `docs/next-steps.md`).

## Android (Phase 2)
    .\gradlew.bat :android:assembleDebug            # builds; fails if any dependency adds the INTERNET permission
    C:\platform-tools\adb.exe install -r android\build\outputs\apk\debug\android-debug.apk

Plug the endoscope into the phone; Android offers to open USB Scope. Photos go to Pictures/USB Scope and clips to
Movies/USB Scope through MediaStore without storage permission. Camera permission is declared and requested
only for USB video-class devices; Android requires it even though this app reads USB directly. The About screen
reports the manifest permission count and excludes the app-private AndroidX signature permission. Debug builds add a
developer sheet — replay the bundled capture without hardware, log per-second frame timing under the tag
`ScopeViewModel`, copy diagnostics — reached by a long-press on the version line in About. Requires the Android SDK
(compileSdk 37, targetSdk 37, minSdk 29); see `docs/dependencies.md`.

Phase 2 accepted 2026-09-10 on a Galaxy Z Fold 7. Notes: `docs/phase2-notes.md`, then `docs/phase3b-notes.md`.

### Screens
- **Live view** — full screen, black letterbox. A status chip (connection dot, state, fps) top-left, a REC badge
  with elapsed time top-right, and a translucent sheet of eight controls along the bottom: snapshot, record,
  rotate, mirror, denoise, stats, settings, about. The sheet fades after four seconds without a touch while
  streaming and comes back on a tap; the REC badge never fades. On a Fold-open or tablet window the sheet becomes
  a rail down the side. A dismissable tips card explains the cable button and where files land on first launch.
- **Settings** — denoise and its strength, sharpening and its strength, the cable button's double-press window, haptics, keep screen on while
  streaming, the stats overlay, the devices whose rotation and mirror the app has remembered (with "Forget"), and
  "Show tips again". Everything persists across restarts.
- **About & privacy** — the permission count, the source link, the MIT licence, attribution to the prior
  reverse-engineering projects, version and build id.

## Features (v0.3.1)
- Temporal denoise (toggle in both apps): adaptive frame blending that removes sensor grain when the probe is
  still and passes moving detail through. Snapshots save what is on screen.
- Optional sharpening (Settings on Android, a checkbox on desktop; off by default): a mild luma unsharp mask
  applied after denoise. What the camera actually delivers, why files are native 320x240 and never upscaled,
  and what sharpening does and does not do: `docs/image-quality.md`.
- Cable button: one press takes a photo, two within the window (configurable, default 1.5 s) start or stop a clip.
- Snapshot and recording feedback: shutter flash, a "Saved" toast with a thumbnail, a spinner while the encoder
  starts, a REC timer.
- Per-device orientation memory: rotate or mirror once and that device comes up that way next time.
- Material 3 with dynamic colour on Android 12+, light and dark. The launcher icon is Anthony's design (see `art/`).
- About & privacy screen (Android) and About dialog (desktop): permissions the app does not have, source link,
  MIT license, attribution to the prior reverse-engineering projects, version and build id.
- Free and open source. Sponsorship goes through the GitHub Sponsor button on the repository (which points at
  Ko-fi); the Android app itself contains no payment or donation links, because Google Play's Payments policy
  forbids in-app links to an external payment method (`docs/play-policy-audit.md`).
