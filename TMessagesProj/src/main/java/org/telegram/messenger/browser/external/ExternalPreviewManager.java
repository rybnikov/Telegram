package org.telegram.messenger.browser.external;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;

public final class ExternalPreviewManager {

    private static final String TAG = "ExternalPreview";
    private static final int MAX_CACHE_SIZE = 64;
    private static final int MAX_CONCURRENT_RESOLVES = 3;

    private static final Object lock = new Object();
    private static final LinkedHashMap<String, CachedPreview> cache = new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 1.0f, true);
    private static final HashMap<String, ArrayList<PendingMessage>> pendingMessages = new HashMap<>();
    private static final HashSet<String> persistentLookups = new HashSet<>();
    private static final HashMap<String, ResolveRequest> queuedRequests = new HashMap<>();
    private static final HashMap<String, ResolveRequest> runningRequests = new HashMap<>();
    private static long nextSequence;
    private static int activeResolves;
    private static long resetGeneration;

    private ExternalPreviewManager() {
    }

    public static void clearDebugState() {
        synchronized (lock) {
            resetGeneration++;
            cache.clear();
            pendingMessages.clear();
            persistentLookups.clear();
            queuedRequests.clear();
            runningRequests.clear();
            activeResolves = 0;
        }
        ExternalMediaPreviewStore.clearDebugState();
    }

    public static void preloadPreviews(ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            requestPreviewIfNeeded(messages.get(i), ResolvePriority.BATCH_LOW);
        }
    }

    public static void reprioritizePreviews(ArrayList<MessageObject> visibleMessages, ArrayList<MessageObject> lookaheadMessages) {
        if (visibleMessages != null) {
            for (int i = 0; i < visibleMessages.size(); i++) {
                requestPreviewIfNeeded(visibleMessages.get(i), ResolvePriority.VISIBLE_NOW);
            }
        }
        if (lookaheadMessages != null) {
            for (int i = 0; i < lookaheadMessages.size(); i++) {
                requestPreviewIfNeeded(lookaheadMessages.get(i), ResolvePriority.LOOKAHEAD);
            }
        }
    }

    public static boolean applyCachedPreviewIfAvailable(MessageObject messageObject) {
        if (!org.telegram.messenger.SharedConfig.extendedPreviews) {
            return false;
        }
        if (messageObject == null || messageObject.messageOwner == null || messageObject.isRestrictedMessage) {
            return false;
        }
        ParsedLink link = findPlatformLink(messageObject.messageOwner);
        if (link == null) {
            return false;
        }
        ExternalMediaResolver resolver = ExternalLinkRouter.findResolver(link.getCanonicalUri());
        TLRPC.MessageMedia messageMedia = MessageObject.getMedia(messageObject.messageOwner);
        if ((!resolver.overridesServerPreview() && hasServerWebPage(messageMedia)) || shouldSkipExistingMedia(messageMedia, link, resolver)) {
            return false;
        }
        CachedPreview cachedPreview;
        synchronized (lock) {
            cachedPreview = cache.get(link.canonicalUrl);
        }
        if (cachedPreview != null && cachedPreview.webPage != null) {
            applyPreviewToMessage(messageObject.messageOwner, cachedPreview.webPage);
            return true;
        }
        requestPreviewIfNeeded(messageObject, ResolvePriority.VISIBLE_NOW);
        return false;
    }

    public static void requestPreviewIfNeeded(MessageObject messageObject) {
        requestPreviewIfNeeded(messageObject, ResolvePriority.BATCH_LOW);
    }

    private static void requestPreviewIfNeeded(MessageObject messageObject, ResolvePriority priority) {
        if (!org.telegram.messenger.SharedConfig.extendedPreviews) {
            log("skip disabled", null, null, null, messageObject);
            return;
        }
        if (messageObject == null || messageObject.messageOwner == null || messageObject.isRestrictedMessage) {
            log("skip restricted", null, null, null, messageObject);
            return;
        }
        ParsedLink link = findPlatformLink(messageObject.messageOwner);
        if (link == null) {
            log("skip no link", null, null, null, messageObject);
            return;
        }
        ExternalMediaResolver resolver = ExternalLinkRouter.findResolver(link.getCanonicalUri());
        if (resolver == null) {
            log("skip no resolver", link, null, null, messageObject);
            return;
        }
        TLRPC.MessageMedia messageMedia = MessageObject.getMedia(messageObject.messageOwner);
        if (!resolver.overridesServerPreview() && hasServerWebPage(messageMedia)) {
            log("skip server webpage", link, resolver, null, messageObject);
            return;
        }
        if (shouldSkipExistingMedia(messageMedia, link, resolver)) {
            log("skip existing media", link, resolver, null, messageObject);
            return;
        }
        CachedPreview cachedPreview;
        boolean shouldLookupPersisted = false;
        boolean shouldDispatch = false;
        synchronized (lock) {
            cachedPreview = cache.get(link.canonicalUrl);
            if (cachedPreview == null) {
                ArrayList<PendingMessage> pending = pendingMessages.get(link.canonicalUrl);
                if (pending == null) {
                    pending = new ArrayList<>();
                    pendingMessages.put(link.canonicalUrl, pending);
                    if (!persistentLookups.contains(link.canonicalUrl)) {
                        persistentLookups.add(link.canonicalUrl);
                        shouldLookupPersisted = true;
                        log("lookup persisted", link, resolver, null, messageObject);
                    } else {
                        log("pending join persisted lookup", link, resolver, null, messageObject);
                    }
                } else {
                    if (persistentLookups.contains(link.canonicalUrl)) {
                        log("pending join persisted lookup", link, resolver, null, messageObject);
                    } else if (queuedRequests.containsKey(link.canonicalUrl)) {
                        enqueueOrUpgradeLocked(link, resolver, priority, messageObject, "pending join queued");
                    } else if (runningRequests.containsKey(link.canonicalUrl)) {
                        ResolveRequest running = runningRequests.get(link.canonicalUrl);
                        if (running != null && priority.ordinal() > running.priority.ordinal()) {
                            running.priority = priority;
                            log("priority note running=" + priority.name(), link, resolver, null, messageObject);
                        } else {
                            log("pending join running", link, resolver, null, messageObject);
                        }
                    } else {
                        enqueueOrUpgradeLocked(link, resolver, priority, messageObject, "pending join late");
                        shouldDispatch = true;
                    }
                }
                pending.add(new PendingMessage(messageObject.currentAccount, messageObject.messageOwner));
            }
        }

        if (cachedPreview != null) {
            if (hasAppliedPreview(messageObject.messageOwner, cachedPreview.webPage)) {
                log("cache hit already applied", link, resolver, null, messageObject);
            } else {
                log("cache hit", link, resolver, null, messageObject);
                postResolvedPreview(messageObject.currentAccount, messageObject.messageOwner, cachedPreview.webPage);
            }
            return;
        }
        if (shouldLookupPersisted) {
            lookupPersistedPreview(messageObject.currentAccount, link, resolver);
            return;
        }
        if (shouldDispatch) {
            dispatchQueuedResolves();
        }
    }

    private static void startResolve(ResolveRequest request) {
        log("start resolve priority=" + request.priority.name(), request.link, request.resolver, null, null);
        new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            CachedPreview cached = null;
            Throwable error = null;
            try {
                ResolvedMedia media = request.resolver.resolve(request.link);
                if (media != null) {
                    TLRPC.WebPage webpage = buildWebPage(request.link, request.resolver, media);
                    if (webpage != null) {
                        cached = new CachedPreview(webpage, media);
                    }
                }
            } catch (Throwable e) {
                error = e;
            }

            final CachedPreview finalCached = cached;
            final Throwable finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                ArrayList<PendingMessage> pending;
                synchronized (lock) {
                    runningRequests.remove(request.link.canonicalUrl);
                    activeResolves = Math.max(0, activeResolves - 1);
                    pending = pendingMessages.remove(request.link.canonicalUrl);
                    boolean staleGeneration = request.generation != resetGeneration;
                    if (staleGeneration) {
                        pending = null;
                    }
                    if (finalCached != null && !staleGeneration) {
                        cache.put(request.link.canonicalUrl, finalCached);
                        trimCache();
                    }
                }
                long duration = System.currentTimeMillis() - startedAt;
                if (pending == null || pending.isEmpty()) {
                    log("publish skipped pending empty duration=" + duration + "ms", request.link, request.resolver, null, null);
                    dispatchQueuedResolves();
                    return;
                }
                if (finalCached == null) {
                    if (finalError != null) {
                        log("fallback no preview error=" + finalError.getClass().getSimpleName() + " duration=" + duration + "ms", request.link, request.resolver, null, null);
                    } else {
                        log("fallback no preview duration=" + duration + "ms", request.link, request.resolver, null, null);
                    }
                    dispatchQueuedResolves();
                    return;
                }
                HashMap<Integer, ArrayList<TLRPC.Message>> messagesByAccount = new HashMap<>();
                for (int i = 0; i < pending.size(); i++) {
                    PendingMessage pendingMessage = pending.get(i);
                    applyPreviewToMessage(pendingMessage.message, finalCached.webPage);
                    ArrayList<TLRPC.Message> messages = messagesByAccount.get(pendingMessage.account);
                    if (messages == null) {
                        messages = new ArrayList<>();
                        messagesByAccount.put(pendingMessage.account, messages);
                    }
                    messages.add(createPreviewMessage(pendingMessage.message, finalCached.webPage));
                }
                for (Map.Entry<Integer, ArrayList<TLRPC.Message>> entry : messagesByAccount.entrySet()) {
                    persistResolvedPreview(entry.getKey(), request, finalCached);
                    NotificationCenter.getInstance(entry.getKey()).postNotificationName(NotificationCenter.didReceivedWebpages, entry.getValue());
                }
                log("loaded preview duration=" + duration + "ms publishAccounts=" + messagesByAccount.size() + " pendingCount=" + pending.size(), request.link, request.resolver, null, null);
                dispatchQueuedResolves();
            });
        }, "ExtPreview-" + request.link.platformName).start();
    }

    public static boolean openCachedPreview(Context context, Uri uri) {
        ExternalMediaResolver resolver = ExternalLinkRouter.findResolver(uri);
        if (resolver == null || !resolver.overridesServerPreview()) {
            return false;
        }
        ParsedLink link = resolver.parseLink(uri);
        if (link == null) {
            return false;
        }
        CachedPreview cachedPreview;
        synchronized (lock) {
            cachedPreview = cache.get(link.canonicalUrl);
        }
        if (cachedPreview == null || cachedPreview.media == null) {
            log("openCachedPreview miss", link, resolver, null, null);
            return false;
        }
        log("openCachedPreview hit", link, resolver, null, null);
        return openCachedMedia(context, resolver, cachedPreview, link.canonicalUrl);
    }

    public static boolean openCachedPreview(Context context, TLRPC.Message message) {
        ParsedLink link = findPlatformLink(message);
        if (link == null) {
            return false;
        }
        ExternalMediaResolver resolver = ExternalLinkRouter.findResolver(link.getCanonicalUri());
        if (resolver == null || !resolver.overridesServerPreview()) {
            return false;
        }
        CachedPreview cachedPreview;
        synchronized (lock) {
            cachedPreview = cache.get(link.canonicalUrl);
        }
        if (cachedPreview == null || cachedPreview.media == null) {
            log("openCachedPreview(message) miss", link, resolver, null, null);
            return false;
        }
        log("openCachedPreview(message) hit", link, resolver, null, null);
        return openCachedMedia(context, resolver, cachedPreview, link.canonicalUrl);
    }

    private static boolean openCachedMedia(Context context, ExternalMediaResolver resolver, CachedPreview cachedPreview, String canonicalUrl) {
        if (cachedPreview.media instanceof ResolvedMedia.Preview) {
            Browser.openUrl(context, Uri.parse(cachedPreview.webPage.url), true, true, false, null, null, false, true, false);
            return true;
        }
        if (cachedPreview.media instanceof ResolvedMedia.Video) {
            ResolvedMedia.Video video = (ResolvedMedia.Video) cachedPreview.media;
            if (!resolver.supportsDirectVideoStreaming()) {
                if ("TikTok".equals(resolver.platformName())) {
                    resolveAndStreamTikTok(context, canonicalUrl, video);
                } else {
                    openEmbedSheet(context, resolver.platformName(), video, canonicalUrl);
                }
                return true;
            }
            ExternalMediaPreviewStore.VideoPreview preview = ExternalMediaPreviewStore.getVideoPreview(cachedPreview.webPage.id);
            if (preview != null) {
                return ExternalMediaOpenHelper.openVideoPreview(context, preview);
            }
        }
        // Preview-only resolvers (no button, e.g. Maps) — don't intercept click
        if (ExternalLinkRouter.getInstantButtonText(resolver.platformName(), cachedPreview.webPage) == null) {
            return false;
        }
        return ExternalMediaOpenHelper.openResolved(context, Uri.parse(canonicalUrl), cachedPreview.media);
    }

    public static boolean tryOpenMessagePreview(Context context, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || !(messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaWebPage)) {
            return false;
        }
        TLRPC.WebPage webPage = messageObject.messageOwner.media.webpage;
        ExternalMediaPreviewStore.VideoPreview preview = webPage == null ? null : ExternalMediaPreviewStore.getVideoPreview(webPage.id);
        if (preview == null) {
            return false;
        }
        ExternalMediaResolver resolver = !TextUtils.isEmpty(preview.sourceUrl) ? ExternalLinkRouter.findResolver(Uri.parse(preview.sourceUrl)) : null;
        if (resolver != null && !resolver.supportsDirectVideoStreaming()) {
            return false;
        }
        return ExternalMediaOpenHelper.openVideoPreview(context, preview);
    }

    static void resolveAndStreamTikTok(Context context, String canonicalUrl, ResolvedMedia.Video video) {
        new Thread(() -> {
            String videoUrl = org.telegram.messenger.browser.tiktok.TikTokMediaResolver.resolveVideoForPlayback(canonicalUrl);
            AndroidUtilities.runOnUIThread(() -> {
                if (videoUrl != null) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d(TAG + ": tiktok playback success url=" + canonicalUrl + " stream=" + ExternalHtmlUtils.trimForLog(videoUrl));
                    }
                    ResolvedMedia.Video streamVideo = new ResolvedMedia.Video(
                        videoUrl, video.posterUrl, video.title, video.description,
                        video.width, video.height
                    );
                    ExternalMediaOpenHelper.openResolved(context, Uri.parse(canonicalUrl), streamVideo);
                } else {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d(TAG + ": tiktok playback fallback browser url=" + canonicalUrl);
                    }
                    Browser.openUrl(context, Uri.parse(canonicalUrl), true, true, false, null, null, false, true, false);
                }
            });
        }, "ExtPreview-playback").start();
    }

    private static void openEmbedSheet(Context context, String siteName, ResolvedMedia.Video video, String sourceUrl) {
        org.telegram.ui.ActionBar.BaseFragment fragment = org.telegram.ui.LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            Browser.openUrl(context, Uri.parse(video.videoUrl), true, true, false, null, null, false, true, false);
            return;
        }
        org.telegram.ui.Components.EmbedBottomSheet.show(
            fragment,
            null,
            null,
            siteName,
            video.title,
            sourceUrl,
            video.videoUrl,
            video.width,
            video.height,
            false
        );
    }

    private static boolean hasServerWebPage(TLRPC.MessageMedia messageMedia) {
        return messageMedia instanceof TLRPC.TL_messageMediaWebPage
            && messageMedia.webpage instanceof TLRPC.TL_webPage;
    }

    private static boolean shouldSkipExistingMedia(TLRPC.MessageMedia messageMedia, ParsedLink link, ExternalMediaResolver resolver) {
        if (messageMedia == null || messageMedia instanceof TLRPC.TL_messageMediaEmpty) {
            return false;
        }
        if (resolver != null && resolver.overridesServerPreview()) {
            return !(messageMedia instanceof TLRPC.TL_messageMediaWebPage);
        }
        if (!(messageMedia instanceof TLRPC.TL_messageMediaWebPage)) {
            return true;
        }
        if (!(messageMedia.webpage instanceof TLRPC.TL_webPage)) {
            return false;
        }
        TLRPC.WebPage webPage = messageMedia.webpage;
        long stableId = computeStableId(link.canonicalUrl);
        if (resolver != null && resolver.overridesServerPreview() && webPage.id == stableId && shouldRefreshExternalVideoPreview(webPage, link)) {
            return false;
        }
        if (resolver != null && resolver.overridesServerPreview() && webPage.id == stableId && !hasRenderableExternalPreview(webPage)) {
            return false;
        }
        return webPage.id == stableId;
    }

    private static boolean hasRenderableExternalPreview(TLRPC.WebPage webPage) {
        if (webPage == null) {
            return false;
        }
        return webPage.photo != null
            || webPage.document != null
            || !TextUtils.isEmpty(webPage.embed_url);
    }

    private static boolean shouldRefreshExternalVideoPreview(TLRPC.WebPage webPage, ParsedLink link) {
        if (webPage == null || webPage.document != null || link == null || TextUtils.isEmpty(link.canonicalUrl)) {
            return false;
        }
        if (!"Instagram".equals(link.platformName)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(link.canonicalUrl);
            ArrayList<String> segments = new ArrayList<>(uri.getPathSegments());
            return !segments.isEmpty() && ("reel".equalsIgnoreCase(segments.get(0)) || "reels".equalsIgnoreCase(segments.get(0)));
        } catch (Exception ignore) {
            return false;
        }
    }

    private static void trimCache() {
        while (cache.size() > MAX_CACHE_SIZE) {
            String firstKey = cache.keySet().iterator().next();
            cache.remove(firstKey);
        }
    }

    private static void postResolvedPreview(int account, TLRPC.Message message, TLRPC.WebPage webpage) {
        applyPreviewToMessage(message, webpage);
        ArrayList<TLRPC.Message> messages = new ArrayList<>(1);
        messages.add(createPreviewMessage(message, webpage));
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didReceivedWebpages, messages);
    }

    private static void lookupPersistedPreview(int account, ParsedLink link, ExternalMediaResolver resolver) {
        long generationSnapshot;
        synchronized (lock) {
            generationSnapshot = resetGeneration;
        }
        AccountInstance.getInstance(account).getMessagesStorage().getExternalPreview(link.canonicalUrl, storedPreview -> {
            ArrayList<PendingMessage> pending = null;
            boolean shouldDispatch = false;
            CachedPreview hydrated = null;
            synchronized (lock) {
                persistentLookups.remove(link.canonicalUrl);
                if (generationSnapshot != resetGeneration) {
                    return;
                }
                if (storedPreview != null) {
                    hydrated = hydrateCachedPreview(storedPreview, resolver);
                    if (hydrated != null) {
                        cache.put(link.canonicalUrl, hydrated);
                        trimCache();
                        pending = pendingMessages.remove(link.canonicalUrl);
                    }
                }
                if (hydrated == null && pendingMessages.containsKey(link.canonicalUrl) && !queuedRequests.containsKey(link.canonicalUrl) && !runningRequests.containsKey(link.canonicalUrl)) {
                    enqueueOrUpgradeLocked(link, resolver, ResolvePriority.VISIBLE_NOW, null, "persisted miss -> enqueued");
                    shouldDispatch = true;
                }
            }
            if (hydrated != null && pending != null && !pending.isEmpty()) {
                HashMap<Integer, ArrayList<TLRPC.Message>> messagesByAccount = new HashMap<>();
                for (int i = 0; i < pending.size(); i++) {
                    PendingMessage pendingMessage = pending.get(i);
                    applyPreviewToMessage(pendingMessage.message, hydrated.webPage);
                    ArrayList<TLRPC.Message> messages = messagesByAccount.get(pendingMessage.account);
                    if (messages == null) {
                        messages = new ArrayList<>();
                        messagesByAccount.put(pendingMessage.account, messages);
                    }
                    messages.add(createPreviewMessage(pendingMessage.message, hydrated.webPage));
                }
                for (Map.Entry<Integer, ArrayList<TLRPC.Message>> entry : messagesByAccount.entrySet()) {
                    NotificationCenter.getInstance(entry.getKey()).postNotificationName(NotificationCenter.didReceivedWebpages, entry.getValue());
                }
                log("persisted hit publishAccounts=" + messagesByAccount.size() + " pendingCount=" + pending.size(), link, resolver, null, null);
            } else if (storedPreview == null) {
                log("persisted miss", link, resolver, null, null);
            }
            if (shouldDispatch) {
                dispatchQueuedResolves();
            }
        });
    }

    private static void persistResolvedPreview(int account, ResolveRequest request, CachedPreview cachedPreview) {
        if (cachedPreview == null || cachedPreview.webPage == null) {
            return;
        }
        log("persist write account=" + account + " webpageId=" + cachedPreview.webPage.id + " kind=" + describePreviewKind(cachedPreview.media), request.link, request.resolver, null, null);
        AccountInstance.getInstance(account).getMessagesStorage().putExternalPreview(createExternalPreviewRecord(request, cachedPreview));
    }

    private static MessagesStorage.ExternalPreviewRecord createExternalPreviewRecord(ResolveRequest request, CachedPreview cachedPreview) {
        String mediaUrl = null;
        String posterUrl = null;
        int width = 0;
        int height = 0;
        int previewKind = MessagesStorage.EXTERNAL_PREVIEW_KIND_PREVIEW;
        if (cachedPreview.media instanceof ResolvedMedia.Video) {
            ResolvedMedia.Video video = (ResolvedMedia.Video) cachedPreview.media;
            previewKind = MessagesStorage.EXTERNAL_PREVIEW_KIND_VIDEO;
            mediaUrl = video.videoUrl;
            posterUrl = video.posterUrl;
            width = video.width;
            height = video.height;
        } else if (cachedPreview.media instanceof ResolvedMedia.Image) {
            ResolvedMedia.Image image = (ResolvedMedia.Image) cachedPreview.media;
            previewKind = MessagesStorage.EXTERNAL_PREVIEW_KIND_IMAGE;
            mediaUrl = image.imageUrl;
            width = image.width;
            height = image.height;
        } else if (cachedPreview.media instanceof ResolvedMedia.Preview) {
            ResolvedMedia.Preview preview = (ResolvedMedia.Preview) cachedPreview.media;
            previewKind = MessagesStorage.EXTERNAL_PREVIEW_KIND_PREVIEW;
            mediaUrl = preview.sourceUrl;
            posterUrl = preview.posterUrl;
            width = preview.width;
            height = preview.height;
        }
        return new MessagesStorage.ExternalPreviewRecord(
            cachedPreview.webPage.id,
            request.link.canonicalUrl,
            request.link.platformName,
            cachedPreview.webPage,
            previewKind,
            mediaUrl,
            posterUrl,
            width,
            height,
            cachedPreview.media != null ? cachedPreview.media.title : cachedPreview.webPage.title,
            cachedPreview.media != null ? cachedPreview.media.description : cachedPreview.webPage.description
        );
    }

    private static CachedPreview hydrateCachedPreview(MessagesStorage.ExternalPreviewRecord preview, ExternalMediaResolver resolver) {
        if (preview == null) {
            return null;
        }
        TLRPC.WebPage webPage = ensureRenderableStoredWebPage(preview);
        if (webPage == null) {
            return null;
        }
        ResolvedMedia media;
        if (preview.previewKind == MessagesStorage.EXTERNAL_PREVIEW_KIND_VIDEO) {
            ResolvedMedia.Video video = new ResolvedMedia.Video(preview.mediaUrl, preview.posterUrl, preview.title, preview.description, preview.width, preview.height);
            media = video;
            ExternalMediaPreviewStore.putVideo(preview.webPageId, preview.platform, preview.canonicalUrl, preview.mediaUrl, preview.posterUrl, preview.width, preview.height, preview.title, preview.description);
        } else if (preview.previewKind == MessagesStorage.EXTERNAL_PREVIEW_KIND_IMAGE) {
            media = new ResolvedMedia.Image(preview.mediaUrl, preview.title, preview.description, preview.width, preview.height);
        } else {
            media = new ResolvedMedia.Preview(preview.mediaUrl, preview.posterUrl, preview.title, preview.description, preview.width, preview.height);
        }
        return new CachedPreview(webPage, media);
    }

    private static String describePreviewKind(ResolvedMedia media) {
        if (media instanceof ResolvedMedia.Video) {
            return "video";
        } else if (media instanceof ResolvedMedia.Image) {
            return "image";
        } else if (media instanceof ResolvedMedia.Preview) {
            return "preview";
        }
        return media == null ? "none" : media.getClass().getSimpleName();
    }

    private static void applyPreviewToMessage(TLRPC.Message message, TLRPC.WebPage webpage) {
        if (message == null) {
            return;
        }
        message.media = new TLRPC.TL_messageMediaWebPage();
        message.media.webpage = webpage;
    }

    private static boolean hasAppliedPreview(TLRPC.Message message, TLRPC.WebPage webpage) {
        if (message == null || webpage == null) {
            return false;
        }
        if (!(message.media instanceof TLRPC.TL_messageMediaWebPage)) {
            return false;
        }
        TLRPC.WebPage current = message.media.webpage;
        if (!(current instanceof TLRPC.TL_webPage)) {
            return false;
        }
        if (current.id != 0 && webpage.id != 0) {
            return current.id == webpage.id;
        }
        return !TextUtils.isEmpty(current.url) && !TextUtils.isEmpty(webpage.url) && TextUtils.equals(current.url, webpage.url);
    }

    private static TLRPC.Message createPreviewMessage(TLRPC.Message source, TLRPC.WebPage webpage) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = source.id;
        message.flags = source.flags;
        message.dialog_id = source.dialog_id != 0 ? source.dialog_id : MessageObject.getDialogId(source);
        message.peer_id = source.peer_id;
        message.from_id = source.from_id;
        message.saved_peer_id = source.saved_peer_id;
        applyPreviewToMessage(message, webpage);
        return message;
    }

    private static TLRPC.WebPage buildWebPage(ParsedLink link, ExternalMediaResolver resolver, ResolvedMedia media) {
        ResolvedMedia.Single previewMedia = pickPreviewMedia(media);
        if (previewMedia == null) {
            return null;
        }

        TLRPC.TL_webPage webpage = new TLRPC.TL_webPage();
        webpage.id = computeStableId(link.canonicalUrl);
        webpage.url = link.canonicalUrl;
        webpage.display_url = buildDisplayUrl(link.canonicalUrl);
        webpage.site_name = link.platformName;
        webpage.title = !TextUtils.isEmpty(media.title) ? media.title : link.platformName;
        webpage.description = media.description;

        if (previewMedia instanceof ResolvedMedia.Video) {
            ResolvedMedia.Video video = (ResolvedMedia.Video) previewMedia;
            boolean supportsDirectVideoStreaming = resolver == null || resolver.supportsDirectVideoStreaming();
            TLRPC.Document videoDocument = ExternalMediaPreviewStore.putVideo(
                webpage.id, link.platformName, link.canonicalUrl,
                video.videoUrl, video.posterUrl, video.width, video.height,
                webpage.title, webpage.description
            );
            String posterUrl = !TextUtils.isEmpty(video.posterUrl) ? video.posterUrl : supportsDirectVideoStreaming ? video.videoUrl : null;
            if (TextUtils.isEmpty(posterUrl)) {
                return null;
            }
            webpage.type = "video";
            webpage.document = videoDocument;
            webpage.embed_url = posterUrl;
            webpage.embed_width = video.width;
            webpage.embed_height = video.height;
        } else if (previewMedia instanceof ResolvedMedia.Image) {
            ResolvedMedia.Image image = (ResolvedMedia.Image) previewMedia;
            webpage.type = "photo";
            webpage.embed_url = image.imageUrl;
            webpage.embed_width = image.width;
            webpage.embed_height = image.height;
        } else if (previewMedia instanceof ResolvedMedia.Preview) {
            ResolvedMedia.Preview preview = (ResolvedMedia.Preview) previewMedia;
            webpage.type = "photo";
            webpage.embed_url = preview.posterUrl;
            webpage.embed_width = preview.width;
            webpage.embed_height = preview.height;
        } else {
            return null;
        }
        normalizeWebPageFlags(webpage);
        return webpage;
    }

    private static TLRPC.WebPage ensureRenderableStoredWebPage(MessagesStorage.ExternalPreviewRecord preview) {
        if (preview.webPage instanceof TLRPC.TL_webPage && hasRenderableExternalPreview(preview.webPage)) {
            if (preview.previewKind == MessagesStorage.EXTERNAL_PREVIEW_KIND_VIDEO) {
                ensureStoredVideoDocument((TLRPC.TL_webPage) preview.webPage, preview);
            }
            normalizeWebPageFlags((TLRPC.TL_webPage) preview.webPage);
            return preview.webPage;
        }
        TLRPC.TL_webPage webPage = new TLRPC.TL_webPage();
        webPage.id = preview.webPageId;
        webPage.url = preview.canonicalUrl;
        webPage.display_url = buildDisplayUrl(preview.canonicalUrl);
        webPage.site_name = preview.platform;
        webPage.title = preview.title;
        webPage.description = preview.description;
        if (preview.previewKind == MessagesStorage.EXTERNAL_PREVIEW_KIND_VIDEO) {
            webPage.type = "video";
            webPage.embed_url = !TextUtils.isEmpty(preview.posterUrl) ? preview.posterUrl : preview.mediaUrl;
            ensureStoredVideoDocument(webPage, preview);
        } else if (preview.previewKind == MessagesStorage.EXTERNAL_PREVIEW_KIND_IMAGE) {
            webPage.type = "photo";
            webPage.embed_url = preview.mediaUrl;
        } else {
            webPage.type = "photo";
            webPage.embed_url = !TextUtils.isEmpty(preview.posterUrl) ? preview.posterUrl : preview.mediaUrl;
        }
        webPage.embed_width = preview.width;
        webPage.embed_height = preview.height;
        normalizeWebPageFlags(webPage);
        return webPage;
    }

    private static void ensureStoredVideoDocument(TLRPC.TL_webPage webPage, MessagesStorage.ExternalPreviewRecord preview) {
        if (webPage == null || preview == null) {
            return;
        }
        webPage.type = "video";
        if (TextUtils.isEmpty(webPage.embed_url)) {
            webPage.embed_url = !TextUtils.isEmpty(preview.posterUrl) ? preview.posterUrl : preview.mediaUrl;
        }
        if (webPage.document == null) {
            webPage.document = ExternalMediaPreviewStore.putVideo(
                preview.webPageId, preview.platform, preview.canonicalUrl,
                preview.mediaUrl, preview.posterUrl, preview.width, preview.height,
                preview.title, preview.description
            );
        }
    }

    private static void normalizeWebPageFlags(TLRPC.TL_webPage webPage) {
        if (webPage == null) {
            return;
        }
        int flags = webPage.flags;
        flags = !TextUtils.isEmpty(webPage.type) ? (flags | 1) : (flags & ~1);
        flags = !TextUtils.isEmpty(webPage.site_name) ? (flags | 2) : (flags & ~2);
        flags = !TextUtils.isEmpty(webPage.title) ? (flags | 4) : (flags & ~4);
        flags = !TextUtils.isEmpty(webPage.description) ? (flags | 8) : (flags & ~8);
        flags = webPage.photo != null ? (flags | 16) : (flags & ~16);
        flags = !TextUtils.isEmpty(webPage.embed_url) ? (flags | 32) : (flags & ~32);
        flags = (webPage.embed_width != 0 || webPage.embed_height != 0) ? (flags | 64) : (flags & ~64);
        flags = webPage.duration != 0 ? (flags | 128) : (flags & ~128);
        flags = !TextUtils.isEmpty(webPage.author) ? (flags | 256) : (flags & ~256);
        flags = webPage.document != null ? (flags | 512) : (flags & ~512);
        flags = webPage.cached_page != null ? (flags | 1024) : (flags & ~1024);
        flags = webPage.attributes != null && !webPage.attributes.isEmpty() ? (flags | 4096) : (flags & ~4096);
        webPage.flags = flags;
        if ((flags & 32) != 0 && TextUtils.isEmpty(webPage.embed_type)) {
            webPage.embed_type = "image";
        }
    }

    private static ResolvedMedia.Single pickPreviewMedia(ResolvedMedia media) {
        if (media instanceof ResolvedMedia.Carousel) {
            ArrayList<ResolvedMedia.Single> items = ((ResolvedMedia.Carousel) media).items;
            if (items.isEmpty()) {
                return null;
            }
            return items.get(0);
        } else if (media instanceof ResolvedMedia.Single) {
            return (ResolvedMedia.Single) media;
        }
        return null;
    }

    static ParsedLink findPlatformLink(TLRPC.Message message) {
        if (message == null || TextUtils.isEmpty(message.message)) {
            return null;
        }
        ArrayList<String> rawUrls = BuildVars.LOGS_ENABLED ? new ArrayList<>() : null;
        ArrayList<String> urls = new ArrayList<>();
        if (message.entities != null) {
            for (int i = 0; i < message.entities.size(); i++) {
                TLRPC.MessageEntity entity = message.entities.get(i);
                String url = null;
                if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                    url = ((TLRPC.TL_messageEntityTextUrl) entity).url;
                } else if (entity instanceof TLRPC.TL_messageEntityUrl) {
                    int start = entity.offset;
                    int end = entity.offset + entity.length;
                    if (start >= 0 && end <= message.message.length() && start < end) {
                        url = message.message.substring(start, end);
                    }
                }
                if (!TextUtils.isEmpty(url)) {
                    if (rawUrls != null && !rawUrls.contains(url)) {
                        rawUrls.add(url);
                    }
                    if (!urls.contains(url)) {
                        urls.add(url);
                    }
                }
            }
        }
        if (urls.isEmpty() && AndroidUtilities.WEB_URL != null) {
            Matcher matcher = AndroidUtilities.WEB_URL.matcher(message.message);
            while (matcher.find()) {
                String value = matcher.group();
                if (rawUrls != null && !rawUrls.contains(value)) {
                    rawUrls.add(value);
                }
                if (!urls.contains(value)) {
                    urls.add(value);
                }
                if (urls.size() > 1) {
                    break;
                }
            }
        }
        if (urls.size() != 1) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d(TAG + ": findPlatformLink fail mid=" + message.id + " raw=" + rawUrls + " deduped=" + urls);
            }
            return null;
        }
        ParsedLink parsedLink = parseCandidate(urls.get(0));
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d(TAG + ": findPlatformLink mid=" + message.id + " raw=" + rawUrls + " deduped=" + urls + " parsed=" + (parsedLink != null ? parsedLink.canonicalUrl : "null"));
        }
        return parsedLink;
    }

    private static ParsedLink parseCandidate(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        String normalized = value;
        if (!normalized.contains("://")) {
            normalized = "https://" + normalized;
        }
        Uri uri = Uri.parse(normalized);
        ExternalMediaResolver resolver = ExternalLinkRouter.findResolver(uri);
        if (resolver == null) {
            return null;
        }
        return resolver.parseLink(uri);
    }

    static long computeStableId(String value) {
        String md5 = Utilities.MD5(value);
        if (TextUtils.isEmpty(md5) || md5.length() < 16) {
            return Math.abs((long) value.hashCode());
        }
        try {
            long hi = Long.parseLong(md5.substring(0, 8), 16);
            long lo = Long.parseLong(md5.substring(8, 16), 16);
            return (hi << 32) | lo;
        } catch (Exception ignore) {
            return Math.abs((long) value.hashCode());
        }
    }

    private static String buildDisplayUrl(String canonicalUrl) {
        Uri uri = Uri.parse(canonicalUrl);
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null) {
            return canonicalUrl;
        }
        return path == null ? host : host + path;
    }

    private static final class PendingMessage {
        final int account;
        final TLRPC.Message message;

        PendingMessage(int account, TLRPC.Message message) {
            this.account = account;
            this.message = message;
        }
    }

    private static final class CachedPreview {
        final TLRPC.WebPage webPage;
        final ResolvedMedia media;

        CachedPreview(TLRPC.WebPage webPage, ResolvedMedia media) {
            this.webPage = webPage;
            this.media = media;
        }
    }

    private enum ResolvePriority {
        BATCH_LOW,
        LOOKAHEAD,
        VISIBLE_NOW
    }

    private static final class ResolveRequest {
        final ParsedLink link;
        final ExternalMediaResolver resolver;
        final long sequence;
        final long generation;
        ResolvePriority priority;

        ResolveRequest(ParsedLink link, ExternalMediaResolver resolver, ResolvePriority priority, long sequence, long generation) {
            this.link = link;
            this.resolver = resolver;
            this.priority = priority;
            this.sequence = sequence;
            this.generation = generation;
        }
    }

    private static void enqueueOrUpgradeLocked(ParsedLink link, ExternalMediaResolver resolver, ResolvePriority priority, MessageObject messageObject, String reason) {
        ResolveRequest request = queuedRequests.get(link.canonicalUrl);
        if (request == null) {
            request = new ResolveRequest(link, resolver, priority, nextSequence++, resetGeneration);
            queuedRequests.put(link.canonicalUrl, request);
            log(reason + " priority=" + priority.name(), link, resolver, null, messageObject);
        } else if (priority.ordinal() > request.priority.ordinal()) {
            ResolvePriority previous = request.priority;
            request.priority = priority;
            log("priority upgrade " + previous.name() + "->" + priority.name(), link, resolver, null, messageObject);
        } else {
            log(reason + " priority stays=" + request.priority.name(), link, resolver, null, messageObject);
        }
    }

    private static void dispatchQueuedResolves() {
        ArrayList<ResolveRequest> toStart = new ArrayList<>();
        synchronized (lock) {
            while (activeResolves < MAX_CONCURRENT_RESOLVES && !queuedRequests.isEmpty()) {
                ResolveRequest next = takeNextRequestLocked();
                if (next == null) {
                    break;
                }
                runningRequests.put(next.link.canonicalUrl, next);
                activeResolves++;
                toStart.add(next);
            }
        }
        for (int i = 0; i < toStart.size(); i++) {
            startResolve(toStart.get(i));
        }
    }

    private static ResolveRequest takeNextRequestLocked() {
        ResolveRequest best = null;
        for (ResolveRequest request : queuedRequests.values()) {
            if (best == null
                || request.priority.ordinal() > best.priority.ordinal()
                || request.priority == best.priority && request.sequence < best.sequence) {
                best = request;
            }
        }
        if (best != null) {
            queuedRequests.remove(best.link.canonicalUrl);
        }
        return best;
    }

    private static void log(String event, ParsedLink link, ExternalMediaResolver resolver, String extra, MessageObject messageObject) {
        if (!BuildVars.DEBUG_PRIVATE_VERSION && !BuildVars.LOGS_ENABLED) {
            return;
        }
        StringBuilder sb = new StringBuilder(TAG).append(": ").append(event);
        if (messageObject != null && messageObject.messageOwner != null) {
            sb.append(" mid=").append(messageObject.messageOwner.id);
            sb.append(" uid=").append(MessageObject.getDialogId(messageObject.messageOwner));
        }
        if (resolver != null) {
            sb.append(" site=").append(resolver.platformName());
        }
        if (link != null) {
            sb.append(" url=").append(link.canonicalUrl);
        }
        if (extra != null) {
            sb.append(" ").append(extra);
        }
        String line = sb.toString();
        Log.d("tmessages", line);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d(line);
        }
    }
}
