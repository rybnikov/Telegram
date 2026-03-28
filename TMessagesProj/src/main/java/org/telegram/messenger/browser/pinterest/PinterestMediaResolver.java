package org.telegram.messenger.browser.pinterest;

import android.os.Build;
import android.text.Html;
import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.external.ExternalHtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class PinterestMediaResolver {

    private static final String TAG = "PinterestResolver";
    private static final String DESKTOP_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36";
    private static final int MAX_HEAD_CHARS = 96 * 1024;

    public ResolvedMedia resolve(PinterestLinkParser.ParsedLink link) throws IOException {
        String html = fetchHtml(link.canonicalUrl);
        String title = findMetaContent(html, "property", "og:title");
        if (TextUtils.isEmpty(title)) {
            title = findMetaContent(html, "name", "twitter:title");
        }
        String description = findMetaContent(html, "property", "og:description");
        if (TextUtils.isEmpty(description)) {
            description = findMetaContent(html, "name", "description");
        }

        String imageUrl = findMetaContent(html, "property", "og:image");
        if (TextUtils.isEmpty(imageUrl)) {
            imageUrl = findMetaContent(html, "name", "twitter:image");
        }
        String videoUrl = findMetaContent(html, "property", "og:video");
        if (TextUtils.isEmpty(videoUrl)) {
            videoUrl = findMetaContent(html, "property", "og:video:secure_url");
        }

        int width = parseInt(findMetaContent(html, "property", "og:image:width"));
        int height = parseInt(findMetaContent(html, "property", "og:image:height"));

        if (!TextUtils.isEmpty(videoUrl)) {
            FileLog.d(TAG + ": video found " + trimForLog(videoUrl));
            return new ResolvedMedia.Video(videoUrl, imageUrl, title, description, width, height);
        }
        if (!TextUtils.isEmpty(imageUrl)) {
            FileLog.d(TAG + ": image found " + trimForLog(imageUrl));
            return new ResolvedMedia.Image(imageUrl, title, description, width, height);
        }

        FileLog.d(TAG + ": fallback no media " + link.canonicalUrl);
        return null;
    }

    private String fetchHtml(String url) throws IOException {
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
            return ExternalHtmlUtils.readUtf8Until(inputStream, "</head>", MAX_HEAD_CHARS);
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

    private String findMetaContent(String html, String key, String expectedValue) {
        String content = ExternalHtmlUtils.findMetaContent(html, key, expectedValue);
        if (!TextUtils.isEmpty(content)) {
            return decodeHtml(content);
        }
        return null;
    }

    private int parseInt(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception ignore) {
            return 0;
        }
    }

    private String decodeHtml(String value) {
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

    public abstract static class ResolvedMedia {
        public final String title;
        public final String description;

        private ResolvedMedia(String title, String description) {
            this.title = title;
            this.description = description;
        }

        public static final class Video extends ResolvedMedia {
            public final String videoUrl;
            public final String posterUrl;
            public final int width;
            public final int height;

            public Video(String videoUrl, String posterUrl, String title, String description, int width, int height) {
                super(title, description);
                this.videoUrl = videoUrl;
                this.posterUrl = posterUrl;
                this.width = width;
                this.height = height;
            }
        }

        public static final class Image extends ResolvedMedia {
            public final String imageUrl;
            public final int width;
            public final int height;

            public Image(String imageUrl, String title, String description, int width, int height) {
                super(title, description);
                this.imageUrl = imageUrl;
                this.width = width;
                this.height = height;
            }
        }
    }
}
