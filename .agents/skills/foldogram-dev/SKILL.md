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
8. After every upstream merge, run the required device test from
   `docs/UPSTREAM_MERGE.md` before merging back to `foldogram`. Verify the
   installed package version/commit and get owner acceptance after that install.
9. Treat `git push fork foldogram` as a release-boundary side effect: it triggers
   Foldogram CI. Do not push an upstream merge to `fork/foldogram` until the
   current installed build has passed owner acceptance.
10. Keep commit messages free of AI traces such as `Claude`, `AI`,
   `Co-Authored`, or `Generated`.
11. Never commit generated Google services files, signing keys,
   `local.properties`, credentials, or local notes.

Full reference: `docs/DEVELOPMENT.md`.
