# Android UVC isochronous transport

2026-11-01, Android build 0.3.1-a17.3, versionCode 6.

## Confirmed trigger

The reported camera is UVC device 349C:0411, with both Camera and USB permissions granted. Its video
streaming interface 1 has no endpoint at alt 0 and isochronous IN endpoint 0x81 at alt 1, interval 1,
raw wMaxPacketSize 0x1400. The original driver rejected it because it could only perform bulk transfers.
The raw value 5120 encodes 1024 bytes times three transactions: effective high-speed capacity is 3072 bytes.
No vendor-id exception or permission bypass is used: standard UVC descriptors select the transport.

## Implementation

A small JNI library issues Linux usbfs isochronous URBs using a duplicate of the file descriptor already
opened by Android's USB service after user approval. It never opens a USB filesystem path, enumerates
outside Android, requests root, accesses media or opens a network connection. App permissions stay the same.

Eight URBs, each containing 32 service-interval packets, keep input queued. The reader preserves each
packet's length/status and fixed buffer offset; it copies completed bytes before requeueing the buffer.
The Kotlin adapter delivers one packet at a time, and UVC parsing removes each packet's own header.
Failed packets mark the video frame damaged. The producer keeps reading while display/filtering processes
the previous frame, keeping at most one pending frame. Bulk and vendor-specific drivers keep their paths.

The driver negotiates an advertised MJPEG/YUY2 format and safe preview size, prefers VGA-or-smaller when
available, and selects an alternate setting with adequate committed capacity. Uncompressed bandwidth is
checked against advertised frame intervals. It never invents a resolution or frame rate. High-speed
1024-byte/multitransaction endpoints are handled; small ambiguous endpoints use conservative full-speed budgeting.
SuperSpeed companion-descriptor bandwidth and other video formats are not implemented.

## Native ownership and safety

Only one asynchronous reaper can use a connection; mixing bulk UsbRequests with native ISO is rejected.
Handles use a registry/shared ownership and per-session locking. Close cancels every submitted URB and
reaps completions before freeing any buffer. DISCARD alone is insufficient because REAP writes data and
status back into userspace allocations. A duplicate fd shares the original open-file state, so closing
only that duplicate cannot be assumed to drain pending USB writes.

If an exceptional kernel failure prevents a complete drain, allocations/fd are retained in a bounded
quarantine (four active/constructing/quarantined sessions in total) until a later safe drain or process exit.
This deliberately favors bounded retention over use-after-free. New opens are refused at the limit.
JNI validates descriptors, buffer sizes, handles, packet counts/lengths and deadlines. The source performs
no runtime code downloads and uses no external USB library; the standard NDK C++ runtime is linked statically.

## Validation

- 180 shared-core and 76 Android JVM tests passed (256 total, no failures/skips).
- Native fake-I/O suite passed with AddressSanitizer, UndefinedBehaviorSanitizer and leak detection inside Docker.
- The same native suite passed as an Android executable on Android 17 / API 37.
- 43 actual JNI boundary checks passed on Android 17, including invalid descriptors/parameters, repeated non-USB-fd opens with no fd growth, invalid handles and idempotent close.
- Four native ABIs compiled; all ELF LOAD segments are 16 KiB aligned. Dependencies are Android system libc/libm/libdl; no extra shared C++ runtime is needed.
- Release lint passed with no errors. Final signed APK upgrade/startup checks are recorded with the delivered build evidence.

There is no physical endoscope in this environment. Fake USB, JNI checks and an emulator cannot establish
actual streaming on the reported phone/camera. Installation, preview, photo/video and detach/reconnect must
still be verified by the user. Native support is experimental until that hardware check succeeds.

## Primary references

- [Linux usbfs implementation](https://github.com/torvalds/linux/blob/master/drivers/usb/core/devio.c): submit, cancel, completion and userspace copy lifecycle.
- [Linux usbfs ABI](https://github.com/torvalds/linux/blob/master/include/uapi/linux/usbdevice_fs.h): URB and packet descriptor definitions.
- [Android USB file descriptor](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection#getFileDescriptor()): authorized descriptor access.
- [Android 16 KiB page guidance](https://developer.android.com/guide/practices/page-sizes).

Native test reproduction is documented in android/src/test/cpp/README.md. All toolchain/dependency preparation
and test/build execution runs inside Docker; no host packages or software environments are installed.
