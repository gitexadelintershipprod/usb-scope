# Release notes

### 2026-11-01

- Android can read UVC cameras using isochronous transfers, including the reported 349C:0411 endpoint layout, with the existing Camera/USB approvals.
- Isochronous cameras use an advertised preview size and frame rate suited to the endpoint capacity. Packet loss discards damaged frames rather than displaying corrupt data.
- Transfer stalls now report useful errors; the last USB error remains available in diagnostics during automatic reconnect.
- No new app permissions or online services are added. Live operation on the user's physical camera still requires testing.

- USB diagnostics lists every device exposed by Android, including cameras the current drivers do not recognize. Copy a local report to identify missing permissions, driver matches and USB transfer types.
- Camera access can be requested from diagnostics even when automatic driver matching does not find it.
- Unsupported-camera reasons stay visible while attached and detection is retried after an observed detach/reconnect.
- Diagnostics contains app/OS/model and USB descriptor details without serial numbers; it is sent only if the user copies and shares it.
- Privacy text clarifies that an active recording may continue after Home and that source links open a browser.

- USB Scope A17 requests Camera permission when Android requires it for a connected USB video camera, then requests USB access.
- Denied permission, unplugged-device and USB-request errors can be retried without an app crash.
- USB permission responses are checked against the currently attached device and actual Android permission state.
- Malformed descriptors and oversized frames are rejected before unbounded allocation or image decoding.
- Installs beside the original USB Scope with a distinct name, package and signing key; remains offline without analytics or ads.
- Built against Android 17 (API 37). Live preview and recording on the user's phone/camera require a hardware test.
