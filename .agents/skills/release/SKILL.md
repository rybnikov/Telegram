---
name: release
description: "Use when cutting an internal release of the foldogram fork to the Google Play internal track via the Android Internal Release workflow (workflow_dispatch; CI creates the tag). Do NOT use for local or beta device builds — use foldogram-dev for those."
---

# Foldogram Internal Release Skill

Use this skill to cut an internal Google Play release of the fork. It is a manual
`workflow_dispatch` run of `internal-release.yml`; CI builds the bundle, uploads
to the Play internal track, and creates the tag and GitHub release.

## Checklist

1. Code is on `foldogram`, the mandatory device test passed from the application
   candidate being released via `com.rbnkv.foldogram.beta` (see Required Device
   Test in `docs/UPSTREAM_MERGE.md`), and the owner accepted the presented manual
   checklist after package, version, and candidate provenance were verified.
   An owner response such as "everything works, release it" is final device
   acceptance. Documentation-only commits after the tested application candidate
   do not invalidate it; application/build-input changes do. Local testing must
   not replace, clear, or uninstall the Play package `com.rbnkv.foldogram`. Only
   then may `foldogram` be pushed to the `fork` remote and released.
2. Pick the version. The tag must be STRICTLY greater than both the
   `gradle.properties` `APP_VERSION_NAME`/`APP_VERSION_CODE` baseline and the
   highest existing `v*` tag. After merging upstream `X.Y.Z` the base is `X.Y.Z`,
   so the first release is `vX.Y.(Z+1)` (e.g. base 12.8.1 -> first release
   v12.8.2; v12.8.1 would fail as equal to the base). Confirm the tag is free:
   `git ls-remote --tags fork v<X.Y.Z>`.
3. Trigger (the `-R` flag is mandatory):
   `gh workflow run internal-release.yml -R rybnikov/Telegram --ref foldogram -f release_tag=v<X.Y.Z> -f promote_release=true`
4. Watch, then VERIFY authoritatively (do not trust the watch exit code):
   `gh run view <run-id> -R rybnikov/Telegram --json conclusion`, and confirm the
   tag and a published GitHub release exist.

## Footguns

- `gh` defaults to `origin` = `DrKLO/Telegram`; always pass `-R rybnikov/Telegram`
  or it fails with HTTP 404.
- A tag equal to the gradle base fails at "Resolve release version" -> bump the
  PATCH and re-trigger; do not edit `gradle.properties` to work around it.
- CI creates and pushes the tag; never create or push it manually, and it must
  not pre-exist.
- Device acceptance comes from Foldogram Beta. Do not locally install a release
  artifact over the Play-installed `com.rbnkv.foldogram`; CI/Google Play owns
  that package transition.
- Once the verified Beta checklist is accepted, proceed to release preflight.
  Do not launch an emulator, create a profile, reinstall Beta, or require a
  fresh-install pass unless the owner explicitly requested that extra scope.
  The accepted device no longer being connected is not a blocker.
- `git push fork foldogram` triggers Foldogram CI. After an upstream merge, do
  not push or start the internal release from a stale "release it" instruction if
  the installed test build was later proven to have the wrong package/version/
  provenance or application/build inputs changed. Install the current build and
  wait for fresh owner acceptance only in those cases.
- `gh run watch --exit-status` has reported `0` on a real failure; always confirm
  via `gh run view --json conclusion` and tag/release existence.

Full reference: `docs/RELEASE.md`.
