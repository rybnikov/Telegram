---
name: dhu-debug
description: "Use when starting or debugging Android Auto Desktop Head Unit (DHU) for Foldogram on a USB-connected Android device."
---

# Android Auto DHU

Use the existing Foldogram Beta install (`com.rbnkv.foldogram.beta`). Do not
clear, uninstall, or replace the stable package (`com.rbnkv.foldogram`).

Before launch, verify that the phone is physically connected and listed in the
`device` state (not `unauthorized` or `offline`):

```bash
~/Library/Android/sdk/platform-tools/adb devices -l
```

Use this canonical launch command:

```bash
~/Library/Android/sdk/extras/google/auto/desktop-head-unit \
  --usb -c ~/.android/headunit.ini
```

Run DHU in a persistent foreground terminal with a TTY. With a command runner,
use a short initial yield, retain the returned session ID, and poll that session
instead of waiting for the process to exit. A successful DHU session stays
running; do not background it or send Ctrl-C after startup. If sandboxing blocks
GUI, USB, or config access, rerun the same command through the normal permission
approval flow.

The `--usb` transport is the known-good workflow. Do not substitute
`adb forward ...` plus `-a 127.0.0.1:5277` unless explicitly testing TCP; that
variant may connect and immediately drop the session.

Poll startup output in intervals of about 10 seconds for up to 60 seconds.
`Found 0 USB devices` and `No device found ready yet, will retry shortly` are
normal while the phone switches to Android Open Accessory mode. Report success
only after all of these signals appear:

```text
Attached!
SSL negotiation finished successfully
Verify returned: ok
```

If the device is still retrying after 60 seconds, inspect the USB connection,
unlock the phone, and handle any Android Auto prompt. Stop only the DHU process
started by the current session before making one clean retry. If that retry also
fails, report the last startup output and stop; do not switch transports,
install an APK, or start another DHU process.

Do not commit `~/.android/headunit.ini`, device IDs, logs, or local setup notes.
For APK build/install changes, also load `foldogram-dev`.
