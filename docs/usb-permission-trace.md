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


## Callback delivery correction (0.3.1-a17.5, versionCode 8)

The A17.4 Motorola report contains two USB permission requests, no received USB replies and no
observed re-enumeration in its retained window. Camera runtime permission becomes granted; its
camera app-op check reports ignored. These facts do not prove why Android still reports USB access denied.

A concrete app defect was found: the PendingIntent contains a token-bearing
`scope-usb-permission://<package>/<token>` URI, but the dynamically registered IntentFilter accepted
only the action and no data. Android rejects a data-bearing intent against that filter. The new shared
helper builds both the callback intent and a filter matching its scheme and exact package authority.
The receiver remains non-exported. Its UUID token, current USB attachment and actual permission checks
remain required before the app accepts a grant.

Repeated Connecting/error retry pairs previously filled the 64-entry history in about 32 seconds.
The trace now omits the intermediate Connecting state while a previous USB error is retained;
the unchanged error coalesces. Explicit user requests/replies and real permission/attachment changes
are still recorded. A regression simulates 600 failed retries and verifies the request/reply survives,
then verifies streaming recovery and a changed failure remain visible.

Diagnostics adds the raw camera app-op mode and this process's importance. Raw mode avoids converting
foreground-only into its effective result, but can still reflect system restrictions. Neither the raw
mode, effective mode nor importance proves the USB service's decision. No app-op is modified and no
permission, foreground service or internal camera access is added.

The callback correction fixes a real delivery defect. It is not evidence that the phone now grants USB
access: AOSP's positive USB confirmation grants UID access before sending the callback, and the session
already polls actual authorization independently. Physical permission acceptance and streaming still need
the user's updated report.

Regression checks use Android's actual IntentFilter implementation against the production helper loaded
from the APK, plus a separate dependency-free androidTest instrumentation runner for same-UID mutable
one-shot PendingIntent delivery. Test code is not included in the release APK. An external shell broadcast with the correct callback
URI must still be rejected by the non-exported receiver; a data-less broadcast alone would only test
the URI filter.

Additional references:
- [IntentFilter data matching](https://developer.android.com/reference/android/content/IntentFilter#matchData(java.lang.String,%20java.lang.String,%20android.net.Uri))
- [AppOpsManager raw/effective modes](https://developer.android.com/reference/android/app/AppOpsManager)


A17.5 build validation: 180 core + 84 Android JVM tests passed (264 total), with no failures, errors
or skipped tests. Release lint reports zero errors. Live authorization and streaming on the Motorola
remain unverified until the user tests the updated APK.

Reproduce the delivery test inside an existing Android SDK environment:

```sh
./gradlew :android:assembleDebug :android:assembleAndroidTest
adb install -r android/build/outputs/apk/debug/android-debug.apk
adb install -r android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk
adb shell am instrument -w com.gitexadelintershipprod.usbscope.test/com.technicallyvu.scope.usb.UsbPermissionIntentInstrumentation
```

Use an isolated emulator; debug and release builds have different signing keys. The test registers a
private receiver with the production filter, sends mutable one-shot PendingIntents with both granted
and denied extras, and checks token preservation and one-shot replay rejection. It does not claim to
emulate the actual USB service's authorization decision.
