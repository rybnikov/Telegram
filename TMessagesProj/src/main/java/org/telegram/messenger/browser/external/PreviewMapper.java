package org.telegram.messenger.browser.external;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public final class PreviewMapper {

    private static final String TAG = "ExternalPreview";

    private PreviewMapper() {
    }

    static TLRPC.WebPage buildWebPage(ParsedLink link, ExternalMediaResolver resolver, ResolvedMedia media) {
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

        ResolvedMedia.Video video = asVideo(previewMedia);
        ResolvedMedia.Image image = asImage(previewMedia);
        ResolvedMedia.Preview preview = asPreview(previewMedia);
        if (video != null) {
            boolean supportsDirectVideoStreaming = resolver == null || resolver.supportsDirectVideoStreaming();
            // Always persist video metadata for the click path. Non-direct platforms
            // (TikTok, etc.) must not expose the URL as an autoplayable document.
            TLRPC.Document videoDocument = ExternalMediaPreviewStore.putVideo(
                webpage.id, link.platformName, link.canonicalUrl,
                video.videoUrl, video.posterUrl, video.width, video.height,
                webpage.title, webpage.description
            );
            String posterUrl = !TextUtils.isEmpty(video.posterUrl) ? video.posterUrl : supportsDirectVideoStreaming ? video.videoUrl : null;
            if (TextUtils.isEmpty(posterUrl)) {
                return null;
            }
            if (supportsDirectVideoStreaming) {
                webpage.type = "video";
                webpage.document = videoDocument;
            } else {
                webpage.type = "photo";
                webpage.document = null;
            }
            webpage.embed_url = posterUrl;
            webpage.embed_width = video.width;
            webpage.embed_height = video.height;
        } else if (image != null) {
            webpage.type = "photo";
            webpage.embed_url = image.imageUrl;
            webpage.embed_width = image.width;
            webpage.embed_height = image.height;
        } else if (preview != null) {
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

    static MessagesStorage.ExternalPreviewRecord createExternalPreviewRecord(ParsedLink link, TLRPC.WebPage webPage, ResolvedMedia media) {
        String mediaUrl = null;
        String posterUrl = null;
        String extra = null;
        int width = 0;
        int height = 0;
        int previewKind = MessagesStorage.EXTERNAL_PREVIEW_KIND_PREVIEW;
        ResolvedMedia.Carousel carousel = asCarousel(media);
        ResolvedMedia.Video video = asVideo(media);
        ResolvedMedia.Image image = asImage(media);
        ResolvedMedia.Preview preview = asPreview(media);
        if (carousel != null) {
            ResolvedMedia.Single first = pickPreviewMedia(carousel);
            if (first != null) {
                previewKind = MessagesStorage.EXTERNAL_PREVIEW_KIND_CAROUSEL;
                ResolvedMedia.Video firstVideo = asVideo(first);
                ResolvedMedia.Image firstImage = asImage(first);
                ResolvedMedia.Preview firstPreview = asPreview(first);
                if (firstVideo != null) {
                    mediaUrl = firstVideo.videoUrl;
                    posterUrl = firstVideo.posterUrl;
                    width = firstVideo.width;
                    height = firstVideo.height;
                } else if (firstImage != null) {
                    mediaUrl = firstImage.imageUrl;
                    width = firstImage.width;
                    height = firstImage.height;
                } else if (firstPreview != null) {
                    mediaUrl = firstPreview.sourceUrl;
                    posterUrl = firstPreview.posterUrl;
                    width = firstPreview.width;
                    height = firstPreview.height;
                }
                extra = serializeCarouselItems(carousel.items);
            }
        } else if (video != null) {
            previewKind = MessagesStorage.EXTERNAL_PREVIEW_KIND_VIDEO;
            mediaUrl = video.videoUrl;
            posterUrl = video.posterUrl;
            width = video.width;
            height = video.height;
        } else if (image != null) {
            previewKind = MessagesStorage.EXTERNAL_PREVIEW_KIND_IMAGE;
            mediaUrl = image.imageUrl;
            width = image.width;
            height = image.height;
        } else if (preview != null) {
            previewKind = MessagesStorage.EXTERNAL_PREVIEW_KIND_PREVIEW;
            mediaUrl = preview.sourceUrl;
            posterUrl = preview.posterUrl;
            width = preview.width;
            height = preview.height;
        }
        return new MessagesStorage.ExternalPreviewRecord(
            webPage.id,
            link.canonicalUrl,
            link.platformName,
            webPage,
            previewKind,
            mediaUrl,
            posterUrl,
            width,
            height,
            media != null ? media.title : webPage.title,
            media != null ? media.description : webPage.description,
            extra
        );
    }

    static HydratedPreview hydrateCachedPreview(MessagesStorage.ExternalPreviewRecord preview) {
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
        } else if (preview.previewKind == MessagesStorage.EXTERNAL_PREVIEW_KIND_CAROUSEL) {
            media = hydrateCarouselPreview(preview);
            ResolvedMedia.Video firstVideo = asVideo(pickPreviewMedia(media));
            if (firstVideo != null) {
                ExternalMediaPreviewStore.putVideo(preview.webPageId, preview.platform, preview.canonicalUrl, firstVideo.videoUrl, firstVideo.posterUrl, firstVideo.width, firstVideo.height, preview.title, preview.description);
            }
        } else if (preview.previewKind == MessagesStorage.EXTERNAL_PREVIEW_KIND_IMAGE) {
            media = new ResolvedMedia.Image(preview.mediaUrl, preview.title, preview.description, preview.width, preview.height);
        } else {
            media = new ResolvedMedia.Preview(preview.mediaUrl, preview.posterUrl, preview.title, preview.description, preview.width, preview.height);
        }
        return new HydratedPreview(webPage, media);
    }

    static ArrayList<Object> createInlineResults(ParsedLink link, ResolvedMedia media) {
        ArrayList<Object> results;
        ResolvedMedia.Carousel carousel = asCarousel(media);
        ResolvedMedia.Single single = asSingle(media);
        if (carousel != null) {
            ArrayList<ResolvedMedia.Single> items = carousel.items;
            results = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                TLRPC.BotInlineResult result = createSingleInlineResult(link, items.get(i), i, media.title, media.description);
                if (result != null) {
                    results.add(result);
                }
            }
        } else if (single != null) {
            results = new ArrayList<>(1);
            TLRPC.BotInlineResult result = createSingleInlineResult(link, single, 0, media.title, media.description);
            if (result != null) {
                results.add(result);
            }
        } else {
            results = new ArrayList<>(0);
        }
        return results;
    }

    static TLRPC.BotInlineResult createVideoInlineResult(ExternalMediaPreviewStore.VideoPreview preview) {
        TLRPC.TL_botInlineResult result = new TLRPC.TL_botInlineResult();
        String md5 = Utilities.MD5(preview.videoUrl);
        result.id = md5 != null ? md5 : "ext_video";
        result.query_id = ExternalMediaOpenHelper.EXTERNAL_STREAM_INLINE_QUERY_ID;
        result.type = "video";
        result.send_message = new TLRPC.TL_botInlineMessageMediaAuto();
        result.send_message.message = preview.sourceUrl;
        result.title = !TextUtils.isEmpty(preview.title) ? preview.title : preview.sourceName;
        result.description = preview.description;
        result.url = preview.sourceUrl;
        result.content = createWebDocument(preview.videoUrl, "video/mp4", preview.width, preview.height, true);
        if (!TextUtils.isEmpty(preview.posterUrl)) {
            result.thumb = createWebDocument(preview.posterUrl, guessImageMimeType(preview.posterUrl), preview.width, preview.height, false);
            result.flags |= 16;
        }
        result.flags |= 2 | 8 | 32;
        if (!TextUtils.isEmpty(result.description)) {
            result.flags |= 4;
        }
        return result;
    }

    static ResolvedMedia.Video asVideo(ResolvedMedia media) {
        return media instanceof ResolvedMedia.Video ? (ResolvedMedia.Video) media : null;
    }

    static ResolvedMedia.Preview asPreview(ResolvedMedia media) {
        return media instanceof ResolvedMedia.Preview ? (ResolvedMedia.Preview) media : null;
    }

    static String describePreviewKind(ResolvedMedia media) {
        if (media instanceof ResolvedMedia.Video) {
            return "video";
        } else if (media instanceof ResolvedMedia.Image) {
            return "image";
        } else if (media instanceof ResolvedMedia.Preview) {
            return "preview";
        }
        return media == null ? "none" : media.getClass().getSimpleName();
    }

    private static TLRPC.BotInlineResult createSingleInlineResult(ParsedLink link, ResolvedMedia.Single media, int index, String title, String description) {
        TLRPC.TL_botInlineResult result = new TLRPC.TL_botInlineResult();
        String md5 = Utilities.MD5(link.canonicalUrl + "#" + index);
        result.id = md5 != null ? md5 : String.valueOf(index);
        result.query_id = ExternalMediaOpenHelper.EXTERNAL_STREAM_INLINE_QUERY_ID;
        result.send_message = new TLRPC.TL_botInlineMessageMediaAuto();
        result.send_message.message = link.canonicalUrl;
        result.title = !TextUtils.isEmpty(title) ? title : link.platformName;
        result.description = description;

        int flags = 2;
        if (!TextUtils.isEmpty(result.description)) {
            flags |= 4;
        }

        ResolvedMedia.Video video = asVideo(media);
        ResolvedMedia.Image image = asImage(media);
        ResolvedMedia.Preview preview = asPreview(media);
        if (video != null) {
            result.type = "video";
            result.content = createWebDocument(video.videoUrl, "video/mp4", video.width, video.height, true);
            result.url = link.canonicalUrl;
            if (!TextUtils.isEmpty(video.posterUrl)) {
                result.thumb = createWebDocument(video.posterUrl, guessImageMimeType(video.posterUrl), 0, 0, false);
                flags |= 16;
            }
            flags |= 8 | 32;
        } else if (image != null) {
            result.type = "photo";
            result.content = createWebDocument(image.imageUrl, guessImageMimeType(image.imageUrl), image.width, image.height, false);
            result.thumb = result.content;
            result.url = link.canonicalUrl;
            flags |= 8 | 16 | 32;
        } else if (preview != null) {
            result.type = "photo";
            result.content = createWebDocument(preview.posterUrl, guessImageMimeType(preview.posterUrl), preview.width, preview.height, false);
            result.thumb = result.content;
            result.url = link.canonicalUrl;
            flags |= 8 | 16 | 32;
        } else {
            return null;
        }

        result.flags = flags;
        return result;
    }

    private static String serializeCarouselItems(ArrayList<ResolvedMedia.Single> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        try {
            JSONArray array = new JSONArray();
            for (int i = 0; i < items.size(); i++) {
                ResolvedMedia.Single item = items.get(i);
                JSONObject object = new JSONObject();
                ResolvedMedia.Video video = asVideo(item);
                ResolvedMedia.Image image = asImage(item);
                if (video != null) {
                    if (TextUtils.isEmpty(video.videoUrl)) {
                        continue;
                    }
                    object.put("t", "v");
                    object.put("u", video.videoUrl);
                    object.put("p", video.posterUrl);
                    object.put("w", video.width);
                    object.put("h", video.height);
                } else if (image != null) {
                    if (TextUtils.isEmpty(image.imageUrl)) {
                        continue;
                    }
                    object.put("t", "i");
                    object.put("u", image.imageUrl);
                    object.put("w", image.width);
                    object.put("h", image.height);
                } else {
                    continue;
                }
                array.put(object);
            }
            return array.length() > 0 ? array.toString() : null;
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d(TAG + ": carousel serialize failed " + e.getClass().getSimpleName());
            }
            return null;
        }
    }

    private static ResolvedMedia hydrateCarouselPreview(MessagesStorage.ExternalPreviewRecord preview) {
        ArrayList<ResolvedMedia.Single> items = new ArrayList<>();
        if (!TextUtils.isEmpty(preview.extra)) {
            try {
                JSONArray array = new JSONArray(preview.extra);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    if (object == null) {
                        continue;
                    }
                    String type = object.optString("t");
                    String url = object.optString("u");
                    if (TextUtils.isEmpty(url)) {
                        continue;
                    }
                    int width = object.optInt("w", 0);
                    int height = object.optInt("h", 0);
                    if ("v".equals(type)) {
                        items.add(new ResolvedMedia.Video(url, object.optString("p"), null, null, width, height));
                    } else if ("i".equals(type)) {
                        items.add(new ResolvedMedia.Image(url, null, null, width, height));
                    }
                }
            } catch (Exception e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d(TAG + ": carousel hydrate failed " + e.getClass().getSimpleName());
                }
            }
        }
        if (!items.isEmpty()) {
            return new ResolvedMedia.Carousel(items, preview.title, preview.description);
        }
        if (!TextUtils.isEmpty(preview.mediaUrl)) {
            if (!TextUtils.isEmpty(preview.posterUrl)) {
                return new ResolvedMedia.Preview(preview.mediaUrl, preview.posterUrl, preview.title, preview.description, preview.width, preview.height);
            }
            return new ResolvedMedia.Image(preview.mediaUrl, preview.title, preview.description, preview.width, preview.height);
        }
        return new ResolvedMedia.Preview(preview.canonicalUrl, preview.posterUrl, preview.title, preview.description, preview.width, preview.height);
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
        ExternalMediaResolver resolver = null;
        if (!TextUtils.isEmpty(preview.canonicalUrl)) {
            try {
                resolver = ExternalLinkRouter.findResolver(Uri.parse(preview.canonicalUrl));
            } catch (Exception ignore) {
                resolver = null;
            }
        }
        boolean supportsDirectVideoStreaming = resolver == null || resolver.supportsDirectVideoStreaming();
        if (TextUtils.isEmpty(webPage.embed_url)) {
            webPage.embed_url = !TextUtils.isEmpty(preview.posterUrl) ? preview.posterUrl : preview.mediaUrl;
        }
        if (!supportsDirectVideoStreaming) {
            webPage.type = "photo";
            webPage.document = null;
            ExternalMediaPreviewStore.putVideo(
                preview.webPageId, preview.platform, preview.canonicalUrl,
                preview.mediaUrl, preview.posterUrl, preview.width, preview.height,
                preview.title, preview.description
            );
            return;
        }
        webPage.type = "video";
        if (webPage.document == null) {
            webPage.document = ExternalMediaPreviewStore.putVideo(
                preview.webPageId, preview.platform, preview.canonicalUrl,
                preview.mediaUrl, preview.posterUrl, preview.width, preview.height,
                preview.title, preview.description
            );
        }
    }

    private static boolean hasRenderableExternalPreview(TLRPC.WebPage webPage) {
        if (webPage == null) {
            return false;
        }
        return webPage.photo != null
            || webPage.document != null
            || !TextUtils.isEmpty(webPage.embed_url);
    }

    private static TLRPC.WebDocument createWebDocument(String url, String mimeType, int width, int height, boolean isVideo) {
        TLRPC.TL_webDocument document = new TLRPC.TL_webDocument();
        document.url = url;
        document.access_hash = 0;
        document.size = 0;
        document.mime_type = mimeType;
        document.attributes = new ArrayList<>();
        if (isVideo) {
            TLRPC.TL_documentAttributeVideo attribute = new TLRPC.TL_documentAttributeVideo();
            attribute.supports_streaming = true;
            attribute.flags |= 2;
            attribute.w = Math.max(width, 1);
            attribute.h = Math.max(height, 1);
            document.attributes.add(attribute);
        } else if (width > 0 && height > 0) {
            TLRPC.TL_documentAttributeImageSize attribute = new TLRPC.TL_documentAttributeImageSize();
            attribute.w = width;
            attribute.h = height;
            document.attributes.add(attribute);
        }
        return document;
    }

    private static String guessImageMimeType(String url) {
        String extension = ImageLoader.getHttpUrlExtension(url, "jpg");
        if ("png".equalsIgnoreCase(extension)) {
            return "image/png";
        } else if ("webp".equalsIgnoreCase(extension)) {
            return "image/webp";
        }
        return "image/jpeg";
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

    static ResolvedMedia.Single pickPreviewMedia(ResolvedMedia media) {
        ResolvedMedia.Carousel carousel = asCarousel(media);
        ResolvedMedia.Single single = asSingle(media);
        if (carousel != null) {
            ArrayList<ResolvedMedia.Single> items = carousel.items;
            if (items.isEmpty()) {
                return null;
            }
            return items.get(0);
        } else if (single != null) {
            return single;
        }
        return null;
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

    private static ResolvedMedia.Carousel asCarousel(ResolvedMedia media) {
        return media instanceof ResolvedMedia.Carousel ? (ResolvedMedia.Carousel) media : null;
    }

    private static ResolvedMedia.Single asSingle(ResolvedMedia media) {
        return media instanceof ResolvedMedia.Single ? (ResolvedMedia.Single) media : null;
    }

    private static ResolvedMedia.Image asImage(ResolvedMedia media) {
        return media instanceof ResolvedMedia.Image ? (ResolvedMedia.Image) media : null;
    }

    static final class HydratedPreview {
        final TLRPC.WebPage webPage;
        final ResolvedMedia media;

        HydratedPreview(TLRPC.WebPage webPage, ResolvedMedia media) {
            this.webPage = webPage;
            this.media = media;
        }
    }
}
