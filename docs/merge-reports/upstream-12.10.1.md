# Upstream Merge Report

## Refs

Merge branch: `merge/upstream-12.10.1-into-foldogram`

Old upstream base: `3f03bfc73f1d176e349765c2990e52f490409813`

New upstream base: `62b56a07ca7e30e39f7fd00a6728d6bbd716ca1c`

Foldogram tip before merge: `794379d5aa08068e9665e68eaa32ce0c4c062531`

Merge result commit: `96f2bd21e`

## Upstream Summary

The merge updates the upstream application baseline from Telegram Android
12.10.0 (`7031`) to 12.10.1 (`7038`) while retaining the Foldogram package id
`com.rbnkv.foldogram` and release-time version override.

The largest change is in native/build infrastructure. Upstream converts
`libyuv` and `openh264` from vendored source trees to pinned Git submodules,
moves refreshed `tlottie` and new OpenH264 static libraries into `jni/prebuild`,
and rewrites the native link configuration around those libraries. It also
updates compile/target SDK and Build Tools to 36, CMake to 3.22.1, Android
Gradle Plugin to 8.10.1, Kotlin to 2.1.0, and Play Billing to 8.0.0.

Runtime changes include a foreground-aware debug ANR detector, FileLog
initialization changes, bot-forum draft cleanup, ephemeral-message restrictions,
rich-message unsupported-block and button-hit-area fixes, ActionBar/comment UI
changes, filter-tab null guards, typed report requests, and structural TL object
comparison for bot keyboards.

## Gates

merge-delta-audit (advisory): pass, exit 0, no collision rows. Command:
`scripts/merge-delta-audit.sh 3f03bfc73 62b56a07c 794379d5aa08068e9665e68eaa32ce0c4c062531`

check-fork-anchors: pass, exit 0, Robolectric/Gradle run with Java 21.

Static registry audit: pass, all 14 feature blocks and 143 anchors are present
at or above their configured thresholds.

check-secrets-policy: pass, exit 0.

MergeRegressionCanaryTest: pass through the targeted task and
`check-fork-anchors`, 14 feature blocks and 143 anchors.

testHA_privateUnitTest: pass, exit 0, Robolectric 4.16.1 on Java 21. The first
run exposed that Robolectric 4.14.1 only supports through SDK 35; the dependency
and CI test JDK were updated instead of pinning tests below target SDK 36.

compileHA_privateJavaWithJavac: pass, exit 0, Java 17.

CI run: not run. The branch has not been pushed.

## Feature Matrix

| feature-id | status | notes |
| --- | --- | --- |
| ext-preview | pass | `ChatMessageCell` auto-merged; all 20 cell markers, six storage markers, binder bridge, cache hydration, request, and click-dispatch anchors remain. The owner accepted the runtime checklist. |
| nav-recovery | pass | `LaunchActivity` and `ActionBarLayout` were not changed by this upstream range. `ApplicationLoader.isUiCompletelyPaused` and all navigation anchors remain. The owner accepted the predictive-back checklist. |
| fold-tablet | pass | Upstream additions in `AndroidUtilities` and `ChatActivity` auto-merged without deleting width-driven tablet detection or the pre-draw recovery hook. The owner accepted the fold/unfold and split-layout checklist. |
| cutout | pass | Protected drawer and v31 style paths were not changed. |
| android-auto | pass | The car dependency and paused-UI sorting gate remain. No merge-specific regression was reported during owner acceptance. |
| maps-live-location | pass | Map manifests and placeholder-based key injection were not changed. |
| places-index | pass | Geo extraction and navigation paths were not changed. |
| db-preview | pass | No database file or migration changed; `LAST_DB_VERSION` remains 179 and released Foldogram steps remain frozen. |
| browser-iv | pass | Browser Instant View paths were not changed. |
| identity | conflict resolved | Accepted `12.10.1` / `7038` and SDK 36; retained `APP_PACKAGE=com.rbnkv.foldogram`, app-module `APP_PACKAGE` wiring, `.beta` device identities, and `RESOLVED_APP_VERSION_NAME`. |
| share-shortcuts | pass | Share ranker and shortcut paths were not changed. |
| ci-release | pass | CI now uses Java 21 for Robolectric SDK 36 tests and switches back to Java 17 for Android compilation. Internal release semantics and recursive submodule checkout are unchanged. |
| secrets-policy | conflict resolved | Dropped upstream dummy signing passwords and retained local/env-based signing and Google-services generation. Secrets policy passes. |
| duress-passcode | pass | Emergency passcode paths were not changed and all registry anchors remain. Runtime hidden-chat smoke is pending. |

## Conflicts

Conflicts resolved:

- `TMessagesProj_App/build.gradle` (`identity`, `secrets-policy`): accepted SDK
  36 and retained the Foldogram release-time version override and Google-services
  generator.
- `gradle.properties` (`identity`, `secrets-policy`): accepted upstream version
  `12.10.1` / `7038` and Gradle heap settings; retained the Foldogram package,
  local signing policy, and removed upstream dummy passwords.
- `buildSrc/.../GenerateSchemeTask.kt` and `buildSrc/.../Rules.kt`
  (build tooling): accepted upstream's `RulesHolder` extraction so codegen has a
  single rules definition.
- `settings.gradle` (build tooling): accepted upstream's equivalent jlatexmath
  project mapping.
- `TMessagesProj/build.gradle` and `.github/workflows/ci.yml` (test tooling):
  upgraded Robolectric from 4.14.1 to 4.16.1 for SDK 36 support, ran test/anchor
  gates on Java 21, and retained Java 17 for Android compilation. No new test
  SDK pin was added.

Registry anchors changed: added explicit `.beta` suffix anchors for
`TMessagesProj_App` and `TMessagesProj_AppHockeyApp` after the device-test
runbook was corrected to protect the Play installation.

Threshold/min_count changes: no `min_count` was lowered. The canary exact-count
baseline was raised from 141 to 143 for the two added beta-identity anchors; the
stale runbook count was corrected from 13/111 to 14/143.

Database migration changes: none. The upstream range does not touch
`MessagesStorage`, `DatabaseMigrationHelper`, or external preview storage.

Secrets/config files classified: `.gitmodules` adds reviewed HTTPS gitlinks for
`libyuv` and `openh264`. No generated Google-services file, local signing file,
credential, `local.properties`, or local note is included.

## DB Upgrade Trace

No new migration executes for a released Foldogram user. Users parked at 174,
175, or 176 still traverse the already accepted frozen/appended chain through
179 exactly as documented in the 12.10.0 report. A user already at 179 remains
at 179. Fresh creation still records version 179 and creates the same upstream
and external-preview schemas. The merged beta was installed over the existing
12.10.0 beta data without clearing it and reached `LaunchActivity` on a cold
start with no SQLite exception or corruption signature. The migration graph is
unchanged, so no new migration step was expected.

## Submodule and Build Audit

All ten submodules initialize recursively at the recorded gitlinks. New links:

- `libyuv`: `28ce69c2744a6aafdb58564e7b884aec3f66be5f`
- `openh264`: `652bdb7719f30b52b08e506645a7322ff1b2cc6f`

`:TMessagesProj_AppHockeyApp:installAfatHA_private` completed successfully and
built the native library for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
The owner accepted the manual checklist covering representative H264/media
runtime behavior.

## Device Baseline

Connected device: OPPO CPH2671 (`220a0b5c`), Android 16 / API 36.

Installed existing-user baseline: `com.rbnkv.foldogram.beta`, version `12.10.0`,
version code `70319`, target SDK 35. It was installed through Android shell,
has been launched, and retains beta app data suitable for an in-place upgrade
test.

Installed candidate provenance: application/test commit `428310a32`, installed
with `:TMessagesProj_AppHockeyApp:installAfatHA_private` and Java 17. The update
completed without uninstalling or clearing beta. Installed metadata is version
`12.10.1`, version code `70389`, target SDK 36; `firstInstallTime` stayed
`2026-08-25 21:20:13`, confirming update-in-place. Final cold launch completed
in 421 ms, the process remained alive after 12 seconds, and its logcat contained
no fatal, SQLite, missing-native-library, or native-signal signature.

The Google Play package `com.rbnkv.foldogram` is intentionally outside the local
test path. Its version `12.10.1`, version code `120100019`, target SDK 35,
Play installer, first-install time, and last-update time were unchanged after the
beta install.

The commits after application/test commit `428310a32` change only the merge
report and agent/runbook instructions. They do not change application or build
inputs, so the installed candidate remains the release application candidate.

## Residual Risk

Known residual risks: the prebuilt OpenH264/libyuv transition is not covered by
Java compilation; target SDK 36 changes predictive-back and large-screen runtime
behavior; Billing 8 changes product detail responses; and upstream
`ReportBottomSheet` currently contains an unconditional `|| true` in the typed
report response branch, which may report network errors as success.

Manual device smoke: the owner tested the installed Foldogram Beta using the
merge-specific checklist covering fold/unfold and split layout, predictive-back,
external previews, emergency hidden chats/notifications, media/H264 behavior,
billing, rich/ephemeral messages, reporting, and other applicable integrations.
On 2026-08-29 the owner reported that everything works and explicitly instructed
the release. This is final device acceptance for the verified candidate. The
Play package remained untouched.

Owner decisions needed: none. Proceed with merge-back, push, and the requested
internal release.
