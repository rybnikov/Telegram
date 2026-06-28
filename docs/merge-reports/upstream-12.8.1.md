# Upstream Merge Report

## Refs

Merge branch: `merge/upstream-12.8.1`

Old upstream base: `9fea7264725bbac16e5bd5f18fe22d7c6e8a3117`

New upstream base: `9b50143d8896d255d03155598937e4f3e28afd86`

Foldogram tip before merge: `15d1369fe63580d93859a99c3dd144d6701f8a4c`

Merge result commit: `101ea33e1`

## Gates

merge-delta-audit (advisory): pass, exit 0. Command:
`scripts/merge-delta-audit.sh 9fea72647 9b50143d8 15d1369fe63580d93859a99c3dd144d6701f8a4c`

check-fork-anchors: pass, exit 0.

check-secrets-policy: pass, exit 0.

MergeRegressionCanaryTest: pass through `check-fork-anchors` and full
`:TMessagesProj:testHA_privateUnitTest`, exit 0.

testHA_privateUnitTest: pass, exit 0.

compileHA_privateJavaWithJavac: pass, exit 0.

CI run: not run; local gates only.

## Feature Matrix

| feature-id | status | notes |
| --- | --- | --- |
| ext-preview | conflict resolved | Preserved external preview imports/hooks in `ChatMessageCell` and `SharedLinkCell`; kept `MessagesStorage` storage delegates and fresh schema hook. |
| nav-recovery | conflict resolved | `LaunchActivity.onConfigurationChanged` keeps upstream reset/invalidate/checkLayout and Foldogram layout request. Canary passed. |
| fold-tablet | conflict resolved | Preserved `hideBottomPanel` setting/load/toggle and LaunchActivity measured-width reset path. |
| cutout | pass | No direct conflict; anchors passed. |
| android-auto | conflict resolved | Kept active `.auto.FoldogramCarAppService`, Auto metadata, and `media`/`template` automotive descriptor; accepted upstream `MusicBrowserService`. |
| maps-live-location | pass | No direct conflict; secrets gate passed and map placeholders stayed tracked. |
| places-index | pass | No direct conflict; anchors passed. |
| db-preview | conflict resolved | `LAST_DB_VERSION = 176`; frozen external preview migration remains `174 -> 175`; upstream `web_browser_settings` appended as `175 -> 176` with `CREATE TABLE IF NOT EXISTS`. |
| browser-iv | conflict resolved | Upstream moved browser settings from `SharedConfig` to `MessagesController`; Foldogram Instant View predicate was restored at the new `Browser.openUrl` call-site and registry anchor was updated. |
| identity | conflict resolved | Upstream version `12.8.1` / code `6916`; Foldogram package `com.rbnkv.foldogram` preserved. |
| share-shortcuts | pass | No direct conflict; anchors passed. |
| ci-release | pass | No direct conflict; workflow anchors passed. |
| secrets-policy | pass | No new forbidden local configs, generated Google services files, signing files, or credentials staged. |

## Conflicts

Conflicts resolved:

- `TMessagesProj/src/main/AndroidManifest.xml` (`android-auto`): accepted upstream `MusicBrowserService`; preserved Foldogram Auto metadata and active `.auto.FoldogramCarAppService`; did not switch to upstream's commented `.car.TelegramCarAppService`.
- `TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java` (`db-preview`): preserved released `external_previews_v1.extra` migration at `174 -> 175`; appended upstream `web_browser_settings` at `175 -> 176`; made the new upstream table creation idempotent.
- `TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java` (`db-preview`, `ext-preview`): set `LAST_DB_VERSION = 176`; retained fresh-create `web_browser_settings` and `ExternalPreviewStorage.createTables`.
- `TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java` (`fold-tablet`, `ext-preview`, `browser-iv`): preserved `hideBottomPanel` and `extendedPreviews`; dropped obsolete `customTabs` / `inappBrowser` SharedConfig state because upstream now owns browser settings in `MessagesController`.
- `TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java` (generated): used upstream 12.8.1 generated code as the base, including split `TL_update` / `TL_iv`; restored only the proven local compatibility `readParams` on `TL_themeDocumentNotModified_layer106`. Runbook gap: generated-code compatibility policy is not yet documented.
- `TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java` (`ext-preview`): kept Foldogram external preview imports and accepted upstream `Choreographer60FpsContent`.
- `TMessagesProj/src/main/java/org/telegram/ui/Cells/SharedLinkCell.java` (`ext-preview`): kept `ExternalLinkRouter` and accepted upstream `RichMessageLayout`.
- `TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java` (`nav-recovery`, `fold-tablet`): combined upstream tablet reset/invalidate/checkLayout with Foldogram frame layout request.
- `TMessagesProj/src/main/java/org/telegram/ui/ThemeActivity.java` (`browser-iv`, `fold-tablet`, `ext-preview`): browser row now reads `getMessagesController().isWebBrowserInAppEnabled()`; Foldogram `hideBottomPanel` and `extendedPreviews` rows preserved.
- `TMessagesProj/src/main/res/values/strings.xml` (upstream resource addition): kept upstream Wear auth strings and Foldogram existing strings.
- `TMessagesProj/src/main/res/xml/automotive_app_desc.xml` (`android-auto`): preserved `notification`, `media`, and `template` declarations.
- `gradle.properties` (`identity`): accepted upstream `12.8.1` / `6916`; preserved `APP_PACKAGE=com.rbnkv.foldogram`.

Registry anchors changed: `browser-iv` anchor changed from removed
`SharedConfig.inappBrowser || isInstantViewOpen()` to
`isWebBrowserOpenInApp(uri.toString()) || isInstantViewOpen()`.

Threshold/min_count changes: none.

Database migration changes: `MessagesStorage.LAST_DB_VERSION` moved from 175 to
176; upstream `web_browser_settings` migration appended above the released
Foldogram DB floor.

Secrets/config files classified: no new forbidden files. Existing tracked
Huawei config remains pre-existing tracked project state; `check-secrets-policy`
passed.

## DB Upgrade Trace

Parked released Foldogram user at `user_version=175`: migrations below 175 are
skipped because this user already received `external_previews_v1.extra`.
Only `175 -> 176` runs, creating `web_browser_settings` with
`CREATE TABLE IF NOT EXISTS`, then setting `PRAGMA user_version = 176`.

Older user at `user_version=174`: runs frozen Foldogram
`external_previews_v1.extra` through `executeNoException`, sets version 175,
then runs upstream `web_browser_settings` `175 -> 176`. The preview column is
not renumbered.

Fresh database creation: create path directly creates both
`web_browser_settings` and `ExternalPreviewStorage` schema, then sets
`user_version` to `LAST_DB_VERSION` 176. Older upgrade paths pass through
`174 -> 175 -> 176` in order.

## Delta Audit

Advisory findings:

- `DatabaseMigrationHelper.java` `PRAGMA user_version = 174` and `version = 174`:
  consciously preserved. These are part of the stable `173 -> 174` step before
  the frozen Foldogram `174 -> 175` preview migration.
- `DialogsActivity.java` `if (closeKeyboard && fragmentSearchField.editText.isFocused()) {`:
  present in old fork, new upstream, and merged worktree. No recovery needed;
  advisory false positive from the line-diff comparison.

## Residual Risk

Known residual risks: generated `TLRPC.java` compatibility restoration is based
on the single proven `TL_themeDocumentNotModified_layer106.readParams` delta;
the runbook should document this policy before the next generated-code conflict.

Manual device smoke needed: Android Auto DHU, fold/tablet resize and
multi-window, external preview render/open/cache paths, browser vs Instant View
URL opening.

Owner decisions needed: decide whether to document the generated-code
compatibility rule in `docs/UPSTREAM_MERGE.md`, and whether upstream's new
commented `org.telegram.messenger.car.TelegramCarAppService` should ever be
mapped into Foldogram Auto instead of the current `.auto` service.
