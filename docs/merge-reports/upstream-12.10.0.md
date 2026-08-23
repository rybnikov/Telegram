# Upstream Merge Report

## Refs

Merge branch: `merge/upstream-12.10.0-into-foldogram`

Old upstream base: `9b50143d8896d255d03155598937e4f3e28afd86`

New upstream base: `3f03bfc73f1d176e349765c2990e52f490409813`

Foldogram tip before merge: `f34d2b18eaaf5d16a411ab1aafc2ba358c42601f`

Merge result commit: `cf1503899`

## Upstream Summary

The accepted upstream range spans Telegram Android 12.8.2 through 12.10.0.
The merged build identity is version `12.10.0`, code `7031`, while the
Foldogram application id remains `com.rbnkv.foldogram`.

The main product changes are Communities and their grouped dialog/admin flows,
rich-message and Instant View authoring (including tables, formulas, media,
buttons, and unsupported-block fallbacks), ephemeral bot messages, welcome
messages, gift-message presentation, and the corresponding TL schema updates
through layers 228-230.

The native/build layout changed substantially. Upstream replaced vendored
native source trees with eight pinned Git submodules for libvpx, dav1d, FFmpeg,
ogg, opus, opusfile, tlottie, and jlatexmath; upgraded the Gradle wrapper to
8.11.1; and added build-time Lottie metadata generation. Foldogram CI and the
internal-release checkout now initialize submodules recursively, and the root
Gradle settings explicitly map the jlatexmath library module required by
`TMessagesProj`.

## Gates

merge-delta-audit (advisory): pass, exit 0. The pre-merge-tip audit reported
eight rows. Six DB-version rows were intentionally relocated to keep released
Foldogram migrations frozen. The `ChatMessageCell` `Log` import is present in
both new upstream and the merge result, so it is no longer a fork-only line.
The `ChatActivity` closing-brace row was a line-diff false positive; compilation
and the canary validate the merged structure. Command:
`scripts/merge-delta-audit.sh 9b50143d8 3f03bfc73 f34d2b18eaaf5d16a411ab1aafc2ba358c42601f`

Post-merge merge-delta-audit with `HEAD`: pass, exit 0. Its very large advisory
output is expected because this upstream range removes/moves vendored native
trees, moves RecyclerView/ExoPlayer sources, and introduces submodules. Command:
`scripts/merge-delta-audit.sh 9b50143d8 3f03bfc73 HEAD`

check-fork-anchors: pass, exit 0.

check-secrets-policy: pass, exit 0, including a second run after all build
generators completed.

MergeRegressionCanaryTest: pass through `check-fork-anchors`, exit 0.

testHA_privateUnitTest: pass, exit 0.

compileHA_privateJavaWithJavac: pass, exit 0.

CI run: not run; local gates only. The branch is not pushed.

## Feature Matrix

| feature-id | status | notes |
| --- | --- | --- |
| ext-preview | conflict resolved | Preserved the external-preview table, schema/recovery registration, migration, and `SharedMediaLayout` preview lookahead while accepting upstream media changes. |
| nav-recovery | conflict resolved | Preserved per-activity fragment ownership and diagnostics; adapted root measurement to upstream `ActivityContentLayout`; `onConfigurationChanged` remains measure-driven. |
| fold-tablet | conflict resolved | Preserved measured-width invalidation, Main Tabs integration, tablet migration logging, and community-aware dialog placement. |
| cutout | conflict resolved | Adapted the side-cutout coverage to upstream's new `systemAndCutoutInsets` model and retained explicit `DisplayCutoutCompat` detection. |
| android-auto | pass | No direct conflict; registry anchors and unit canary passed. |
| maps-live-location | pass | No direct conflict; secrets policy and registry anchors passed. |
| places-index | pass | No direct conflict; registry anchors passed. |
| db-preview | conflict resolved | Kept released `174 -> 175` and `175 -> 176` steps fixed; appended upstream migrations as `176 -> 177 -> 178 -> 179`. |
| browser-iv | pass | No direct conflict; registry anchors passed. |
| identity | conflict resolved | Accepted upstream `12.10.0` / `7031`; retained `APP_PACKAGE=com.rbnkv.foldogram` and local BuildVars generation. |
| share-shortcuts | pass | No direct conflict; registry anchors passed. |
| ci-release | conflict resolved | Retained Foldogram build/release generators and made CI/release checkout all eight upstream submodules recursively. |
| secrets-policy | conflict resolved | Combined upstream ignore additions with Foldogram local-secret/signing ignores; no forbidden file is staged. |
| duress-passcode | conflict resolved | Preserved emergency-passcode imports, hidden-dialog filtering, and visible unread-count behavior while accepting upstream multi-account and Community notification logic. |

## Conflicts

Conflicts resolved:

- `.gitignore` (`secrets-policy`): kept Foldogram local signing/config ignores
  and accepted upstream's FFmpeg build-directory ignore.
- `TMessagesProj/build.gradle` (`identity`, `ci-release`): retained the
  Foldogram BuildVars generator and accepted upstream dependencies, including
  the jlatexmath project dependency.
- `DatabaseMigrationHelper.java` (`db-preview`, `ext-preview`): preserved the
  released Foldogram steps and appended all new upstream migrations above DB
  version 176 with idempotent table/index DDL.
- `MessagesStorage.java` (`db-preview`, `ext-preview`): set
  `LAST_DB_VERSION = 179`; retained external-preview fresh-create/recovery
  registration; added ephemeral and welcome-message tables to recovery.
- `NotificationsController.java` (`duress-passcode`): combined hidden-dialog
  filtering with upstream all-account filtering and Community exclusions.
- `DrawerLayoutContainer.java` (`cutout`): retained black side-cutout coverage
  using upstream's new inset state and single child-inset dispatch path.
- `SharedMediaLayout.java` (`ext-preview`): retained preview lookahead and
  dropped an obsolete, unused upstream constant from the conflicted hunk.
- `DialogsActivity.java` (`duress-passcode`, `fold-tablet`): kept emergency
  hooks and Foldogram Main Tabs menu entries outside the new Community-specific
  menu branch.
- `LaunchActivity.java` (`nav-recovery`, `fold-tablet`): kept per-instance
  fragment teardown and measure-driven tablet transitions while accepting
  upstream PiP cleanup, Community handling, and `ActivityContentLayout`.
- `MainTabsActivity.java` (`fold-tablet`): combined Foldogram diagnostics with
  upstream text-span imports.
- `strings.xml` (`duress-passcode`): retained Foldogram emergency strings and
  accepted upstream Community/rich/welcome/gift resources.
- `TMessagesProj_AppHockeyApp/build.gradle` (`ci-release`): retained generated
  Google-services handling while accepting upstream build changes.
- `gradle.properties` (`identity`): accepted version `12.10.0` / `7031` and
  retained the Foldogram package.

The post-conflict compile audit also restored `Log`/`FileLog` imports used by
fork navigation diagnostics and removed one diagnostic reference to upstream's
deleted `shadowTabletSide` view. These were silent auto-merge losses, not
textual conflicts.

Registry anchors changed: none.

Threshold/min_count changes: none.

Database migration changes: `MessagesStorage.LAST_DB_VERSION` moved from 176
to 179. Upstream's media index, ephemeral messages, and welcome messages were
appended without renumbering released Foldogram steps.

Secrets/config files classified: `.gitmodules` contains only reviewed HTTPS
source URLs and pinned gitlinks. No generated Google services file, signing
file, credential, `local.properties`, or local note is included.

## DB Upgrade Trace

Parked released Foldogram user at `user_version=176`: runs only the appended
steps. `176 -> 177` creates the media index with `IF NOT EXISTS`; `177 -> 178`
creates `ephemeral_messages` and its date index with `IF NOT EXISTS`; and
`178 -> 179` creates `welcome_messages` plus its indexes with
`IF NOT EXISTS`.

Older user at `user_version=175`: first runs the frozen Foldogram
`web_browser_settings` step `175 -> 176`, then the three appended upstream
steps through 179.

Older user at `user_version=174`: first runs the frozen external-preview
column migration `174 -> 175` through `executeNoException`, then the frozen
web-browser step and the three appended upstream steps. Neither released
Foldogram migration was moved or reused.

Fresh database creation: directly creates `web_browser_settings`, the external
preview schema, `ephemeral_messages`, and `welcome_messages`, then records
`user_version=179`. Recovery registration includes both new upstream tables and
the external-preview table.

## Submodule and Build Audit

All eight submodules initialize recursively at the exact gitlinks recorded by
upstream. The jlatexmath dependency is mapped to
`TMessagesProj/lib/jlatexmath/jlatexmath`, the actual Android library module,
rather than the submodule's parent build. Both GitHub Actions checkout paths
now use `submodules: recursive` so clean CI and internal-release runners see the
same tree used by local gates.

## Residual Risk

Known residual risks: the native dependency transition is unusually large and
static gates do not exercise all ABI/media paths. Upstream Community,
rich-message, ephemeral/welcome-message, and gift flows are new runtime surface
area. The DB chain is statically validated but must still be exercised against
a real released Foldogram database.

Manual device smoke needed: install over a released Foldogram database parked
at version 176 and verify startup/migration, then verify a fresh install. Also
smoke-test fold/tablet resize and rotation, landscape side cutout, emergency
passcode hidden chats and unread notifications, external previews, Communities,
rich/ephemeral/welcome messages, Android Auto, and representative audio/video
playback on the shipped ABIs.

Owner decisions needed: accept this report and complete the required device
smoke before merging the branch back into `foldogram`.
