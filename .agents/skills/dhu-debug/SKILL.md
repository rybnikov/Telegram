---
name: dhu-debug
description: "Use when starting or debugging Android Auto Desktop Head Unit (DHU) for Foldogram on a USB-connected Android device."
---

# Android Auto DHU

Use the existing Foldogram Beta install (`com.rbnkv.foldogram.beta`). Do not
clear, uninstall, or replace the stable package (`com.rbnkv.foldogram`).

Canonical launch (run in a persistent terminal; keep it foregrounded):

```bash
~/Library/Android/sdk/extras/google/auto/desktop-head-unit \
  --usb -c ~/.android/headunit.ini
```

The `--usb` transport is the known-good workflow. Do not substitute
`adb forward ...` plus `-a 127.0.0.1:5277` unless explicitly testing TCP; that
variant may connect and immediately drop the session.

Before launch, verify the phone is physically connected and visible with
`adb devices -l`. During startup, `Found 0 USB devices` is normal while the
phone switches to Android Open Accessory mode; wait for `Attached!` and a
successful TLS handshake. If it keeps retrying, check the USB connection and
run the same command again.

Do not commit `~/.android/headunit.ini`, device IDs, logs, or local setup notes.
For APK build/install changes, also load `foldogram-dev`.
