package org.telegram.messenger.browser.external;

import android.text.TextUtils;

import org.telegram.messenger.WebFile;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ExternalMediaPreviewStore {

    private static final int MAX_CACHE_SIZE = 64;
    private static final Object lock = new Object();
    private static final LinkedHashMap<Long, VideoPreview> videoCache = new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 1.0f, true);

    private ExternalMediaPreviewStore() {
    }

    public static void putVideo(long webPageId, String sourceName, String sourceUrl, String videoUrl, String posterUrl, int width, int height, String title, String description) {
        if (webPageId == 0 || TextUtils.isEmpty(videoUrl)) {
            return;
        }
        VideoPreview preview = new VideoPreview(
            webPageId,
            sourceName,
            sourceUrl,
            videoUrl,
            posterUrl,
            width,
            height,
            title,
            description,
            buildWebFile(videoUrl, "video/mp4", width, height, true),
            buildWebFile(posterUrl, guessImageMimeType(posterUrl), width, height, false)
        );
        synchronized (lock) {
            videoCache.put(webPageId, preview);
            trimCache();
        }
    }

    public static VideoPreview getVideoPreview(long webPageId) {
        synchronized (lock) {
            return videoCache.get(webPageId);
        }
    }

    public static void clearDebugState() {
        synchronized (lock) {
            videoCache.clear();
        }
    }

    private static void trimCache() {
        while (videoCache.size() > MAX_CACHE_SIZE) {
            Map.Entry<Long, VideoPreview> entry = videoCache.entrySet().iterator().next();
            videoCache.remove(entry.getKey());
        }
    }

    private static WebFile buildWebFile(String url, String mimeType, int width, int height, boolean isVideo) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        TLRPC.TL_webDocument webDocument = new TLRPC.TL_webDocument();
        webDocument.url = url;
        webDocument.access_hash = 0;
        webDocument.size = 0;
        webDocument.mime_type = !TextUtils.isEmpty(mimeType) ? mimeType : "application/octet-stream";
        webDocument.attributes = new ArrayList<>();
        if (isVideo) {
            TLRPC.TL_documentAttributeVideo attribute = new TLRPC.TL_documentAttributeVideo();
            attribute.supports_streaming = true;
            attribute.flags |= 2;
            attribute.w = Math.max(width, 1);
            attribute.h = Math.max(height, 1);
            webDocument.attributes.add(attribute);
        } else if (width > 0 && height > 0) {
            TLRPC.TL_documentAttributeImageSize attribute = new TLRPC.TL_documentAttributeImageSize();
            attribute.w = width;
            attribute.h = height;
            webDocument.attributes.add(attribute);
        }
        return WebFile.createWithWebDocument(webDocument);
    }

    private static String guessImageMimeType(String url) {
        return PreviewMapper.guessImageMimeType(url);
    }

    public static final class VideoPreview {
        public final long webPageId;
        public final String sourceName;
        public final String sourceUrl;
        public final String videoUrl;
        public final String posterUrl;
        public final int width;
        public final int height;
        public final String title;
        public final String description;
        public final WebFile videoWebFile;
        public final WebFile posterWebFile;

        private VideoPreview(long webPageId, String sourceName, String sourceUrl, String videoUrl, String posterUrl, int width, int height, String title, String description, WebFile videoWebFile, WebFile posterWebFile) {
            this.webPageId = webPageId;
            this.sourceName = sourceName;
            this.sourceUrl = sourceUrl;
            this.videoUrl = videoUrl;
            this.posterUrl = posterUrl;
            this.width = width;
            this.height = height;
            this.title = title;
            this.description = description;
            this.videoWebFile = videoWebFile;
            this.posterWebFile = posterWebFile;
        }
    }
}
