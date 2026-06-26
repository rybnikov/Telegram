# Foldogram Fork Features

This file is the single tracked source of truth for Foldogram-specific behavior
that must survive upstream merges. Each feature section has prose for agents and
a strict `json` block for future CI/canary checks.

If prose and a `json` block disagree, the `json` block is authoritative for CI.
Update the prose in the same commit so agents and automation stay aligned.

Agents resolving upstream conflicts must protect invariants, not lines. If
upstream now satisfies the invariant, remove the fork hook. If upstream rewrote
the surrounding code, adapt the hook. Never choose `ours` or `theirs` blindly.

## ext-preview

Human explanation: Foldogram renders richer previews for external media sites
such as Instagram, TikTok, YouTube, X, Pinterest, and map links. This code was
split out of upstream-heavy files so future merges cannot silently drop preview
hydration, cache persistence, poster rendering, or playback dispatch. Some
anchors are referenced-from heuristics: for example, `ExternalPreviewCellBinder`
being mentioned in `ChatMessageCell` proves the bridge is present, while the
behavioral resolver/mapper tests prove the hot path.

Invariant: External previews keep rendering and opening with Foldogram behavior.
External videos render as poster plus play without a fake Telegram document chip,
clicks route through `PreviewClickDispatcher`, cached previews survive restart,
and preview storage remains available through `MessagesStorage`.

Conflict policy: When upstream changes `ChatMessageCell`, `MessagesStorage`, or
browser preview code, first check whether upstream now implements the same
preview invariant. If it does, remove the obsolete hook and adjust the registry
in a dedicated baseline commit. If not, adapt the Foldogram hook to the new
upstream structure and keep the behavior tests green. Do not lower marker counts
inside a conflict-resolution commit.

```json
{"id":"ext-preview","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java","contains":"// FOLDOGRAM-EXT-PREVIEW:","min_count":20},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java","contains":"// FOLDOGRAM-EXT-PREVIEW:","min_count":6},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java","contains":"ExternalPreviewCellBinder"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java","contains":"ExternalPreviewStorage"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalPreviewManager.java","contains":"public static boolean openCachedPreview"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/PreviewClickDispatcher.java","contains":"public final class PreviewClickDispatcher"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalHttpClient.java","contains":"public final class ExternalHttpClient"}],"tests":["MergeRegressionCanaryTest","InstagramResolverTest","TikTokResolverTest","YouTubeResolverTest","PreviewMapperTest","PreviewClickDispatcherTest","ExternalHttpClientTest"]}
```

## nav-recovery

Human explanation: Foldogram carries navigation recovery fixes for foldable and
tablet flows where back gestures, external activities, and layout migration can
leave zombie fragments or stuck animation state. These fixes were added after
real device regressions in split/tablet mode.

Invariant: Back navigation and fragment stack migration must not leave stuck
animations, zombie fragments, or a broken split layout after lifecycle changes,
external activity returns, or tablet/split reflows.

Conflict policy: If upstream rewrites `ActionBarLayout` or `LaunchActivity`,
verify whether the new implementation already clears stale animation and resets
finished fragments during migration. If yes, remove the duplicate Foldogram
hook. If not, port the invariant to the new code path and keep the canary green.
Do not preserve old code mechanically if upstream changed the fragment model.

```json
{"id":"nav-recovery","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java","contains":"private void forceResetAnimationState()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java","contains":"forceResetAnimationState();","min_count":5},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java","contains":"resetNavigationStateIfNeeded"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java","contains":"animationInProgressStartTime"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"chatFragment.resetFragment();"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java","contains":"public static boolean isUiCompletelyPaused()"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java","contains":"if (chatsDict == null && ApplicationLoader.isUiCompletelyPaused())"}],"tests":["MergeRegressionCanaryTest"]}
```

## fold-tablet

Human explanation: Foldogram changes tablet/foldable detection and list layout
behavior so OPPO Find N style split/tablet mode does not lose the chat pane or
misclassify the window after resize. It also carries bottom-panel observer hooks
for foldable UI state.

Invariant: Tablet mode is driven by the measured window width, split layout can
migrate without losing the active chat, and transient empty list layouts do not
trigger false pagination/filter loading.

Conflict policy: If upstream changes tablet detection, `DialogsActivity` list
loading, or bottom-panel notification wiring, compare the new behavior with the
invariant. Keep Foldogram's width-driven semantics unless upstream has a proven
equivalent. Do not drop `hideBottomPanelChanged` consumers just because upstream
moved observer wiring.

```json
{"id":"fold-tablet","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java","contains":"hideBottomPanelChanged"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java","contains":"postNotificationName(NotificationCenter.hideBottomPanelChanged)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java","contains":"transientEmptyLayout"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java","contains":"hideBottomPanelChanged"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/MainTabsActivity.java","contains":"hideBottomPanelChanged"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java","contains":"public static boolean isTabletForce()"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java","contains":"widthDp >= 600 && heightDp >= 320"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"private void invalidateTabletMode()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"checkTabletLayoutInvariant"}],"tests":["MergeRegressionCanaryTest"]}
```

## android-auto

Human explanation: Foldogram has an Android Auto messaging surface with chat
tabs, compose/dictation flows, voice recording, and dialog repositories. It also
depends on the paused-UI sort gate so Auto sessions can still update dialogs when
the phone UI is paused.

Invariant: Android Auto remains declared in the manifest, the car app service
creates a Foldogram session, tabbed chat lists remain available, and dialog
sorting continues to work while the phone UI is fully paused.

Conflict policy: If upstream changes manifests, dependencies, or dialog sorting,
keep Android Auto declarations and the paused-UI sorting invariant. If upstream
adds an equivalent Auto implementation, map Foldogram features onto it before
removing local classes. Do not delete Auto classes as unused without checking
manifest and DHU behavior.

```json
{"id":"android-auto","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/messenger/auto/FoldogramCarAppService.java","contains":"public class FoldogramCarAppService extends CarAppService"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/auto/FoldogramAutoSession.java","contains":"public class FoldogramAutoSession extends Session"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/auto/ChatListScreen.java","contains":"builder.addTab(buildTab(AutoPrimarySection.UNREAD"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/auto/AutoPrimarySection.java","contains":"BOTS(\"tab_bots\""},{"path":"TMessagesProj/src/main/AndroidManifest.xml","contains":".auto.FoldogramCarAppService"},{"path":"TMessagesProj/src/main/AndroidManifest.xml","contains":"androidx.car.app.CarAppService"},{"path":"TMessagesProj/build.gradle","contains":"androidx.car.app:app:1.7.0"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java","contains":"if (chatsDict == null && ApplicationLoader.isUiCompletelyPaused())"}],"tests":["MergeRegressionCanaryTest"]}
```

## maps-live-location

Human explanation: Foldogram release builds use Google Maps SDK metadata through
manifest placeholders so live location and map screens can render after key
rotation. This is separate from Places indexing for Android Auto.

Invariant: Debug and release map manifests keep the `com.google.android.geo.API_KEY`
metadata wired to the `googleMapsApiKey` placeholder. The real key is supplied by
the build environment and must not be hardcoded into tracked manifests.

Conflict policy: If upstream changes map manifests, preserve placeholder-based
key injection. If upstream replaces the maps provider, verify live location
rendering before removing these anchors. Never commit a real Maps API key.

```json
{"id":"maps-live-location","criticality":"high","anchors":[{"path":"TMessagesProj/config/release/AndroidManifest.xml","contains":"com.google.android.geo.API_KEY"},{"path":"TMessagesProj/config/release/AndroidManifest.xml","contains":"${googleMapsApiKey}"},{"path":"TMessagesProj/config/debug/AndroidManifest.xml","contains":"com.google.android.geo.API_KEY"},{"path":"TMessagesProj/config/debug/AndroidManifest.xml","contains":"${googleMapsApiKey}"},{"path":"TMessagesProj/config/release/AndroidManifest_SDK23.xml","contains":"com.google.android.geo.API_KEY"}],"tests":["MergeRegressionCanaryTest"]}
```

## places-index

Human explanation: Foldogram extracts geo destinations from recent messages and
map links so Android Auto can offer navigation without picking up the phone. In
the current code this lives in `org.telegram.messenger.auto.GeoExtractor`, not in
a standalone `GeoLocationExtractor`.

Invariant: Recent dialog messages can be scanned for Telegram geo media, live
location, and supported map links; Auto repositories can surface a destination
and open navigation through `google.navigation:q=lat,lng`.

Conflict policy: If upstream changes message media structures, link preview
parsing, or Auto repositories, keep the extraction invariant rather than the
current class shape. If the feature moves out of Auto into shared media indexing,
update the anchors and tests in the same commit.

```json
{"id":"places-index","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/messenger/auto/GeoExtractor.java","contains":"public class GeoExtractor"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/auto/GeoExtractor.java","contains":"extractFromMessages"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/auto/AutoGeoRepository.java","contains":"GeoExtractor.extractFromMessages"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/auto/AutoConversationItemFactory.java","contains":"google.navigation:q="},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/maps/MapsLinkParser.java","contains":"maps.app.goo.gl"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/maps/MapsMediaResolver.java","contains":"MapsLinkParser.parse"}],"tests":["MergeRegressionCanaryTest"]}
```

## db-preview

Human explanation: External previews persist in SQLite so links can hydrate after
restart and avoid repeated network fetches. The database surface is deliberately
delegated to `ExternalPreviewStorage` to keep upstream `MessagesStorage` merge
conflicts smaller.

Invariant: `external_previews_v1` schema, migrations, read/write delegates, and
format-version purge remain intact. Fork database migrations must stay one step
above the upstream version when conflicts occur.

Conflict policy: On any database conflict, stop and inspect `LAST_DB_VERSION`,
upstream migrations, and Foldogram preview migrations together. Never choose
`ours` or `theirs` for migration blocks. If upstream increments the DB version,
renumber Foldogram migration steps to upstream+1 and keep preview storage data
compatible or intentionally purged.

```json
{"id":"db-preview","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java","contains":"public final static int LAST_DB_VERSION = 175"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalPreviewStorage.java","contains":"public static final String TABLE_NAME = \"external_previews_v1\""},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalPreviewStorage.java","contains":"CREATE TABLE external_previews_v1"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java","contains":"ALTER TABLE external_previews_v1 ADD COLUMN extra TEXT"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/PreviewRepository.java","contains":"EXTERNAL_PREVIEW_FORMAT_VERSION = 3"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java","contains":"public void putExternalPreview"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java","contains":"public void getExternalPreview"}],"tests":["MergeRegressionCanaryTest","PreviewMapperTest"]}
```

## browser-iv

Human explanation: Foldogram adjusts browser/opening behavior around Instant View
state so custom tabs and in-app browser choices do not conflict with active
Instant View screens.

Invariant: Browser URL opening can detect whether Instant View is open and use
that state when deciding between custom tabs, in-app browser, and normal external
opening.

Conflict policy: If upstream rewrites `Browser.openUrl`, keep an explicit
Instant View state predicate or prove the new upstream path has equivalent
behavior. Do not remove `isInstantViewOpen` just because it looks like a small
helper.

```json
{"id":"browser-iv","criticality":"med","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/Browser.java","contains":"public static boolean isInstantViewOpen()"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/Browser.java","contains":"SharedConfig.inappBrowser || isInstantViewOpen()"}],"tests":["MergeRegressionCanaryTest"]}
```

## identity

Human explanation: Foldogram is published as `com.rbnkv.foldogram`, not the
upstream Telegram package. App modules derive their application id from
`APP_PACKAGE`, and release metadata and Play URLs must stay Foldogram-specific.

Invariant: All app variants use the Foldogram package identity, beta variants add
their suffixes on top of it, and release metadata points to the Foldogram Play
listing.

Conflict policy: If upstream changes Gradle packaging or manifests, preserve
`APP_PACKAGE=com.rbnkv.foldogram` as the source of truth. Do not hardcode the
upstream package into app modules or Play release workflow.

```json
{"id":"identity","criticality":"high","anchors":[{"path":"gradle.properties","contains":"APP_PACKAGE=com.rbnkv.foldogram"},{"path":"TMessagesProj_App/build.gradle","contains":"defaultConfig.applicationId = APP_PACKAGE"},{"path":"TMessagesProj_AppHockeyApp/build.gradle","contains":"defaultConfig.applicationId = APP_PACKAGE"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java","contains":"https://play.google.com/store/apps/details?id=com.rbnkv.foldogram"},{"path":"TMessagesProj/src/main/AndroidManifest.xml","contains":"${applicationId}.provider"}],"tests":["MergeRegressionCanaryTest"]}
```

## ci-release

Human explanation: Foldogram has a manual internal-release GitHub workflow for
building, uploading to Google Play internal, optionally promoting, tagging, and
publishing GitHub release metadata. This flow was hardened after Play promote and
credential rotation failures.

Invariant: Internal release remains manual via `workflow_dispatch`, restricted to
the `foldogram` branch and owner, uses Foldogram package name, uploads as draft,
and treats automatic Play promote failure as non-fatal when Google Play still
requires manual promotion.

Conflict policy: If upstream changes workflows, do not replace the Foldogram
internal release flow with upstream release automation. Keep release secrets out
of logs and keep promote optional unless Google Play behavior is revalidated.

```json
{"id":"ci-release","criticality":"high","anchors":[{"path":".github/workflows/internal-release.yml","contains":"workflow_dispatch:"},{"path":".github/workflows/internal-release.yml","contains":"github.actor == 'rybnikov' && github.ref == 'refs/heads/foldogram'"},{"path":".github/workflows/internal-release.yml","contains":"GOOGLE_SERVICES_JSON: ${{ secrets.GOOGLE_SERVICES_JSON }}"},{"path":".github/workflows/internal-release.yml","contains":"packageName: com.rbnkv.foldogram"},{"path":".github/workflows/internal-release.yml","contains":"status: draft"},{"path":".github/workflows/internal-release.yml","contains":"Promote Google Play internal release"},{"path":".github/workflows/internal-release.yml","contains":"Automatic Play promote failed; continuing"}],"tests":["MergeRegressionCanaryTest"]}
```

## secrets-policy

Human explanation: Foldogram already had a Firebase credential incident. Generated
app-module `google-services.json` files, local agent notes, signing files, and
real API keys must not be newly committed. Current history still contains some
legacy/upstream tracked config files, so future strict secret checks must classify
legacy tracked files separately instead of silently expanding the tracked surface.

Invariant: `CLAUDE.md` remains local and ignored, generated app `google-services.json`
files remain ignored, app modules generate Google services config from env/local
inputs, and release workflows consume secrets through GitHub secrets without
printing their contents.

Conflict policy: If upstream adds or rewrites Firebase/Google service config,
stop and classify the file before committing. Do not add real generated app
configs to git. If a check must allow a legacy tracked upstream file, document the
allowlist in the check and do not broaden it without review.

```json
{"id":"secrets-policy","criticality":"high","anchors":[{"path":".gitignore","contains":"CLAUDE.md"},{"path":".gitignore","contains":"google-services.json"},{"path":".gitignore","contains":"**/google-services.json"},{"path":"TMessagesProj_AppHockeyApp/.gitignore","contains":"google-services.json"},{"path":"TMessagesProj_App/build.gradle","contains":"GOOGLE_SERVICES_JSON"},{"path":"TMessagesProj_AppHockeyApp/build.gradle","contains":"GOOGLE_SERVICES_JSON_HOCKEYAPP"},{"path":".github/workflows/internal-release.yml","contains":"secrets.GOOGLE_SERVICES_JSON"}],"tests":["MergeRegressionCanaryTest"]}
```
