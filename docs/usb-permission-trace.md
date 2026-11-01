# USB permission tracing

Android build 0.3.1-a17.4, versionCode 7, 2026-11-01.

## Reason for the update

The reported 349C:0411 camera is enumerated, Camera permission and the native ISO backend are available,
but Android reports UsbManager.hasPermission=false even after the user says they selected Allow. The user
also reports Camera access enabled globally. This does not establish whether the system did not retain a grant
or the attachment changed during opening; the earlier report lacked a timeline.

## What is recorded

A bounded in-memory history records explicit request/callback events, the system reply extra, request-match
status, current actual USB/Camera permission, PendingIntent creator package/UID, successful and failed USB opens,
connection closes and viewer state changes. The session's successful enumeration is the single authoritative
polling inventory. Real detach broadcasts additionally invalidate a generation so a rapid reconnect can be
identified even if polling missed the gap. USB paths and serials are not exported. A1/A2 labels identify only
local attachment generations; times are relative to process startup and the newest 64 entries are retained.

No serial fields, photos, media, filesystem files or network connection are read for this report. No new
permissions, privileged APIs, hidden reflection or automatic uploads are added. A global sensor privacy switch's
current state is not publicly readable in SDK 37; only supported-toggle capabilities are queried. Camera app-op
mode is diagnostic information, not a claim that it explains the USB service's decision.

## Interpretation

- A successful open or an actual permission=false→true event confirms that Android granted access at that point.
- A later detach/re-enumeration with another attachment label can explain losing an attachment-scoped grant.
- A reported granted=true callback with actualUSB=false on the same attachment requires further system-state
  diagnosis. The app must not pretend permission exists or bypass Android's USB authorization.
- An ignored/lost private callback alone cannot explain sustained false: the existing live session independently
  rechecks UsbManager.hasPermission during retries.

AOSP checks sensor privacy and CAMERA permission before its USB permission maps. Persistent DeviceFilter/UID
entries precede temporary attachment grants, so a persistent false is one possible explanation; it is not
confirmed on the reported Motorola. USB permission cannot legitimately be forced by this ordinary app.

## Validation and limits

180 core and 83 Android JVM tests passed (263 total), with no failures/skips. Release lint has no errors.
Trace regressions cover grant/revocation, changed identities, missed-poll detach/reconnect, bounded history,
coalescing and path/control-character redaction. Actual permission acceptance and the physical device's
reenumeration behavior still need a report from the user's phone. Native streaming code is unchanged.

Primary references:
- [AOSP USB user permission manager](https://android.googlesource.com/platform/frameworks/base/+/master/services/usb/java/com/android/server/usb/UsbUserPermissionManager.java)
- [AOSP USB permission dialog](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SystemUI/src/com/android/systemui/usb/UsbPermissionActivity.java)
- [Public sensor privacy API](https://developer.android.com/reference/android/hardware/SensorPrivacyManager)
