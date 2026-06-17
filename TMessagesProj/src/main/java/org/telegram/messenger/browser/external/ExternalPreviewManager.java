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
import org.telegram.messenger.UserConfig;
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
    private static final int MAX_PLATFORM_LINK_SCAN = 4;
    // Increment whenever external preview extraction or persisted preview format changes.
    public static final int EXTERNAL_PREVIEW_FORMAT_VERSION = 2;

    private static final Object lock = new Object();
    private static final LinkedHashMap<String, CachedPreview> cache = new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 1.0f, true);
    private static final HashMap<String, ArrayList<PendingMessage>> pendingMessages = new HashMap<>();
    private static final HashSet<String> persistentLookups = new HashSet<>();
    private static final HashSet<Long> warmAttempted = new HashSet<>();
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
            warmAttempted.clear();
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
        ExternalMediaResolver resolver = ResolverRegistry.findResolver(link.getCanonicalUri());
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
        ExternalMediaResolver resolver = ResolverRegistry.findResolver(link.getCanonicalUri());
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
            warmStoreIfNeeded(messageObject.currentAccount, messageMedia, link, resolver, messageObject);
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
                    TLRPC.WebPage webpage = PreviewMapper.buildWebPage(request.link, request.resolver, media);
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
        ExternalMediaResolver resolver = ResolverRegistry.findResolver(uri);
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
        return openCachedMedia(context, resolver, cachedPreview, link);
    }

    public static boolean openCachedPreview(Context context, TLRPC.Message message) {
        return openCachedPreview(context, message, null);
    }

    private static boolean openCachedPreview(Context context, TLRPC.Message message, ExternalMediaOpenHelper.ProgressHandle progressHandle) {
        ParsedLink link = findPlatformLink(message);
        if (link == null) {
            return false;
        }
        ExternalMediaResolver resolver = ResolverRegistry.findResolver(link.getCanonicalUri());
        if (resolver == null || !resolver.overridesServerPreview()) {
            return false;
        }
        CachedPreview cachedPreview;
        synchronized (lock) {
            cachedPreview = cache.get(link.canonicalUrl);
        }
        if (cachedPreview == null || cachedPreview.media == null) {
            log("openCachedPreview(message) miss", link, resolver, null, null);
            if (shouldOpenAppliedExternalPreviewAsync(message, link, resolver)) {
                openAppliedExternalPreviewAsync(context, UserConfig.selectedAccount, link, resolver, progressHandle);
                return true;
            }
            return false;
        }
        log("openCachedPreview(message) hit", link, resolver, null, null);
        return openCachedMedia(context, resolver, cachedPreview, link);
    }

    private static boolean openCachedMedia(Context context, ExternalMediaResolver resolver, CachedPreview cachedPreview, ParsedLink link) {
        if (PreviewMapper.asPreview(cachedPreview.media) != null) {
            Browser.openUrl(context, Uri.parse(cachedPreview.webPage.url), true, true, false, null, null, false, true, false);
            return true;
        }
        ResolvedMedia.Video video = PreviewMapper.asVideo(cachedPreview.media);
        if (video != null) {
            ExternalMediaPreviewStore.VideoPreview preview = ExternalMediaPreviewStore.getVideoPreview(cachedPreview.webPage.id);
            if (resolver instanceof PlaybackResolver) {
                PreviewClickDispatcher.resolveAndOpenVideoAsync(context, link, (PlaybackResolver) resolver, video, preview);
                return true;
            }
            if (preview != null) {
                return PreviewClickDispatcher.openVideo(context, link, video, new Playback.DirectStream(preview.videoUrl), preview);
            }
        }
        // Preview-only resolvers (no button, e.g. Maps) — don't intercept click
        if (ExternalLinkRouter.getInstantButtonText(resolver.platformName(), cachedPreview.webPage) == null) {
            return false;
        }
        return ExternalMediaOpenHelper.openResolved(context, link.getCanonicalUri(), cachedPreview.media);
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
        ExternalMediaResolver resolver = !TextUtils.isEmpty(preview.sourceUrl) ? ResolverRegistry.findResolver(Uri.parse(preview.sourceUrl)) : null;
        if (resolver != null && !resolver.overridesServerPreview()) {
            return false;
        }
        ParsedLink link = resolver != null ? resolver.parseLink(Uri.parse(preview.sourceUrl)) : null;
        if (link == null) {
            link = new ParsedLink(preview.sourceUrl, preview.sourceUrl, null, preview.sourceName);
        }
        ResolvedMedia.Video video = new ResolvedMedia.Video(preview.videoUrl, preview.posterUrl, preview.title, preview.description, preview.width, preview.height);
        if (resolver instanceof PlaybackResolver) {
            PreviewClickDispatcher.resolveAndOpenVideoAsync(context, link, (PlaybackResolver) resolver, video, preview);
            return true;
        }
        return PreviewClickDispatcher.openVideo(context, link, video, new Playback.DirectStream(preview.videoUrl), preview);
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
        long stableId = PreviewMapper.computeStableId(link.canonicalUrl);
        if (resolver != null && resolver.overridesServerPreview() && webPage.id == stableId && shouldRefreshExternalVideoPreview(resolver, webPage, link)) {
            return false;
        }
        if (resolver != null && resolver.overridesServerPreview() && webPage.id == stableId && !PreviewMapper.hasRenderableExternalPreview(webPage)) {
            return false;
        }
        return webPage.id == stableId;
    }

    private static void warmStoreIfNeeded(int account, TLRPC.MessageMedia messageMedia, ParsedLink link, ExternalMediaResolver resolver, MessageObject messageObject) {
        if (!(messageMedia instanceof TLRPC.TL_messageMediaWebPage) || link == null || resolver == null) {
            return;
        }
        TLRPC.WebPage webPage = messageMedia.webpage;
        if (!shouldWarmAppliedExternalWebPage(webPage, link, resolver)) {
            return;
        }
        if (ExternalMediaPreviewStore.getVideoPreview(webPage.id) != null) {
            return;
        }
        boolean shouldLookup = false;
        synchronized (lock) {
            if (cache.containsKey(link.canonicalUrl) || persistentLookups.contains(link.canonicalUrl) || warmAttempted.contains(webPage.id)) {
                return;
            }
            warmAttempted.add(webPage.id);
            persistentLookups.add(link.canonicalUrl);
            shouldLookup = true;
            log("warm persisted lookup", link, resolver, "webpageId=" + webPage.id, messageObject);
        }
        if (shouldLookup) {
            lookupPersistedPreview(account, link, resolver);
        }
    }

    private static boolean shouldOpenAppliedExternalPreviewAsync(TLRPC.Message message, ParsedLink link, ExternalMediaResolver resolver) {
        TLRPC.MessageMedia messageMedia = MessageObject.getMedia(message);
        if (!(messageMedia instanceof TLRPC.TL_messageMediaWebPage)) {
            return false;
        }
        return shouldWarmAppliedExternalWebPage(messageMedia.webpage, link, resolver);
    }

    private static boolean shouldWarmAppliedExternalWebPage(TLRPC.WebPage webPage, ParsedLink link, ExternalMediaResolver resolver) {
        if (!(webPage instanceof TLRPC.TL_webPage) || link == null || resolver == null) {
            return false;
        }
        if (webPage.id != PreviewMapper.computeStableId(link.canonicalUrl)) {
            return false;
        }
        return webPage.document != null
            || "video".equals(webPage.type)
            || ExternalLinkRouter.getInstantButtonText(resolver.platformName(), webPage) != null;
    }

    private static void openAppliedExternalPreviewAsync(Context context, int account, ParsedLink link, ExternalMediaResolver resolver, ExternalMediaOpenHelper.ProgressHandle progressHandle) {
        if (progressHandle != null) {
            progressHandle.init();
        }
        AccountInstance.getInstance(account).getMessagesStorage().getExternalPreview(link.canonicalUrl, storedPreview -> {
            CachedPreview hydrated = storedPreview != null ? hydrateCachedPreview(storedPreview) : null;
            if (hydrated != null) {
                synchronized (lock) {
                    cache.put(link.canonicalUrl, hydrated);
                    trimCache();
                }
                log("openCachedPreview(message) persisted hit", link, resolver, null, null);
                if (progressHandle != null) {
                    progressHandle.end();
                }
                if (!openCachedMedia(context, resolver, hydrated, link)) {
                    Browser.openUrl(context, link.getCanonicalUri(), true, true, false, null, null, false, true, false);
                }
                return;
            }
            log("openCachedPreview(message) persisted miss", link, resolver, null, null);
            if (progressHandle != null) {
                progressHandle.end();
            }
            boolean handled = ExternalMediaOpenHelper.tryOpen(
                context,
                link.getCanonicalUri(),
                fallbackUri -> Browser.openUrl(context, fallbackUri, true, true, false, null, null, false, true, false),
                progressHandle
            );
            if (!handled) {
                Browser.openUrl(context, link.getCanonicalUri(), true, true, false, null, null, false, true, false);
            }
        });
    }

    private static boolean shouldRefreshExternalVideoPreview(ExternalMediaResolver resolver, TLRPC.WebPage webPage, ParsedLink link) {
        if (webPage == null || link == null || TextUtils.isEmpty(link.canonicalUrl) || resolver == null) {
            return false;
        }
        boolean supportsDirectVideoStreaming = resolver.supportsDirectVideoStreaming();
        boolean looksLikeVideo = "video".equals(webPage.type) || webPage.document != null;
        if (supportsDirectVideoStreaming && looksLikeVideo && webPage.document == null) {
            return true;
        }
        if (!supportsDirectVideoStreaming && webPage.document != null) {
            return true;
        }
        return supportsDirectVideoStreaming
            && webPage.document == null
            && resolver.shouldRefreshResolvedVideoPreview(webPage, link);
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
                    hydrated = hydrateCachedPreview(storedPreview);
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
            } else if (hydrated != null) {
                log("persisted hit warm", link, resolver, null, null);
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
        log("persist write account=" + account + " webpageId=" + cachedPreview.webPage.id + " kind=" + PreviewMapper.describePreviewKind(cachedPreview.media), request.link, request.resolver, null, null);
        AccountInstance.getInstance(account).getMessagesStorage().putExternalPreview(PreviewMapper.createExternalPreviewRecord(request.link, cachedPreview.webPage, cachedPreview.media));
    }

    private static CachedPreview hydrateCachedPreview(MessagesStorage.ExternalPreviewRecord preview) {
        PreviewMapper.HydratedPreview hydrated = PreviewMapper.hydrateCachedPreview(preview);
        if (hydrated == null) {
            return null;
        }
        return new CachedPreview(hydrated.webPage, hydrated.media);
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
                if (urls.size() >= MAX_PLATFORM_LINK_SCAN) {
                    break;
                }
            }
        }
        ParsedLink parsedLink = null;
        int parsedIndex = -1;
        for (int i = 0; i < urls.size() && i < MAX_PLATFORM_LINK_SCAN; i++) {
            parsedLink = parseCandidate(urls.get(i));
            if (parsedLink != null) {
                parsedIndex = i;
                break;
            }
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d(TAG + ": findPlatformLink mid=" + message.id + " raw=" + rawUrls + " deduped=" + urls + " parsedIndex=" + parsedIndex + " parsed=" + (parsedLink != null ? parsedLink.canonicalUrl : "null"));
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
        ExternalMediaResolver resolver = ResolverRegistry.findResolver(uri);
        if (resolver == null) {
            return null;
        }
        return resolver.parseLink(uri);
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
