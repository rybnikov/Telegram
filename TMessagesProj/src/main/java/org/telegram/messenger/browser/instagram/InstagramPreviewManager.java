package org.telegram.messenger.browser.instagram;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.external.ExternalMediaPreviewStore;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;

public final class InstagramPreviewManager {

    private static final String TAG = "InstagramPreview";
    private static final int MAX_CACHE_SIZE = 32;

    private static final Object lock = new Object();
    private static final LinkedHashMap<String, CachedPreview> cache = new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true);
    private static final HashMap<String, ArrayList<PendingMessage>> pendingMessages = new HashMap<>();

    private InstagramPreviewManager() {
    }

    public static void requestPreviewIfNeeded(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.isRestrictedMessage) {
            return;
        }
        InstagramLinkParser.ParsedLink link = findInstagramLink(messageObject.messageOwner);
        if (link == null) {
            return;
        }
        TLRPC.MessageMedia messageMedia = MessageObject.getMedia(messageObject.messageOwner);
        if (shouldSkipExistingMedia(messageMedia, link)) {
            FileLog.d(TAG + ": skip existing media " + messageObject.getId());
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

        FileLog.d(TAG + ": schedule resolve " + link.canonicalUrl);

        Utilities.globalQueue.postRunnable(() -> {
            CachedPreview cached = null;
            Throwable error = null;
            try {
                InstagramMediaResolver.ResolvedMedia media = new InstagramMediaResolver().resolve(link);
                if (media != null) {
                    TLRPC.WebPage webpage = buildWebPage(link, media);
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
                FileLog.d(TAG + ": loaded preview " + link.canonicalUrl);
            });
        });
    }

    public static boolean openCachedPreview(android.content.Context context, Uri uri) {
        InstagramLinkParser.ParsedLink link = InstagramLinkParser.parse(uri);
        if (link == null) {
            return false;
        }
        CachedPreview cachedPreview;
        synchronized (lock) {
            cachedPreview = cache.get(link.canonicalUrl);
        }
        return cachedPreview != null
            && cachedPreview.media != null
            && InstagramMediaOpenHelper.openResolved(context, uri, cachedPreview.media);
    }

    public static boolean openCachedPreview(android.content.Context context, TLRPC.Message message) {
        InstagramLinkParser.ParsedLink link = findInstagramLink(message);
        if (link == null) {
            return false;
        }
        CachedPreview cachedPreview;
        synchronized (lock) {
            cachedPreview = cache.get(link.canonicalUrl);
        }
        return cachedPreview != null
            && cachedPreview.media != null
            && InstagramMediaOpenHelper.openResolved(context, Uri.parse(link.canonicalUrl), cachedPreview.media);
    }

    private static boolean shouldSkipExistingMedia(TLRPC.MessageMedia messageMedia, InstagramLinkParser.ParsedLink link) {
        if (messageMedia == null || messageMedia instanceof TLRPC.TL_messageMediaEmpty) {
            return false;
        }
        if (!(messageMedia instanceof TLRPC.TL_messageMediaWebPage)) {
            return true;
        }
        if (!(messageMedia.webpage instanceof TLRPC.TL_webPage)) {
            return false;
        }
        return isManagedPreview(messageMedia.webpage, link);
    }

    private static boolean isManagedPreview(TLRPC.WebPage webPage, InstagramLinkParser.ParsedLink link) {
        return webPage != null && link != null && webPage.id == computeStableId(link.canonicalUrl);
    }

    private static void trimCache() {
        while (cache.size() > MAX_CACHE_SIZE) {
            String firstKey = cache.keySet().iterator().next();
            cache.remove(firstKey);
        }
    }

    private static void postResolvedPreview(int account, TLRPC.Message message, TLRPC.WebPage webpage) {
        ArrayList<TLRPC.Message> messages = new ArrayList<>(1);
        messages.add(createPreviewMessage(message, webpage));
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didReceivedWebpages, messages);
    }

    private static TLRPC.Message createPreviewMessage(TLRPC.Message source, TLRPC.WebPage webpage) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = source.id;
        message.peer_id = source.peer_id;
        message.from_id = source.from_id;
        message.media = new TLRPC.TL_messageMediaWebPage();
        message.media.webpage = webpage;
        return message;
    }

    private static TLRPC.WebPage buildWebPage(InstagramLinkParser.ParsedLink link, InstagramMediaResolver.ResolvedMedia media) {
        InstagramMediaResolver.ResolvedMedia.Single previewMedia = pickPreviewMedia(media);
        if (previewMedia == null) {
            return null;
        }

        TLRPC.TL_webPage webpage = new TLRPC.TL_webPage();
        webpage.id = computeStableId(link.canonicalUrl);
        webpage.url = link.canonicalUrl;
        webpage.display_url = buildDisplayUrl(link.canonicalUrl);
        webpage.site_name = "Instagram";
        webpage.title = !TextUtils.isEmpty(media.title) ? media.title : "Instagram";
        webpage.description = media.description;
        if (previewMedia instanceof InstagramMediaResolver.ResolvedMedia.Video) {
            InstagramMediaResolver.ResolvedMedia.Video video = (InstagramMediaResolver.ResolvedMedia.Video) previewMedia;
            webpage.type = "video";
            webpage.embed_url = !TextUtils.isEmpty(video.posterUrl) ? video.posterUrl : video.videoUrl;
            webpage.embed_width = video.width;
            webpage.embed_height = video.height;
            webpage.document = ExternalMediaPreviewStore.putVideo(
                webpage.id,
                "Instagram",
                link.canonicalUrl,
                video.videoUrl,
                video.posterUrl,
                video.width,
                video.height,
                webpage.title,
                webpage.description
            );
        } else if (previewMedia instanceof InstagramMediaResolver.ResolvedMedia.Image) {
            InstagramMediaResolver.ResolvedMedia.Image image = (InstagramMediaResolver.ResolvedMedia.Image) previewMedia;
            webpage.type = "photo";
            webpage.embed_url = image.imageUrl;
            webpage.embed_width = image.width;
            webpage.embed_height = image.height;
        } else {
            return null;
        }
        return webpage;
    }

    private static InstagramMediaResolver.ResolvedMedia.Single pickPreviewMedia(InstagramMediaResolver.ResolvedMedia media) {
        if (media instanceof InstagramMediaResolver.ResolvedMedia.Carousel) {
            ArrayList<InstagramMediaResolver.ResolvedMedia.Single> items = ((InstagramMediaResolver.ResolvedMedia.Carousel) media).items;
            if (items.isEmpty()) {
                return null;
            }
            return pickPreviewMedia(items.get(0));
        } else if (media instanceof InstagramMediaResolver.ResolvedMedia.Video) {
            return (InstagramMediaResolver.ResolvedMedia.Video) media;
        } else if (media instanceof InstagramMediaResolver.ResolvedMedia.Image) {
            return (InstagramMediaResolver.ResolvedMedia.Image) media;
        }
        return null;
    }

    private static InstagramLinkParser.ParsedLink findInstagramLink(TLRPC.Message message) {
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
        if (urls.isEmpty()) {
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

    private static InstagramLinkParser.ParsedLink parseCandidate(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        String normalized = value;
        if (!normalized.contains("://")) {
            normalized = "https://" + normalized;
        }
        Uri uri = Uri.parse(normalized);
        return InstagramLinkParser.parse(uri);
    }

    private static long computeStableId(String value) {
        String md5 = Utilities.MD5(value);
        if (md5 == null) {
            return value.hashCode();
        }
        long result = 0;
        for (int i = 0; i < 15 && i < md5.length(); i++) {
            int digit = Character.digit(md5.charAt(i), 16);
            if (digit < 0) {
                continue;
            }
            result = (result << 4) | digit;
        }
        return result != 0 ? result : value.hashCode();
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
        final InstagramMediaResolver.ResolvedMedia media;

        CachedPreview(TLRPC.WebPage webPage, InstagramMediaResolver.ResolvedMedia media) {
            this.webPage = webPage;
            this.media = media;
        }
    }

}
