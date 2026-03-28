package org.telegram.messenger.browser.tiktok;

import android.os.Build;
import android.text.Html;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.external.ExternalHtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class TikTokMediaResolver {

    private static final String TAG = "TikTokResolver";
    private static final String DESKTOP_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36";
    private static final String REHYDRATION_SCRIPT_ID = "__UNIVERSAL_DATA_FOR_REHYDRATION__";
    private static final int MAX_SCRIPT_CHARS = 512 * 1024;

    public ResolvedMedia resolve(TikTokLinkParser.ParsedLink link) throws Exception {
        FetchResult fetchResult = fetchHtml(link.canonicalUrl);
        JSONObject root = extractRehydrationJson(fetchResult.html);
        if (root == null) {
            FileLog.d(TAG + ": fallback no rehydration " + link.canonicalUrl);
            return null;
        }

        JSONObject detail = root.optJSONObject("__DEFAULT_SCOPE__");
        if (detail != null) {
            detail = detail.optJSONObject("webapp.video-detail");
        }
        JSONObject itemInfo = detail != null ? detail.optJSONObject("itemInfo") : null;
        JSONObject itemStruct = itemInfo != null ? itemInfo.optJSONObject("itemStruct") : null;
        if (itemStruct == null) {
            FileLog.d(TAG + ": fallback no itemStruct " + link.canonicalUrl);
            return null;
        }

        JSONObject video = itemStruct.optJSONObject("video");
        if (video == null) {
            FileLog.d(TAG + ": fallback no video " + link.canonicalUrl);
            return null;
        }

        String sourceUrl = !TextUtils.isEmpty(fetchResult.finalUrl) ? fetchResult.finalUrl : link.canonicalUrl;
        String title = optString(detail != null ? detail.optJSONObject("shareMeta") : null, "title");
        String description = firstNonEmpty(
            optString(detail != null ? detail.optJSONObject("shareMeta") : null, "desc"),
            optString(itemStruct, "desc")
        );
        String videoUrl = pickVideoUrl(video);
        String posterUrl = firstNonEmpty(
            optString(video, "originCover"),
            optString(video, "cover"),
            optString(video, "dynamicCover"),
            optString(video, "zoomCover")
        );
        int width = video.optInt("width");
        int height = video.optInt("height");

        if (!TextUtils.isEmpty(posterUrl)) {
            if (!TextUtils.isEmpty(videoUrl)) {
                FileLog.d(TAG + ": candidate video ignored until playable path is verified " + trimForLog(videoUrl));
            }
            FileLog.d(TAG + ": poster found " + trimForLog(posterUrl));
            return new ResolvedMedia.Preview(sourceUrl, posterUrl, title, description, width, height);
        }

        FileLog.d(TAG + ": fallback no poster " + sourceUrl);
        return null;
    }

    private FetchResult fetchHtml(String url) throws IOException {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            connection.setRequestProperty("Cache-Control", "max-age=0");
            connection.setRequestProperty("DNT", "1");
            connection.setRequestProperty("Sec-GPC", "1");
            connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
            connection.setRequestProperty("User-Agent", DESKTOP_UA);
            connection.connect();

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_ACCEPTED && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                throw new IOException("Unexpected HTTP " + code);
            }

            inputStream = connection.getInputStream();
            return new FetchResult(
                ExternalHtmlUtils.readUtf8Until(inputStream, REHYDRATION_SCRIPT_ID, "</script>", MAX_SCRIPT_CHARS),
                connection.getURL() != null ? connection.getURL().toString() : url
            );
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignore) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject extractRehydrationJson(String html) throws Exception {
        String scriptContent = ExternalHtmlUtils.findScriptContentById(html, REHYDRATION_SCRIPT_ID);
        if (TextUtils.isEmpty(scriptContent)) {
            return null;
        }
        return new JSONObject(scriptContent);
    }

    private String pickVideoUrl(JSONObject video) {
        JSONObject playAddrStruct = video.optJSONObject("PlayAddrStruct");
        String url = firstHttpUrl(playAddrStruct != null ? playAddrStruct.optJSONArray("UrlList") : null);
        if (!TextUtils.isEmpty(url)) {
            FileLog.d(TAG + ": chosen candidate PlayAddrStruct");
            return url;
        }
        url = firstHttpUrl(video.optJSONArray("playAddr"));
        if (!TextUtils.isEmpty(url)) {
            FileLog.d(TAG + ": chosen candidate playAddr");
            return url;
        }
        url = optString(video, "downloadAddr");
        if (!TextUtils.isEmpty(url) && url.startsWith("http")) {
            FileLog.d(TAG + ": chosen candidate downloadAddr");
            return url;
        }
        return null;
    }

    private String firstHttpUrl(JSONArray array) {
        if (array == null) {
            return null;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i);
            if (!TextUtils.isEmpty(value) && value.startsWith("http")) {
                return value;
            }
        }
        return null;
    }

    private String optString(JSONObject object, String key) {
        if (object == null) {
            return null;
        }
        String value = object.optString(key, null);
        return decodeHtml(value);
    }

    private String firstNonEmpty(String... values) {
        for (int i = 0; i < values.length; i++) {
            if (!TextUtils.isEmpty(values[i])) {
                return values[i];
            }
        }
        return null;
    }

    private String decodeHtml(String value) {
        if (TextUtils.isEmpty(value)) {
            return value;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        return Html.fromHtml(value).toString();
    }

    private String trimForLog(String value) {
        if (TextUtils.isEmpty(value) || value.length() <= 180) {
            return value;
        }
        return value.substring(0, 180);
    }

    private static final class FetchResult {
        private final String html;
        private final String finalUrl;

        private FetchResult(String html, String finalUrl) {
            this.html = html;
            this.finalUrl = finalUrl;
        }
    }

    public abstract static class ResolvedMedia {
        public final String sourceUrl;
        public final String title;
        public final String description;

        private ResolvedMedia(String sourceUrl, String title, String description) {
            this.sourceUrl = sourceUrl;
            this.title = title;
            this.description = description;
        }

        public static final class Video extends ResolvedMedia {
            public final String videoUrl;
            public final String posterUrl;
            public final int width;
            public final int height;

            public Video(String sourceUrl, String videoUrl, String posterUrl, String title, String description, int width, int height) {
                super(sourceUrl, title, description);
                this.videoUrl = videoUrl;
                this.posterUrl = posterUrl;
                this.width = width;
                this.height = height;
            }
        }

        public static final class Preview extends ResolvedMedia {
            public final String posterUrl;
            public final int width;
            public final int height;

            public Preview(String sourceUrl, String posterUrl, String title, String description, int width, int height) {
                super(sourceUrl, title, description);
                this.posterUrl = posterUrl;
                this.width = width;
                this.height = height;
            }
        }
    }
}
