# Release notes

### 2026-11-01

- USB Scope A17 requests Camera permission when Android requires it for a connected USB video camera, then requests USB access.
- Denied permission, unplugged-device and USB-request errors can be retried without an app crash.
- USB permission responses are checked against the currently attached device and actual Android permission state.
- Malformed descriptors and oversized frames are rejected before unbounded allocation or image decoding.
- Installs beside the original USB Scope with a distinct name, package and signing key; remains offline without analytics or ads.
- Built against Android 17 (API 37). Live preview and recording on the user's phone/camera require a hardware test.
