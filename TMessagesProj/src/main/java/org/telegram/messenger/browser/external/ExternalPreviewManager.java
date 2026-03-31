package org.telegram.messenger.browser.external;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;

public final class ExternalPreviewManager {

    private static final String TAG = "ExternalPreview";
    private static final int MAX_CACHE_SIZE = 64;

    private static final Object lock = new Object();
    private static final LinkedHashMap<String, CachedPreview> cache = new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 1.0f, true);
    private static final HashMap<String, ArrayList<PendingMessage>> pendingMessages = new HashMap<>();

    private ExternalPreviewManager() {
    }

    public static void preloadPreviews(ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            requestPreviewIfNeeded(messages.get(i));
        }
    }

    public static void requestPreviewIfNeeded(MessageObject messageObject) {
        if (!org.telegram.messenger.SharedConfig.extendedPreviews) {
            return;
        }
        if (messageObject == null || messageObject.messageOwner == null || messageObject.isRestrictedMessage) {
            return;
        }
        ParsedLink link = findPlatformLink(messageObject.messageOwner);
        if (link == null) {
            return;
        }
        ExternalMediaResolver resolver = ExternalLinkRouter.findResolver(link.getCanonicalUri());
        if (resolver == null) {
            return;
        }
        TLRPC.MessageMedia messageMedia = MessageObject.getMedia(messageObject.messageOwner);
        if (!resolver.overridesServerPreview() && hasServerWebPage(messageMedia)) {
            return;
        }
        if (shouldSkipExistingMedia(messageMedia, link)) {
            return;
        }

        CachedPreview cachedPreview;
        boolean shouldResolve = false;
        synchronized (lock) {
            cachedPreview = cache.get(link.canonicalUrl);
            if (cachedPreview == null) {
                ArrayList<PendingMessage> pending = pendingMessages.get(link.canonicalUrl);
                if (pending == null) {
                    pending = new ArrayList<>();
                    pendingMessages.put(link.canonicalUrl, pending);
                    shouldResolve = true;
                }
                pending.add(new PendingMessage(messageObject.currentAccount, messageObject.messageOwner));
            }
        }

        if (cachedPreview != null) {
            postResolvedPreview(messageObject.currentAccount, messageObject.messageOwner, cachedPreview.webPage);
            return;
        }
        if (!shouldResolve) {
            return;
        }

        FileLog.d(TAG + ": schedule resolve " + link.platformName + " " + link.canonicalUrl);

        // Use separate threads for parallel resolve (don't block globalQueue)
        new Thread(() -> {
            CachedPreview cached = null;
            Throwable error = null;
            try {
                ResolvedMedia media = resolver.resolve(link);
                if (media != null) {
                    TLRPC.WebPage webpage = buildWebPage(link, resolver, media);
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
                    pending = pendingMessages.remove(link.canonicalUrl);
                    if (finalCached != null) {
                        cache.put(link.canonicalUrl, finalCached);
                        trimCache();
                    }
                }
                if (pending == null || pending.isEmpty()) {
                    return;
                }
                if (finalCached == null) {
                    if (finalError != null) {
                        FileLog.d(TAG + ": fallback no preview " + finalError.getClass().getSimpleName() + " " + link.canonicalUrl);
                    } else {
                        FileLog.d(TAG + ": fallback no preview " + link.canonicalUrl);
                    }
                    return;
                }
                HashMap<Integer, ArrayList<TLRPC.Message>> messagesByAccount = new HashMap<>();
                for (int i = 0; i < pending.size(); i++) {
                    PendingMessage pendingMessage = pending.get(i);
                    ArrayList<TLRPC.Message> messages = messagesByAccount.get(pendingMessage.account);
                    if (messages == null) {
                        messages = new ArrayList<>();
                        messagesByAccount.put(pendingMessage.account, messages);
                    }
                    messages.add(createPreviewMessage(pendingMessage.message, finalCached.webPage));
                }
                for (Map.Entry<Integer, ArrayList<TLRPC.Message>> entry : messagesByAccount.entrySet()) {
                    NotificationCenter.getInstance(entry.getKey()).postNotificationName(NotificationCenter.didReceivedWebpages, entry.getValue());
                }
                FileLog.d(TAG + ": loaded preview " + link.platformName + " " + link.canonicalUrl);
            });
        }, "ExtPreview-" + link.platformName).start();
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
            return false;
        }
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
            return false;
        }
        return openCachedMedia(context, resolver, cachedPreview, link.canonicalUrl);
    }

    private static boolean openCachedMedia(Context context, ExternalMediaResolver resolver, CachedPreview cachedPreview, String canonicalUrl) {
        if (cachedPreview.media instanceof ResolvedMedia.Preview) {
            Browser.openUrl(context, Uri.parse(cachedPreview.webPage.url), true, true, false, null, null, false, true, false);
            return true;
        }
        if (cachedPreview.media instanceof ResolvedMedia.Video && !resolver.supportsDirectVideoStreaming()) {
            ResolvedMedia.Video video = (ResolvedMedia.Video) cachedPreview.media;
            if ("TikTok".equals(resolver.platformName())) {
                resolveAndStreamTikTok(context, canonicalUrl, video);
            } else {
                // YouTube and others: open embed in Telegram's EmbedBottomSheet
                openEmbedSheet(context, resolver.platformName(), video, canonicalUrl);
            }
            return true;
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
                    ResolvedMedia.Video streamVideo = new ResolvedMedia.Video(
                        videoUrl, video.posterUrl, video.title, video.description,
                        video.width, video.height
                    );
                    ExternalMediaOpenHelper.openResolved(context, Uri.parse(canonicalUrl), streamVideo);
                } else {
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

    private static boolean shouldSkipExistingMedia(TLRPC.MessageMedia messageMedia, ParsedLink link) {
        if (messageMedia == null || messageMedia instanceof TLRPC.TL_messageMediaEmpty) {
            return false;
        }
        if (!(messageMedia instanceof TLRPC.TL_messageMediaWebPage)) {
            return true;
        }
        if (!(messageMedia.webpage instanceof TLRPC.TL_webPage)) {
            return false;
        }
        return messageMedia.webpage.id == computeStableId(link.canonicalUrl);
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

    private static void applyPreviewToMessage(TLRPC.Message message, TLRPC.WebPage webpage) {
        if (message == null) {
            return;
        }
        message.media = new TLRPC.TL_messageMediaWebPage();
        message.media.webpage = webpage;
    }

    private static TLRPC.Message createPreviewMessage(TLRPC.Message source, TLRPC.WebPage webpage) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = source.id;
        message.peer_id = source.peer_id;
        message.from_id = source.from_id;
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
            if (supportsDirectVideoStreaming) {
                // Store direct video previews separately for inline playback/autoload in the cell.
                ExternalMediaPreviewStore.putVideo(
                    webpage.id, link.platformName, link.canonicalUrl,
                    video.videoUrl, video.posterUrl, video.width, video.height,
                    webpage.title, webpage.description
                );
            }
            String posterUrl = !TextUtils.isEmpty(video.posterUrl) ? video.posterUrl : supportsDirectVideoStreaming ? video.videoUrl : null;
            if (TextUtils.isEmpty(posterUrl)) {
                return null;
            }
            webpage.type = "photo";
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
        return webpage;
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
                    urls.add(url);
                }
            }
        }
        if (urls.isEmpty() && AndroidUtilities.WEB_URL != null) {
            Matcher matcher = AndroidUtilities.WEB_URL.matcher(message.message);
            while (matcher.find()) {
                urls.add(matcher.group());
                if (urls.size() > 1) {
                    break;
                }
            }
        }
        if (urls.size() != 1) {
            return null;
        }
        return parseCandidate(urls.get(0));
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
}
