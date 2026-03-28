package org.telegram.messenger.browser.tiktok;

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

public final class TikTokPreviewManager {

    private static final String TAG = "TikTokPreview";
    private static final int MAX_CACHE_SIZE = 32;

    private static final Object lock = new Object();
    private static final LinkedHashMap<String, CachedPreview> cache = new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true);
    private static final HashMap<String, ArrayList<PendingMessage>> pendingMessages = new HashMap<>();

    private TikTokPreviewManager() {
    }

    public static void requestPreviewIfNeeded(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.isRestrictedMessage) {
            return;
        }
        TikTokLinkParser.ParsedLink link = findTikTokLink(messageObject.messageOwner);
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
                TikTokMediaResolver.ResolvedMedia media = new TikTokMediaResolver().resolve(link);
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
        TikTokLinkParser.ParsedLink link = TikTokLinkParser.parse(uri);
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
        if (cachedPreview.media instanceof TikTokMediaResolver.ResolvedMedia.Preview) {
            Browser.openUrl(context, Uri.parse(cachedPreview.webPage.url), true, true, false, null, null, false, true, false);
            return true;
        }
        return TikTokMediaOpenHelper.openResolved(context, Uri.parse(cachedPreview.webPage.url), cachedPreview.media);
    }

    public static boolean openCachedPreview(android.content.Context context, TLRPC.Message message) {
        TikTokLinkParser.ParsedLink link = findTikTokLink(message);
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
        if (cachedPreview.media instanceof TikTokMediaResolver.ResolvedMedia.Preview) {
            Browser.openUrl(context, Uri.parse(cachedPreview.webPage.url), true, true, false, null, null, false, true, false);
            return true;
        }
        return TikTokMediaOpenHelper.openResolved(context, Uri.parse(cachedPreview.webPage.url), cachedPreview.media);
    }

    private static boolean shouldSkipExistingMedia(TLRPC.MessageMedia messageMedia, TikTokLinkParser.ParsedLink link) {
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

    private static boolean isManagedPreview(TLRPC.WebPage webPage, TikTokLinkParser.ParsedLink link) {
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

    private static TLRPC.WebPage buildWebPage(TikTokLinkParser.ParsedLink link, TikTokMediaResolver.ResolvedMedia media) {
        TLRPC.TL_webPage webpage = new TLRPC.TL_webPage();
        webpage.id = computeStableId(link.canonicalUrl);
        webpage.url = link.canonicalUrl;
        webpage.display_url = buildDisplayUrl(link.canonicalUrl);
        webpage.site_name = "TikTok";
        webpage.title = !TextUtils.isEmpty(media.title) ? media.title : "TikTok";
        webpage.description = media.description;
        if (media instanceof TikTokMediaResolver.ResolvedMedia.Preview) {
            TikTokMediaResolver.ResolvedMedia.Preview preview = (TikTokMediaResolver.ResolvedMedia.Preview) media;
            webpage.type = "photo";
            webpage.embed_url = preview.posterUrl;
            webpage.embed_width = preview.width;
            webpage.embed_height = preview.height;
        } else if (media instanceof TikTokMediaResolver.ResolvedMedia.Video) {
            TikTokMediaResolver.ResolvedMedia.Video video = (TikTokMediaResolver.ResolvedMedia.Video) media;
            webpage.type = "video";
            webpage.embed_url = !TextUtils.isEmpty(video.posterUrl) ? video.posterUrl : video.videoUrl;
            webpage.embed_width = video.width;
            webpage.embed_height = video.height;
        } else {
            return null;
        }
        return webpage;
    }

    private static TikTokLinkParser.ParsedLink findTikTokLink(TLRPC.Message message) {
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
        String normalized = urls.get(0);
        if (!normalized.contains("://")) {
            normalized = "https://" + normalized;
        }
        return TikTokLinkParser.parse(Uri.parse(normalized));
    }

    private static long computeStableId(String value) {
        String md5 = Utilities.MD5(value);
        if (TextUtils.isEmpty(md5) || md5.length() < 16) {
            return Math.abs((long) value.hashCode());
        }
        try {
            return Long.parseUnsignedLong(md5.substring(0, 16), 16);
        } catch (Exception ignore) {
            return Math.abs((long) value.hashCode());
        }
    }

    private static String buildDisplayUrl(String url) {
        Uri uri = Uri.parse(url);
        StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(uri.getHost())) {
            builder.append(uri.getHost());
        }
        if (!TextUtils.isEmpty(uri.getPath())) {
            builder.append(uri.getPath());
        }
        return builder.toString();
    }

    private static final class CachedPreview {
        private final TLRPC.WebPage webPage;
        private final TikTokMediaResolver.ResolvedMedia media;

        private CachedPreview(TLRPC.WebPage webPage, TikTokMediaResolver.ResolvedMedia media) {
            this.webPage = webPage;
            this.media = media;
        }
    }

    private static final class PendingMessage {
        private final int account;
        private final TLRPC.Message message;

        private PendingMessage(int account, TLRPC.Message message) {
            this.account = account;
            this.message = message;
        }
    }
}
