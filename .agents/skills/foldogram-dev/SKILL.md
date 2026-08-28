---
name: foldogram-dev
description: "Use when building, installing, testing, releasing the foldogram fork, or making any code change. Foldogram Beta device-test isolation, branch/build/release flow, secrets policy, and no-AI-traces commit rule."
---

# Foldogram Development Skill

Use this skill for normal code changes, builds, installs, tests, and releases.
Do not use it for upstream DrKLO/Telegram merges; use the `upstream-merge`
skill instead.

## Checklist

1. Branch from `foldogram`.
2. Keep Java source compatible with Java 8.
3. Run Gradle with Java 17.
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
   candidate provenance, then get owner acceptance. Fresh-install testing also
   targets only the beta package; do not clear or uninstall beta data without
   owner approval unless an isolated test profile/emulator is used.
9. Treat `git push fork foldogram` as a release-boundary side effect: it triggers
   Foldogram CI. Do not push an upstream merge to `fork/foldogram` until the
   current installed build has passed owner acceptance.
10. Keep commit messages free of AI traces such as `Claude`, `AI`,
   `Co-Authored`, or `Generated`.
11. Never commit generated Google services files, signing keys,
   `local.properties`, credentials, or local notes.

Full reference: `docs/DEVELOPMENT.md`.
