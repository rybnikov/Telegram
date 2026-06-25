# Telegram Android Build And Release Notes

## Project defaults

- Base package: `com.rbnkv.foldogram`
- Current app version from `gradle.properties`: `12.5.4` / `6609`
- When starting feature work, branch from `foldogram`, not from random feature branches.
- Use Java 17 for Gradle commands:
  - `JAVA_HOME=$(/usr/libexec/java_home -v 17)`

## Beta build and install

- The working beta flow for device testing is the `AppHockeyApp` module, not `TMessagesProj_App`.
- Use:
  - `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj_AppHockeyApp:compileAfatHA_privateJavaWithJavac`
  - `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj_AppHockeyApp:installAfatHA_private`
- This installs package `com.rbnkv.foldogram.beta`.
- The connected test phone used in this repo work is:
  - device id `220a0b5c`
  - model `CPH2671`

## Google services and signing

- `TMessagesProj_AppHockeyApp` must not keep a real `google-services.json` in git anymore. The tracked file was intentionally removed after a leaked Firebase API key incident.
- `TMessagesProj_AppHockeyApp/build.gradle` now generates `google-services.json` at build time from:
  - `GOOGLE_SERVICES_JSON_HOCKEYAPP`, else `GOOGLE_SERVICES_JSON`
  - or `local.properties` keys `google.services.hockeyapp.json.path` / `google.services.json.path`
  - or a dummy fallback JSON if no real config is provided
- **NEVER commit any real `google-services.json`.** Both repo-level and module-level `.gitignore` now block it; if it appears in `git diff --cached`, unstage it immediately.
- Current known-good local Firebase config file:
  - `/Users/rbnkv/Downloads/google-services (6).json`
- Recommended local setup:
  - add `google.services.hockeyapp.json.path=/Users/rbnkv/Downloads/google-services (6).json` to `local.properties`
- CI uses the rotated Firebase config via the `GOOGLE_SERVICES_JSON` secret.
- Beta signing in `TMessagesProj_AppHockeyApp/build.gradle` uses the keystore from `../TMessagesProj/config/release.keystore` and passwords from `local.properties`.

## Release bundle flow

- Release bundle goes through `TMessagesProj_App`, not `AppHockeyApp`.
- Use:
  - `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj_App:bundleAfatRelease`
- `TMessagesProj_App/build.gradle` supports release overrides through:
  - `RELEASE_VERSION_CODE`
  - `RELEASE_VERSION_NAME`
  - `RELEASE_KEYSTORE_PATH`
  - `RELEASE_KEY_PASSWORD`
  - `RELEASE_KEY_ALIAS`
  - `RELEASE_STORE_PASSWORD`
- Output bundle path is under:
  - `TMessagesProj_App/build/outputs/bundle/afatRelease/`

## CI/CD release flow

- GitHub Actions workflows live in:
  - `.github/workflows/internal-release.yml`
  - `.github/workflows/release-cache-warm.yml`
- Real internal release is driven by pushing a git tag that matches `vX.Y.Z`.
- `internal-release.yml`:
  - triggers on `push` tags `v*`
  - runs only when `github.actor == 'rybnikov'`
  - hard-checks the exact format `vMAJOR.MINOR.PATCH`
  - computes:
    - `RELEASE_VERSION_NAME=MAJOR.MINOR.PATCH`
    - `RELEASE_VERSION_CODE=MAJOR * 1000000 + MINOR * 1000 + PATCH`
  - rejects tags that are not higher than:
    - `APP_VERSION_NAME` / `APP_VERSION_CODE` from `gradle.properties`
    - the highest previous `v*` tag in git
  - builds with:
    - `./gradlew :TMessagesProj_App:bundleAfatRelease`
  - publishes package `com.rbnkv.foldogram`, not `com.rbnkv.foldogram.beta`
  - uploads the `.aab` to Google Play `internal` track
  - then promotes the draft release to `completed`
  - publishes GitHub release metadata and attaches checksum artifact

## CI/CD secrets and environment

- The release workflows use GitHub environment `play-internal`.
- Required secrets used by CI:
  - `TELEGRAM_APP_ID`
  - `TELEGRAM_APP_HASH`
  - `TELEGRAM_PLAYSTORE_URL`
  - `TELEGRAM_HUAWEI_APP_ID`
  - `GOOGLE_SERVICES_JSON`
  - `KEY_ALIAS`
  - `KEY_PASSWORD`
  - `KEYSTORE_PASSWORD`
  - `KEYSTORE_BASE64`
  - `PLAY_SERVICE_ACCOUNT_JSON`
- CI restores the keystore into `${RUNNER_TEMP}/release.keystore` and exports:
  - `RELEASE_KEYSTORE_PATH`
  - `RELEASE_VERSION_NAME`
  - `RELEASE_VERSION_CODE`

## CI/CD operator notes

- To cut an internal release:
  - ensure the target commit is on the correct branch/state
  - create and push a tag like `v12.5.7`
  - release will only run if the tag push is done by `rybnikov`
  - GitHub Actions will do the rest if secrets/environment are valid
- For follow-up bugfix releases:
  - land the fix as a normal commit
  - push the next higher tag
  - do not reuse or move an existing release tag
  - do not rely on editing `gradle.properties` for each release; CI derives release version from the tag and only requires it to be higher than the baseline in `gradle.properties`
- This repo supports fast repeated internal releases in one session; the normal loop is:
  - fix bug
  - commit
  - push next `vX.Y.Z` tag
  - let `internal-release.yml` rebuild and publish
- To warm native caches before a release:
  - run `Android Release Cache Warm`
  - provide `release_tag` like `v12.5.7`
- The cache-warm workflow only builds `:TMessagesProj_App:bundleAfatRelease` and saves `.cxx` cache state; it does not upload to Play.
- Cache warm is optional, but useful before urgent follow-up releases when native rebuild time matters.

## Upstream merge procedure

- Do not apply source snapshots over `foldogram` or feature branches. Snapshot-style updates previously erased fork hooks silently.
- Keep upstream integration on a separate branch with real git history, then merge that branch into `foldogram` and resolve conflicts explicitly.
- Before accepting an upstream merge, run the merge canary:
  - `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj:testHA_privateUnitTest --tests '*MergeRegressionCanaryTest'`
- Also run the source delta audit before accepting the merge:
  - `scripts/merge-delta-audit.sh <old-upstream-base> <new-upstream-base> <merged-fork-tip>`
- Review every line printed by the delta audit. Lines may be legitimate upstream adoption, but missing `FOLDOGRAM-EXT-PREVIEW`, navigation reset, Android Auto, or release/security hooks must be restored before release.
- After the canary and audit, run the normal compile and unit suite:
  - `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj:compileHA_privateJavaWithJavac`
  - `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :TMessagesProj:testHA_privateUnitTest`

## Sensitive files — do not commit

- any `google-services.json` with real Foldogram config — see "Google services and signing" above.
- `local.properties` — contains keystore passwords.
- `*.keystore`, `*.jks` — signing keys.
- Any file containing API keys, tokens, or credentials for Foldogram services.
- Before every commit, check `git diff --cached --name-only` for these files. If any appear — unstage them with `git reset HEAD <file>`.

## Practical rules

- For fast iteration on phone, build and install `:TMessagesProj_AppHockeyApp:installAfatHA_private`.
- For store/distribution artifacts, build `:TMessagesProj_App:bundleAfatRelease`.
- Do not assume the checked-in Firebase config is valid for beta; verify package coverage before spending time debugging build failures.
- After a successful device install, prefer validating behavior on the phone before doing more refactors.
