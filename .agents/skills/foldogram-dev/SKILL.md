---
name: foldogram-dev
description: "Use when building, installing, testing, releasing the foldogram fork, or making any code change. Foldogram Beta device acceptance, branch/build/release flow, secrets policy, and no-AI-traces commit rule."
---

# Foldogram Development Skill

Use this skill for normal code changes, builds, installs, tests, and releases.
Do not use it for upstream DrKLO/Telegram merges; use the `upstream-merge`
skill instead.

For Android Auto Desktop Head Unit startup/debugging, use `dhu-debug`.

## Checklist

1. Branch from `foldogram`.
2. Keep Java source compatible with Java 8.
3. Run Android builds and installs with Java 17. Run Robolectric unit-test and
   fork-anchor gates with Java 21 when the app targets SDK 36; Robolectric 4.16
   requires it. Do not pin tests to an older SDK to bypass a toolchain mismatch.
4. Use `TMessagesProj_AppHockeyApp` and
   `:TMessagesProj_AppHockeyApp:installAfatHA_private` for local/device installs.
   The installed package must be `com.rbnkv.foldogram.beta`. Never install a
   local build over, clear, or uninstall the Google Play package
   `com.rbnkv.foldogram`.
5. Use `TMessagesProj_App` and `bundleAfatRelease` for store artifacts.
6. Run the merge canary before merging merge-sensitive changes.
7. Run the four CI gates before merging into `foldogram` or preparing a release.
8. After every upstream merge, run the required device test from
   `docs/UPSTREAM_MERGE.md` before merging back to `foldogram`. Upgrade the
   existing Foldogram Beta data in place, verify the installed beta package and
   candidate provenance, launch it, give the owner the relevant manual checklist,
   then wait. The owner's confirmation that everything works closes device
   acceptance. Do not add a fresh install, emulator, isolated profile, or second
   device unless the owner explicitly asks for it. Never clear or uninstall beta
   data without owner approval.
9. Treat `git push fork foldogram` as a release-boundary side effect: it triggers
   Foldogram CI. Do not push an upstream merge to `fork/foldogram` until the
   current installed build has passed owner acceptance.
10. A device disconnect after verified owner acceptance is not a reason to
    rebuild or reinstall. Repeat the Beta cycle only if application/build inputs
    changed or package/version/provenance verification was wrong.
11. Keep commit messages free of AI traces such as `Claude`, `AI`,
   `Co-Authored`, or `Generated`.
12. Never commit generated Google services files, signing keys,
   `local.properties`, credentials, or local notes.

Full reference: `docs/DEVELOPMENT.md`.
