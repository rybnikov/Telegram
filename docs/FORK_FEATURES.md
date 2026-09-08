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
{"id":"ext-preview","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java","contains":"// FOLDOGRAM-EXT-PREVIEW:","min_count":20},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java","contains":"// FOLDOGRAM-EXT-PREVIEW:","min_count":6},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java","contains":"ExternalPreviewCellBinder"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java","contains":"ExternalPreviewManager.applyCachedPreviewIfAvailable(messageObject)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java","contains":"ExternalLinkRouter.requestPreviewIfNeeded(messageObject)"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java","contains":"ExternalPreviewStorage"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalPreviewManager.java","contains":"public static boolean applyCachedPreviewIfAvailable"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalPreviewManager.java","contains":"public static void requestPreviewIfNeeded"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalPreviewManager.java","contains":"public static boolean openCachedPreview"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalLinkRouter.java","contains":"ExternalPreviewManager.openCachedPreview"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/PreviewClickDispatcher.java","contains":"public final class PreviewClickDispatcher"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalHttpClient.java","contains":"public final class ExternalHttpClient"}],"tests":["MergeRegressionCanaryTest","InstagramResolverTest","TikTokResolverTest","YouTubeResolverTest","PreviewMapperTest","PreviewClickDispatcherTest","ExternalHttpClientTest"]}
```

## nav-recovery

Human explanation: Foldogram carries navigation recovery fixes for foldable and
tablet flows where back gestures, predictive-back cancellation, external
activities, multi-window changes, multi-instance activity teardown, and layout
migration can leave zombie fragments or stuck animation state. These fixes were
added after real device regressions in split/tablet mode.

Invariant: Back navigation and fragment stack migration must not leave stuck
animations, stale predictive-back state, zombie fragments, destroyed global UI
from another LaunchActivity task, or a broken split layout after lifecycle
changes, external activity returns, multi-window transitions, or tablet/split
reflows. `LaunchActivity.onConfigurationChanged` must not run immediate tablet
state resets or layout checks; tablet/layout transitions stay measure-driven
through `updateDisplaySizeFromRootMeasure`, `scheduleWindowWidthChanged`, and
`onWindowWidthChanged` so anti-flap freeze logic can run.

Conflict policy: If upstream rewrites `ActionBarLayout` or `LaunchActivity`,
verify whether the new implementation already clears stale animation and resets
finished fragments during migration. If yes, remove the duplicate Foldogram
hook. If not, port the invariant to the new code path and keep the canary green.
Do not preserve old code mechanically if upstream changed the fragment model.
If upstream adds `resetTabletFlag`, `invalidateTabletMode`, or `checkLayout` to
`LaunchActivity.onConfigurationChanged`, remove those immediate calls and keep
the measured-width path as the sole tablet transition trigger.

```json
{"id":"nav-recovery","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java","contains":"private void forceResetAnimationState()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java","contains":"forceResetAnimationState();","min_count":5},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java","contains":"resetNavigationStateIfNeeded"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java","contains":"animationInProgressStartTime"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java","contains":"fragment.resetFragment();"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"chatFragment.resetFragment();"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"private void cancelStalePredictiveBack(String reason)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"cancelStalePredictiveBack(\"multiwindow\")"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"public void onMultiWindowModeChanged(boolean isInMultiWindowMode)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"private boolean hasOtherLaunchActivityInstanceInAppTasks()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"allowGlobalUiTeardown"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"fragmentOwnerTaskIds"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"destroyOwnedFragments"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"private final int instanceId = System.identityHashCode(this)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"onTopResumedActivityChanged"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java","contains":"public static boolean isUiCompletelyPaused()"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java","contains":"return mainInterfacePaused && externalInterfacePaused;"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java","contains":"if (chatsDict == null && ApplicationLoader.isUiCompletelyPaused())"}],"tests":["MergeRegressionCanaryTest"]}
```

## fold-tablet

Human explanation: Foldogram changes tablet/foldable detection and list layout
behavior so OPPO Find N style split/tablet mode does not lose the chat pane,
misclassify the window after cold start, or get trapped in ChatActivity pre-draw
cancel/redraw loops after resize. It also carries bottom-panel observer hooks
for foldable UI state.

Invariant: Tablet mode is driven by the measured window width, split layout can
migrate without losing the active chat or root tabs, cold-start tablet detection
uses current display size, and transient empty list layouts do not trigger false
pagination/filter loading.

Conflict policy: If upstream changes tablet detection, `DialogsActivity` list
loading, or bottom-panel notification wiring, compare the new behavior with the
invariant. Keep Foldogram's width-driven semantics unless upstream has a proven
equivalent. Do not drop `hideBottomPanelChanged` consumers just because upstream
moved observer wiring.

```json
{"id":"fold-tablet","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java","contains":"hideBottomPanelChanged"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java","contains":"postNotificationName(NotificationCenter.hideBottomPanelChanged)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java","contains":"transientEmptyLayout"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java","contains":"hideBottomPanelChanged"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/MainTabsActivity.java","contains":"hideBottomPanelChanged"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java","contains":"public static boolean isTabletForce()"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java","contains":"widthDp >= 600 && heightDp >= 320"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"private int measuredWindowWidth"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"private void updateDisplaySizeFromRootMeasure(int width, int height)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"private void onWindowWidthChanged()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"private boolean shouldFreezeTabletModeChange(boolean wasTablet, boolean nextTablet)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"private void scheduleWindowWidthChanged(long delay)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"checkTabletLayoutInvariant(\"windowWidthChanged\")"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"measuredWindowWidth = 0;"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"private void invalidateTabletMode()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"mainTabsActivity.prepareDialogsActivity(null)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java","contains":"checkTabletLayoutInvariant"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java","contains":"tablet/window transitions can trap the whole activity in cancelAndRedraw"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java","contains":"fixLayoutInternal();"}],"tests":["MergeRegressionCanaryTest"]}
```

## cutout

Human explanation: Foldogram carries a display-cutout workaround for landscape
rotation where a side cutout could expose an unwanted visual stripe. The current
implementation tracks cutout presence in `DrawerLayoutContainer`, draws black
side coverage for cutout insets, and keeps v31 themes in `shortEdges` cutout
mode.

Invariant: Display-cutout side insets remain explicitly handled in the drawer
container and v31 styles keep the cutout mode expected by the current workaround.
If upstream changes cutout handling, verify the rotation/cutout visual invariant
instead of preserving these exact lines blindly.

Conflict policy: If upstream removes the manual cutout drawing or changes
`windowLayoutInDisplayCutoutMode`, test the affected orientation/cutout scenario.
Keep either the current workaround or an upstream-equivalent fix. Never commit
hardcoded device-specific dimensions.

```json
{"id":"cutout","criticality":"med","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/DrawerLayoutContainer.java","contains":"DisplayCutoutCompat"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/DrawerLayoutContainer.java","contains":"private boolean hasCutout"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/DrawerLayoutContainer.java","contains":"insets.getDisplayCutout()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/DrawerLayoutContainer.java","contains":"getBoundingRects().isEmpty()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/DrawerLayoutContainer.java","contains":"if (hasCutout)"},{"path":"TMessagesProj/src/main/res/values-v31/styles.xml","contains":"android:windowLayoutInDisplayCutoutMode\">shortEdges","min_count":2}],"tests":["MergeRegressionCanaryTest"]}
```

## gesture-edge-to-edge

Human explanation: Foldogram keeps screen and scrolling backgrounds visible
behind the transparent gesture-navigation handle while preserving the existing
safe position of controls. Opaque app-side protection is reserved for tappable
system navigation such as three-button navigation and taskbars; IME insets are
handled independently and real window insets continue to reach child screens.

Invariant: `DrawerLayoutContainer` must never paint a navigation or keyboard
scrim over child content in transparent gesture mode. Navigation containers do
not shorten legacy content for a gesture-only inset, `UniversalFragment` lists
use bottom padding with unclipped scrolling, main-tab pages retain the original
insets, and attached sheet windows reach the bottom edge. Three-button
navigation and taskbars retain opaque protection based only on system-provided
insets. No device-specific dimensions may be used.

Conflict policy: If upstream changes edge-to-edge or inset dispatch, preserve
the distinction between `navigationBars`, `tappableElement`, and `ime`. Do not
replace it with a navigation-bar height heuristic. Keep the rendering test: a
contrasting child pixel at the bottom must survive the root draw in gesture
mode, while tappable navigation must still receive protection.

```json
{"id":"gesture-edge-to-edge","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/ui/Components/inset/EdgeToEdgeInsets.java","contains":"WindowInsetsCompat.Type.tappableElement()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Components/inset/EdgeToEdgeInsets.java","contains":"public static int getLegacyBottomInset"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/DrawerLayoutContainer.java","contains":"EdgeToEdgeInsets.getNavigationBarProtection(insets)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/DrawerLayoutContainer.java","contains":"navigationBarProtection.bottom"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java","contains":"EdgeToEdgeInsets.getLegacyBottomInset(insets)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BottomSheet.java","contains":"insets.getTappableElementInsets().bottom"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BottomSheet.java","contains":"drawNavigationBar(canvas, (drawDoubleNavigationBar ? 0.7f * navigationBarAlpha : 1f), true)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Components/UniversalFragment.java","contains":"listView.setClipToPadding(false)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/MainTabsActivity.java","contains":"return super.onApplyWindowInsets(v, insets);"},{"path":"TMessagesProj/src/test/java/org/telegram/ui/ActionBar/DrawerLayoutContainerEdgeToEdgeTest.java","contains":"gestureContentRemainsVisibleAfterRootDrawEvenWithImeInsets"}],"tests":["MergeRegressionCanaryTest","DrawerLayoutContainerEdgeToEdgeTest"]}
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
format-version purge remain intact. Released fork migration numbers are frozen:
the shipped preview migration at user_version 174 -> 175 must not be renumbered.
`LAST_DB_VERSION` may grow after upstream merges, but it must never fall below
the shipped Foldogram DB floor. Fork DDL must be idempotent: `CREATE` statements
use `IF NOT EXISTS`, and fork `ADD COLUMN` migrations use `executeNoException`.

Conflict policy: On any database conflict, stop and inspect `LAST_DB_VERSION`,
upstream migrations, and Foldogram preview migrations together. Never choose
`ours` or `theirs` for migration blocks. Do not renumber shipped Foldogram
migrations. If upstream increments the DB version, append upstream's new
migration above the shipped fork floor and prove the upgrade path for users
parked on every released `user_version`.

```json
{"id":"db-preview","criticality":"high","anchors":[{"path":"TMessagesProj/src/test/java/org/telegram/messenger/merge/MergeRegressionCanaryTest.java","contains":"SHIPPED_DB_FLOOR = 175"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalPreviewStorage.java","contains":"public static final String TABLE_NAME = \"external_previews_v1\""},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalPreviewStorage.java","contains":"CREATE TABLE IF NOT EXISTS external_previews_v1"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java","contains":"executeNoException(database, \"ALTER TABLE external_previews_v1 ADD COLUMN extra TEXT\")"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/external/PreviewRepository.java","contains":"EXTERNAL_PREVIEW_FORMAT_VERSION = 3"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java","contains":"public void putExternalPreview"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java","contains":"public void getExternalPreview"}],"tests":["MergeRegressionCanaryTest","PreviewMapperTest"]}
```

## browser-iv

Human explanation: Foldogram adjusts browser/opening behavior around Instant View
state so custom tabs and in-app browser choices do not conflict with active
Instant View screens.

Invariant: Browser URL opening can detect whether Instant View is open and use
that state when deciding between custom tabs, in-app browser, and normal external
opening.

Conflict policy: If upstream rewrites `Browser.openUrl`, keep an explicit
Instant View state predicate near the in-app browser decision or prove the new
upstream path has equivalent behavior. Do not remove `isInstantViewOpen` just
because it looks like a small helper.

```json
{"id":"browser-iv","criticality":"med","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/Browser.java","contains":"public static boolean isInstantViewOpen()"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/browser/Browser.java","contains":"isWebBrowserOpenInApp(uri.toString()) || isInstantViewOpen()"}],"tests":["MergeRegressionCanaryTest"]}
```

## identity

Human explanation: Foldogram is published as `com.rbnkv.foldogram`, not the
upstream Telegram package. App modules derive their application id from
`APP_PACKAGE`, local/device builds use the independent
`com.rbnkv.foldogram.beta` identity, and release metadata and Play URLs must stay
Foldogram-specific.

Invariant: Release variants use the Foldogram package identity, beta variants add
`.beta` on top of it, and release metadata points to the Foldogram Play listing.
Local development and device testing target beta, never the Play-installed
stable package. The canary intentionally counts separate `.beta` suffix anchors
in both application modules so an upstream Gradle rewrite cannot silently point
local installation back at stable.

Conflict policy: If upstream changes Gradle packaging or manifests, preserve
`APP_PACKAGE=com.rbnkv.foldogram` as the source of truth and preserve `.beta` on
local/device variants. Do not hardcode the upstream package into app modules or
Play release workflow, and stop before installing any local artifact that
resolves to the stable Foldogram package.

```json
{"id":"identity","criticality":"high","anchors":[{"path":"gradle.properties","contains":"APP_PACKAGE=com.rbnkv.foldogram"},{"path":"TMessagesProj_App/build.gradle","contains":"defaultConfig.applicationId = APP_PACKAGE"},{"path":"TMessagesProj_App/build.gradle","contains":"applicationIdSuffix \".beta\""},{"path":"TMessagesProj_AppHockeyApp/build.gradle","contains":"defaultConfig.applicationId = APP_PACKAGE"},{"path":"TMessagesProj_AppHockeyApp/build.gradle","contains":"applicationIdSuffix \".beta\""},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java","contains":"https://play.google.com/store/apps/details?id=com.rbnkv.foldogram"},{"path":"TMessagesProj/src/main/AndroidManifest.xml","contains":"${applicationId}.provider"}],"tests":["MergeRegressionCanaryTest"]}
```

## share-shortcuts

Human explanation: Foldogram ranks Android direct-share shortcuts with local
share/send/open signals instead of relying only on upstream remote hints. The
ranker stores local scores, blends them with remote hints, and feeds both the
share sheet and dynamic shortcut publication.

Invariant: Share target ranking remains backed by `ShareTargetRanker`, local
share/send/open events update the ranker, `MediaDataController.getShareHints`
uses ranked dialogs, and Android dynamic shortcuts are reported/updated with the
ranked results.

Conflict policy: If upstream rewrites share hints or shortcut publication,
preserve the local ranking invariant or prove upstream now provides an equivalent
ranker. Do not remove `ShareTargetRanker` as unused if `MediaDataController`
still owns share hints and dynamic shortcuts.

```json
{"id":"share-shortcuts","criticality":"med","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/messenger/ShareTargetRanker.java","contains":"class ShareTargetRanker extends BaseController"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/ShareTargetRanker.java","contains":"private static final String TABLE_NAME = \"share_hints_v1\""},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/ShareTargetRanker.java","contains":"public ArrayList<RankedDialog> getTopDialogs"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/ShareTargetRanker.java","contains":"public boolean recordSuccessfulSend(long dialogId)"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java","contains":"shareTargetRanker = new ShareTargetRanker(num)"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java","contains":"private final ShareTargetRanker shareTargetRanker"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java","contains":"shareTargetRanker.getTopDialogs"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java","contains":"shareTargetRanker.recordSuccessfulSend(dialogId)"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java","contains":"ShortcutManagerCompat.reportShortcutUsed"}],"tests":["MergeRegressionCanaryTest"]}
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

Invariant: `CLAUDE.local.md` remains local and ignored, tracked `CLAUDE.md` is
only a symlink to `AGENTS.md`, generated app `google-services.json` files remain
ignored, app modules generate Google services config from env/local inputs, and
release workflows consume secrets through GitHub secrets without printing their
contents.

Conflict policy: If upstream adds or rewrites Firebase/Google service config,
stop and classify the file before committing. Do not add real generated app
configs to git. If a check must allow a legacy tracked upstream file, document the
allowlist in the check and do not broaden it without review.

```json
{"id":"secrets-policy","criticality":"high","anchors":[{"path":".gitignore","contains":"CLAUDE.local.md"},{"path":".gitignore","contains":"google-services.json"},{"path":".gitignore","contains":"**/google-services.json"},{"path":"TMessagesProj_AppHockeyApp/.gitignore","contains":"google-services.json"},{"path":"TMessagesProj_App/build.gradle","contains":"GOOGLE_SERVICES_JSON"},{"path":"TMessagesProj_AppHockeyApp/build.gradle","contains":"GOOGLE_SERVICES_JSON_HOCKEYAPP"},{"path":".github/workflows/internal-release.yml","contains":"secrets.GOOGLE_SERVICES_JSON"}],"tests":["MergeRegressionCanaryTest"]}
```

## duress-passcode

Human explanation: Foldogram supports an emergency passcode for duress unlocks.
Entering the emergency code opens the app while hiding a configured set of chats
from lists, search, pickers, notifications, badges, counters, and direct-share
targets. The emergency settings entry is visible only in normal mode.

Invariant: The emergency code hides the selected chats across list, search,
pickers, notifications, counters, and shortcuts; the mode is sticky and
persisted; only the retained owner passcode clears it and heals the current gate;
disabling the passcode in emergency mode removes only the current lock while
preserving hidden chats and owner recovery; biometric unlock is disabled when an
emergency code exists; the hidden set is scoped to the configured account.

Conflict policy: This feature never goes upstream. Always re-apply the thin
call-outs onto the new upstream structure and keep all state-machine logic in
`EmergencyPasscode` plus the fork-owned settings UI. Never delete a call-out as
unused while `EmergencyPasscode` exists, and never refactor
`SharedConfig.checkPasscode` to implement this feature.

```json
{"id":"duress-passcode","criticality":"high","anchors":[{"path":"TMessagesProj/src/main/java/org/telegram/messenger/duress/EmergencyPasscode.java","contains":"public final class EmergencyPasscode"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/duress/EmergencyPasscode.java","contains":"public static int checkType"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/duress/EmergencyPasscode.java","contains":"public static void applyUnlock"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/duress/EmergencyPasscode.java","contains":"public static boolean isHidden"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/duress/EmergencyPasscode.java","contains":"public static boolean setEmergencyCode"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/duress/EmergencyPasscode.java","contains":"ownerPasscodeHash"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java","contains":"EmergencyPasscode.load(preferences)"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java","contains":"EmergencyPasscode.save(editor)"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java","contains":"EmergencyPasscode.clear()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Components/PasscodeView.java","contains":"EmergencyPasscode.onPasscodeAccepted(UserConfig.selectedAccount, passcodeResult)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Components/PasscodeView.java","contains":"!EmergencyPasscode.hasEmergency()","min_count":2},{"path":"TMessagesProj/src/main/java/org/telegram/ui/PasscodeActivity.java","contains":"EmergencyPasscode.onPasscodeAccepted(currentAccount, passcodeResult)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/PasscodeActivity.java","contains":"EmergencyPasscode.wipeAll()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/PasscodeActivity.java","contains":"private int emergencyRow"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/PasscodeActivity.java","contains":"setSettingEmergencyPasscode(true)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/PasscodeActivity.java","contains":"EmergencyPasscode.snapshotOwnerIfNeeded(currentAccount)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/PasscodeActivity.java","contains":"new EmergencyPasscodeActivity()"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsAdapter.java","contains":"EmergencyPasscode.isHidden(currentAccount, item.dialog.id)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java","contains":"EmergencyPasscode.isHidden(currentAccount, dialog.id)"},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/NotificationsController.java","contains":"EmergencyPasscode.isHidden","min_count":3},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java","contains":"EmergencyPasscode.isHidden(currentAccount, d.id)"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java","contains":"EmergencyPasscode.adjustTabCounter","min_count":2},{"path":"TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java","contains":"Search/list clicks must not open emergency-hidden chats"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Adapters/SearchAdapterHelper.java","contains":"EmergencyPasscode.isHidden","min_count":4},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsSearchAdapter.java","contains":"EmergencyPasscode.isHidden","min_count":5},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java","contains":"Hidden chats never appear in top-peer search hints"},{"path":"TMessagesProj/src/main/java/org/telegram/ui/Components/ShareAlert.java","contains":"EmergencyPasscode.isHidden","min_count":2},{"path":"TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java","contains":"EmergencyPasscode.isHidden","min_count":3},{"path":"TMessagesProj/src/main/java/org/telegram/ui/EmergencyPasscodeActivity.java","contains":"public class EmergencyPasscodeActivity"},{"path":"TMessagesProj/src/main/res/values/strings.xml","contains":"EmergencyPasscodeInfo"}],"tests":["MergeRegressionCanaryTest","EmergencyPasscodeTest"]}
```
