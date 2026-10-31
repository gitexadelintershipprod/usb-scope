# USB Scope A17 security and compatibility review

Review date: 2026-11-01. Upstream baseline: `45e4f4594cc3d75be164929217b10c72e69d424b`.
Scope: inspected Android and shared Kotlin source, manifests, media storage, USB parsing, Gradle configuration,
and the release APK. This is not a security certification or a verdict on the existing Google Play binary.

## Conclusion

No malicious code, in-process networking, analytics, advertising, dynamic code loading, embedded credentials,
contacts/location collection, microphone capture or arbitrary gallery reads was found in the inspected app paths.
The APK intentionally omits INTERNET, RECORD_AUDIO and storage permissions. AndroidX/Compose/Kotlin are normal
runtime dependencies; desktop FFmpeg/JavaCV/libusb dependencies are separate from the Android runtime.
Backup is disabled. The launcher/USB entry activity is exported; the private USB-permission receiver is not.
External source links open the browser only when tapped.

## Permission defect and fix

The original app omitted CAMERA entirely. Android's USB service requires it for video-class USB devices when
an app targets API 28 or later. This requirement predates Android 17; the observed message alone cannot establish
that Android 17 introduced the failure. Vendor-class cameras can behave differently.

This fork declares CAMERA, checks device and interface classes, requests Camera before USB approval when needed,
and validates the actual permission state and current attachment before proceeding. A package-scoped mutable,
one-shot PendingIntent carries a unique request token; stale results, detach and duplicate taps are handled.
Denied access stays retryable; permanent Camera denial can be resolved through the app's system settings.

References: [UsbManager](https://developer.android.com/reference/android/hardware/usb/UsbManager),
[AOSP USB permission policy](https://android.googlesource.com/platform/frameworks/base/+/master/services/usb/java/com/android/server/usb/UsbUserPermissionManager.java),
[Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk).

## Confirmed robustness defects fixed

- A one-byte UVC descriptor could read beyond its buffer. Reject descriptors shorter than two bytes.
- A proprietary MJPEG stream with a constant frame ID could accumulate without bound. Cap at 4 MiB and recover at the next frame.
- UVC frame limits relied on device-controlled sizes. Enforce a 32 MiB absolute ceiling.
- UVC dimensions are validated before negotiation and filtering; YUYV byte counts use overflow-safe arithmetic.
- JPEG decoding trusted camera-supplied dimensions. Inspect bounds first, limiting dimensions to 4096 per side and 8,388,608 pixels before allocating a bitmap. Apply the same display allocation limit to YUYV frames.

These are local denial-of-service defenses against malformed or faulty USB input. They do not establish
absence of every decoder/USB-stack vulnerability. Unfiltered JPEG snapshots retain camera-supplied metadata;
the app adds no GPS data. Photos/clips are saved locally through MediaStore and become visible to gallery apps.

## Build and runtime evidence

Container verification passed: 166 shared-core tests and 61 Android JVM tests, with no failures or skips; release lint has no errors (11 warnings remain). All four packaged graphics-path native-library ABIs have 16 KiB ELF LOAD alignment. Runtime and signed-APK verification are recorded in the accompanying verification report. The Gradle wrapper has a pinned
SHA-256; Android SDK command tools are downloaded from Google with a checked SHA-256 inside Docker. No host
packages or virtual environments are installed. Resolved transitive artifacts are not independently audited
or pinned by dependency verification metadata.

## Hardware limits

Generic UVC support uses bulk transfers. Isochronous-only UVC cameras remain unsupported. No test can establish
compatibility with the user's actual camera without attaching it to the user's Android 17 phone. The required
hardware checks are grant/deny/retry, detach/reconnect, live image, rotation, snapshot and recording. An emulator
smoke test covers OS installation and app startup, not USB streaming.
