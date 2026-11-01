# Building the Android compatibility fork

All dependencies and builds run inside Docker. Docker must already be installed; these commands do not install host software.

```sh
docker build -t usb-scope-android-build -f tools/android/Dockerfile .
docker volume create usb-scope-gradle-cache
docker run --rm -v usb-scope-gradle-cache:/cache usb-scope-android-build chown 1000:1000 /cache
docker run --rm --user 1000:1000 -e HOME=/tmp -e ANDROID_USER_HOME=/tmp/.android -e JAVA_TOOL_OPTIONS=-Duser.home=/tmp -v "$PWD:/src" -v usb-scope-gradle-cache:/cache usb-scope-android-build ./gradlew --no-daemon --max-workers=6 :core:test :android:testDebugUnitTest :android:lintRelease :android:assembleRelease
```

Use your own UID/GID instead of 1000 if necessary. The image provides JDK 17, SDK platform 37.0, Build Tools 37, NDK 29.0.14206865 and CMake 3.22.1. Native USB code is built from source for arm64-v8a, armeabi-v7a, x86 and x86_64 with 16 KiB ELF alignment.
The build uses compile/target API 37, min API 29. Release output is unsigned unless an external signing-properties
file is explicitly supplied. Sign it with your own private Android keystore using Build Tools `zipalign` and
`apksigner`; never commit a keystore or password. The delivered APK uses an independent RSA signing key retained
privately in the task workspace, not the upstream Play certificate.

The fork package is `com.gitexadelintershipprod.usbscope`, displayed as USB Scope A17. It installs alongside
`com.technicallyvu.scope` and does not overwrite the upstream app or its preferences. Camera approval is needed
only for USB video-class devices; a per-device USB approval is still necessary.
