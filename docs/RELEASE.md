# Foldogram Internal Release

Internal releases of the Foldogram fork go to the Google Play **internal** track
through the GitHub Actions workflow `internal-release.yml` ("Android Internal
Release"). A release is a manual `workflow_dispatch` run — **CI creates and
pushes the tag itself**; you never create or push a release tag by hand.

## Prerequisites

- The code to release is on `foldogram`.
- The mandatory device test has passed on the exact build being released (see
  "Required Device Test" in `docs/UPSTREAM_MERGE.md`). Every upstream merge must
  be device-tested before it is released.
- The owner has accepted that installed build after the package version/commit
  was verified. If testing was later found to have used a missing, disconnected,
  or different-version build, any earlier release approval is stale.
- `foldogram` is pushed to the `fork` remote (`rybnikov/Telegram`) and up to
  date, but only after the device-test acceptance above. A push to this branch
  triggers Foldogram CI, so it is part of the post-acceptance release boundary.
  The workflow guard requires `github.ref == refs/heads/foldogram`, so the branch
  must already carry the exact code you want released.
- `gh` is authenticated as `rybnikov` (the guard requires
  `github.actor == 'rybnikov'`).

## Version selection (critical)

The release tag drives the published version. CI computes:

- `RELEASE_VERSION_NAME = MAJOR.MINOR.PATCH` (from the tag `vMAJOR.MINOR.PATCH`)
- `RELEASE_VERSION_CODE = MAJOR*1000000 + MINOR*1000 + PATCH`

CI **rejects** the tag unless it is **strictly greater** than **both**:

1. the `gradle.properties` baseline (`APP_VERSION_NAME` and `APP_VERSION_CODE`), and
2. the highest existing `v*` tag.

A tag equal to the baseline fails at the **Resolve release version** step with:

    Release version must be higher than baseline version <X> from gradle.properties

**Fork convention:** `gradle.properties` holds the upstream sync version (the
base); releases are patches **above** the base.

Algorithm to pick the next tag:

1. `base` = `APP_VERSION_NAME` in `gradle.properties` (e.g. `12.8.1`).
2. `highest` = highest existing `v*` tag:
   `git ls-remote --tags fork 'v*' | sed 's#.*refs/tags/##' | sort -V | tail -1`
3. `next` = increment the PATCH of `max(base, highest-without-'v')` ->
   `vMAJOR.MINOR.(PATCH+1)`.
4. Confirm the chosen tag is free: `git ls-remote --tags fork <tag>`.

**Worked example (real incident, 2026-06-29):** after merging upstream `12.8.1`
the base became `12.8.1` while the highest tag was `v12.7.6`. Releasing `v12.8.1`
FAILED (equal to the base). The correct first release was **`v12.8.2`**.

**Recovery:** if a run fails at *Resolve release version* with the baseline
message, the tag was not strictly above the gradle base -> bump the PATCH and
re-trigger. Do **not** edit `gradle.properties` to work around it.

## Pre-flight (read-only)

    gh auth status                                   # logged in as rybnikov
    git ls-remote --tags fork v<X.Y.Z>               # chosen tag must be free
    git show foldogram:gradle.properties | grep APP_VERSION
    git ls-remote --tags fork 'v*' | sed 's#.*refs/tags/##' | sort -V | tail -1

## Trigger

    gh workflow run internal-release.yml \
      -R rybnikov/Telegram \
      --ref foldogram \
      -f release_tag=v<X.Y.Z> \
      -f promote_release=true

- `-R rybnikov/Telegram` is **mandatory**. Without it `gh` targets the default
  remote `origin` = `DrKLO/Telegram` (upstream) and fails with
  `HTTP 404: workflow internal-release.yml not found`.
- `promote_release=true` promotes the Play internal draft to completed (it
  tolerates a draft-app rejection and stays draft). `false` leaves it as a draft.

## Monitor and verify

    gh run list -R rybnikov/Telegram --workflow=internal-release.yml -L 1   # get <run-id>
    gh run watch <run-id> -R rybnikov/Telegram

**Verify the outcome authoritatively — do not trust the `gh run watch` exit
code** (it has reported `0` on a real failure):

    gh run view <run-id> -R rybnikov/Telegram --json status,conclusion
    git ls-remote --tags fork v<X.Y.Z>
    gh release view v<X.Y.Z> -R rybnikov/Telegram --json tagName,isDraft,url

A successful release shows `conclusion: success`, the tag `v<X.Y.Z>` on the fork,
and a published (non-draft) GitHub release.

## What CI does

Builds `:TMessagesProj_App:bundleAfatRelease`, uploads the `.aab` to the Google
Play **internal** track (draft), optionally promotes it, generates checksums,
then creates and pushes the `v<X.Y.Z>` tag and publishes the GitHub release.
Published package is `com.rbnkv.foldogram` (not `com.rbnkv.foldogram.beta`).

## Secrets

All release secrets (keystore, Play service account, Firebase config) live in the
GitHub environment `play-internal` and in local `local.properties` — never in the
repository. If a secret-like file appears in `git diff --cached`, unstage it.

## Quick checklist (common footguns)

1. `gh workflow run` / `gh run watch` need `-R rybnikov/Telegram`, or they 404 on
   DrKLO upstream.
2. The tag must be strictly greater than the `gradle.properties` base **and** the
   highest `v*` tag. After merging upstream `X.Y.Z`, the first release is
   `vX.Y.(Z+1)`.
3. `git push fork foldogram` triggers Foldogram CI. Do not push or release an
   upstream merge until the current installed build has fresh owner acceptance.
4. CI creates and pushes the tag — never create or push it manually; it must not
   pre-exist.
5. Verify with `gh run view --json conclusion` plus tag/release existence; do not
   trust the `gh run watch` exit status.
