# USB Scope privacy policy

**Effective date:** 1 November 2026 (Android compatibility fork)
**Applies to:** USB Scope A17, the independent Android compatibility fork.
**Source and support:** https://github.com/gitexadelintershipprod/usb-scope
Original USB Scope is by TechnicallyVu (Anthony Vu).

USB Scope is a viewer for USB endoscope and borescope cameras. It shows the camera's live picture and saves
photos and video clips when you ask it to. It does not collect, store, transmit, sell or share any personal
data. This page explains that in the detail app stores and privacy laws ask for.

## The short version

- The app has **no internet access**. On Android it does not request the INTERNET permission, so the
  operating system will not let it open a network connection at all.
- The app declares **Camera** permission because Android requires it for video-class USB cameras. It is
  requested only when such a USB device needs it. USB access also requires approval for each connected device.
  No microphone, location, contacts, storage or notification permissions are requested.
- There is **no account**, no sign-in, no analytics, no crash reporting, no advertising and no advertising or telemetry SDK. The app uses AndroidX, Compose and Kotlin libraries.
- Everything the app produces stays **on your device**, in files you own and can delete.

## What the app does with your data

### The camera picture

The endoscope sends its picture over the USB cable. The app decodes it and shows it on screen. Frames are
kept in memory only for as long as they are needed to display them (and, if you have noise reduction turned
on, to blend with the next frame). They are not written anywhere unless you take a snapshot or record a
clip.

### Photos and clips

When you tap Snapshot or Record, or use the cable button, the app writes a JPEG or MP4 file to your
device's normal media storage, in an album called **USB Scope**. On Android this goes through the system
media store, which is why the app needs no storage permission; the files appear in your Gallery like any
photo. On Windows they are written to the folder you chose. The files contain the picture, the time they
were taken and an orientation tag. The app adds no location or device identifiers. Unfiltered JPEG snapshots may retain metadata
provided by the USB camera itself. You can view, share or delete them with any other app; USB Scope never reads them
back.

### Settings

Your preferences (noise reduction, sharpening, the cable-button timing, vibration, keep-screen-on, whether
the statistics overlay is shown, whether you have dismissed the tips, and the remembered rotation or
mirroring for each camera model you have used) are stored in a small private file on the device. They are
not synced or backed up by the app and are removed when you uninstall it.

### USB device information

To recognise your camera the app reads the identifiers the device itself reports over USB (vendor and
product number, and for some models a short name and firmware version). These describe the camera model,
not you. They are shown on screen when relevant and are not stored anywhere except as the key for the
per-model rotation setting above.

### Nothing else

The app does not read your contacts, calendar, location, phone state, clipboard, other apps, or any file it
did not create. It does not run in the background when you are not using it and starts only when you open it
or plug in a supported camera.

## Links that leave the app

On Android the About screen contains one link: the project's source code. The Windows app's About
dialog contains that same source link and one more, to a donation page at ko-fi.com. Tapping a link
opens the address in your normal web browser. Nothing is sent from the app; once you are in the
browser, the privacy policy of that website applies. You can use every feature of USB Scope without
ever tapping them.

## Children

USB Scope is a general-purpose tool and is not directed at children. Because it collects no data, it
collects no data from children either.

## Your rights

Privacy laws such as the GDPR and the CCPA give you rights over personal data a company holds about you.
TechnicallyVu holds none from this app, so there is nothing to access, correct, export or delete on our
side. Anything the app produced is in files on your own device and under your control.

## How you can verify this

- The app is open source under the MIT licence. Anyone can read the code and rebuild it.
- The Android build fails on purpose if the INTERNET permission ever appears in the app's manifest.
- The About screen lists the system permission count and the sensitive permissions the app does not request.
- Android Settings → Apps → USB Scope A17 → Permissions lists Camera; it can be revoked at any time.

## Changes to this policy

If a future version of the app ever changes what it does with data, this page will be updated, the effective
date at the top will change, and the app's release notes will say so. The current version of this policy
for this fork lives in its source repository. The upstream website describes the original app.

## Contact

Questions about this fork: https://github.com/gitexadelintershipprod/usb-scope/issues
