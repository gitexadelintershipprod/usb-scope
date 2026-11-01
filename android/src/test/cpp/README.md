# Native isochronous transport tests

`iso_native_test.cpp` substitutes the usbfs syscalls while compiling the production C++ implementation.
It verifies independent packet boundaries, copying before requeue, error metadata, partial-submission
cleanup, malformed completion rejection, bounded interrupted reads, read/close ownership, and bounded
quarantine plus recovery when cancellation cannot safely drain.

Run with AddressSanitizer, UndefinedBehaviorSanitizer and leak checking **inside Docker** from the
repository root. This installs the compiler only into a temporary container, not the host:

```sh
docker run --rm -v "$PWD:/src:ro" -w /src usb-scope-android-build:2026-11-01-iso sh -c '
  set -eu
  apt-get update >/dev/null
  apt-get install -y --no-install-recommends g++ >/dev/null
  g++ -std=c++17 -O1 -g -Wall -Wextra -Werror -pthread \
    -fsanitize=address,undefined -fno-omit-frame-pointer \
    android/src/test/cpp/iso_native_test.cpp -o /tmp/iso-native-test
  ASAN_OPTIONS=detect_leaks=1 /tmp/iso-native-test
'
```

The same source can be cross-compiled with the NDK's `x86_64-linux-android29-clang++`, using
`-static-libstdc++`, and executed in the temporary Android emulator. The test releases its otherwise
process-lifetime registry after all simulated URBs are drained so leak detection remains enabled.

These tests exercise allocation, copying and ownership. They do not establish USB streaming or
bandwidth compatibility with a physical camera or handset.
