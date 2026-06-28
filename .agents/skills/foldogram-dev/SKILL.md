---
name: foldogram-dev
description: "Use when building, installing, testing, releasing the foldogram fork, or making any code change. Branch/build/release flow, secrets policy, no-AI-traces commit rule."
---

# Foldogram Development Skill

Use this skill for normal code changes, builds, installs, tests, and releases.
Do not use it for upstream DrKLO/Telegram merges; use the `upstream-merge`
skill instead.

## Checklist

1. Branch from `foldogram`.
2. Keep Java source compatible with Java 8.
3. Run Gradle with Java 17.
4. Use `TMessagesProj_AppHockeyApp` for beta/device installs.
5. Use `TMessagesProj_App` and `bundleAfatRelease` for store artifacts.
6. Run the merge canary before merging merge-sensitive changes.
7. Run the four CI gates before merging into `foldogram` or preparing a release.
8. Keep commit messages free of AI traces such as `Claude`, `AI`,
   `Co-Authored`, or `Generated`.
9. Never commit generated Google services files, signing keys,
   `local.properties`, credentials, or local notes.

Full reference: `docs/DEVELOPMENT.md`.
