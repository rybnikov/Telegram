package org.telegram.messenger.browser.instagram;

import android.os.Build;
import android.text.Html;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstagramMediaResolver {

    private static final String TAG = "InstagramResolver";
    private static final String DESKTOP_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36";
    private static final String WEB_INFO_MARKER = "\"xdt_api__v1__media__shortcode__web_info\"";
    private static final String ITEMS_KEY = "\"items\":";

    private static final Pattern META_TAG_PATTERN = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_PATTERN = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(['\"])(.*?)\\2", Pattern.DOTALL);
    private static final Pattern SCRIPT_TAG_PATTERN = Pattern.compile("<script\\b[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public ResolvedMedia resolve(InstagramLinkParser.ParsedLink link) throws IOException {
        String html = fetchHtml(link.canonicalUrl);
        HtmlMetadata metadata = extractMetadata(link, html);

        if (metadata.carouselItems != null && !metadata.carouselItems.isEmpty()) {
            FileLog.d(TAG + ": carousel resolved count=" + metadata.carouselItems.size() + " " + link.canonicalUrl);
            return new ResolvedMedia.Carousel(metadata.carouselItems, metadata.title, metadata.description);
        }

        if (metadata.primaryItem instanceof ResolvedMedia.Video) {
            return metadata.primaryItem;
        }

        if (metadata.primaryItem instanceof ResolvedMedia.Image) {
            return metadata.primaryItem;
        }

        if (metadata.isCarousel) {
            FileLog.d(TAG + ": fallback carousel " + link.canonicalUrl);
            return null;
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
            connection.setRequestProperty("Priority", "u=0, i");
            connection.setRequestProperty("Sec-GPC", "1");
            connection.setRequestProperty("Sec-CH-Prefers-Color-Scheme", "light");
            connection.setRequestProperty("Sec-CH-UA", "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"");
            connection.setRequestProperty("Sec-CH-UA-Full-Version-List", "\"Not(A:Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"144.0.7559.110\", \"Google Chrome\";v=\"144.0.7559.110\"");
            connection.setRequestProperty("Sec-CH-UA-Mobile", "?0");
            connection.setRequestProperty("Sec-CH-UA-Model", "\"\"");
            connection.setRequestProperty("Sec-CH-UA-Platform", "\"macOS\"");
            connection.setRequestProperty("Sec-CH-UA-Platform-Version", "\"15.7.3\"");
            connection.setRequestProperty("Sec-Fetch-Dest", "document");
            connection.setRequestProperty("Sec-Fetch-Mode", "navigate");
            connection.setRequestProperty("Sec-Fetch-Site", "none");
            connection.setRequestProperty("Sec-Fetch-User", "?1");
            connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
            connection.setRequestProperty("User-Agent", DESKTOP_UA);
            connection.connect();

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_ACCEPTED && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                throw new IOException("Unexpected HTTP " + code);
            }

            inputStream = connection.getInputStream();
            return readStream(inputStream);
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

    private HtmlMetadata extractMetadata(InstagramLinkParser.ParsedLink link, String html) {
        String title = findMetaContent(html, "property", "og:title");
        String description = findMetaContent(html, "name", "description");
        JSONObject mediaObject = extractPrimaryMediaObject(html);

        ResolvedMedia.Single primaryItem = null;
        ArrayList<ResolvedMedia.Single> carouselItems = null;
        boolean isCarousel = link.pathType == InstagramLinkParser.PathType.POST && looksLikeCarousel(html);

        if (mediaObject != null) {
            primaryItem = parseMediaItem(mediaObject);
            JSONArray carouselMedia = mediaObject.optJSONArray("carousel_media");
            if (carouselMedia != null && carouselMedia.length() > 0) {
                carouselItems = new ArrayList<>();
                for (int i = 0; i < carouselMedia.length(); i++) {
                    JSONObject item = carouselMedia.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    ResolvedMedia.Single mediaItem = parseMediaItem(item);
                    if (mediaItem != null) {
                        carouselItems.add(mediaItem);
                    }
                }
                isCarousel = !carouselItems.isEmpty();
            }
        }

        if (primaryItem == null) {
            String imageUrl = findMetaContent(html, "property", "og:image");
            if (TextUtils.isEmpty(imageUrl)) {
                imageUrl = findMetaContent(html, "property", "og:image:secure_url");
            }

            String ogVideo = findMetaContent(html, "property", "og:video");
            if (TextUtils.isEmpty(ogVideo)) {
                ogVideo = findMetaContent(html, "property", "og:video:secure_url");
            }

            if (!TextUtils.isEmpty(ogVideo)) {
                FileLog.d(TAG + ": fallback meta video " + trimForLog(ogVideo));
                primaryItem = new ResolvedMedia.Video(ogVideo, imageUrl, title, description, 0, 0);
            } else if (!TextUtils.isEmpty(imageUrl)) {
                primaryItem = new ResolvedMedia.Image(imageUrl, title, description, 0, 0);
            }
        }

        return new HtmlMetadata(primaryItem, carouselItems, title, description, isCarousel);
    }

    private JSONObject extractPrimaryMediaObject(String html) {
        Matcher scripts = SCRIPT_TAG_PATTERN.matcher(html);
        while (scripts.find()) {
            String script = scripts.group(1);
            if (TextUtils.isEmpty(script) || !script.contains(WEB_INFO_MARKER)) {
                continue;
            }
            JSONObject mediaObject = extractPrimaryMediaObjectFromScript(script);
            if (mediaObject != null) {
                return mediaObject;
            }
        }
        FileLog.d(TAG + ": marker not found");
        return null;
    }

    private JSONObject extractPrimaryMediaObjectFromScript(String script) {
        int markerIndex = script.indexOf(WEB_INFO_MARKER);
        if (markerIndex == -1) {
            return null;
        }
        int itemsKeyIndex = script.indexOf(ITEMS_KEY, markerIndex);
        if (itemsKeyIndex == -1) {
            return null;
        }
        int arrayStart = script.indexOf('[', itemsKeyIndex);
        if (arrayStart == -1) {
            return null;
        }
        int arrayEnd = findMatching(script, arrayStart, '[', ']');
        if (arrayEnd == -1) {
            return null;
        }
        try {
            JSONArray items = new JSONArray(script.substring(arrayStart, arrayEnd + 1));
            return items.optJSONObject(0);
        } catch (Exception e) {
            FileLog.d(TAG + ": failed to parse media json " + e.getClass().getSimpleName());
            return null;
        }
    }

    private ResolvedMedia.Single parseMediaItem(JSONObject mediaObject) {
        String imageUrl = extractBestImageCandidate(mediaObject);
        VideoCandidate videoCandidate = parseVideoCandidate(mediaObject.optJSONArray("video_versions"));

        String title = null;
        String description = null;
        int width = mediaObject.optInt("original_width", 0);
        int height = mediaObject.optInt("original_height", 0);

        if (videoCandidate != null) {
            return new ResolvedMedia.Video(
                videoCandidate.url,
                imageUrl,
                title,
                description,
                videoCandidate.width > 0 ? videoCandidate.width : width,
                videoCandidate.height > 0 ? videoCandidate.height : height
            );
        }

        if (!TextUtils.isEmpty(imageUrl)) {
            return new ResolvedMedia.Image(imageUrl, title, description, width, height);
        }

        return null;
    }

    private String extractBestImageCandidate(JSONObject mediaObject) {
        JSONObject imageVersions = mediaObject.optJSONObject("image_versions2");
        if (imageVersions != null) {
            JSONArray candidates = imageVersions.optJSONArray("candidates");
            if (candidates != null) {
                JSONObject best = null;
                int bestArea = -1;
                for (int i = 0; i < candidates.length(); i++) {
                    JSONObject item = candidates.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String url = item.optString("url");
                    if (TextUtils.isEmpty(url) || !url.startsWith("https://")) {
                        continue;
                    }
                    int width = item.optInt("width", 0);
                    int height = item.optInt("height", 0);
                    int area = width * height;
                    if (best == null || area > bestArea) {
                        best = item;
                        bestArea = area;
                    }
                }
                if (best != null) {
                    return best.optString("url");
                }
            }
        }

        String displayUri = mediaObject.optString("display_uri");
        if (!TextUtils.isEmpty(displayUri) && displayUri.startsWith("https://")) {
            return displayUri;
        }
        return null;
    }

    private VideoCandidate parseVideoCandidate(JSONArray array) {
        if (array == null || array.length() == 0) {
            return null;
        }

        VideoCandidate best = null;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String url = item.optString("url");
            if (TextUtils.isEmpty(url) || !url.startsWith("https://") || !url.contains(".mp4")) {
                continue;
            }
            String lowerUrl = url.toLowerCase(Locale.US);
            if (lowerUrl.contains("audio")) {
                continue;
            }
            VideoCandidate candidate = new VideoCandidate(
                url,
                item.optInt("width", 0),
                item.optInt("height", 0),
                item.optInt("type", 0)
            );
            if (best == null || isBetterCandidate(candidate, best)) {
                best = candidate;
            }
        }

        if (best != null) {
            FileLog.d(
                TAG + ": mp4 found selected=" + best.width + "x" + best.height +
                    " type=" + best.type +
                    " url=" + trimForLog(best.url)
            );
        }
        return best;
    }

    private String findMetaContent(String html, String key, String expectedValue) {
        Matcher matcher = META_TAG_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attrs = parseAttributes(matcher.group());
            String actualValue = attrs.get(key);
            if (actualValue == null || !expectedValue.equalsIgnoreCase(actualValue)) {
                continue;
            }
            String content = attrs.get("content");
            if (!TextUtils.isEmpty(content)) {
                return decodeHtml(content);
            }
        }
        return null;
    }

    private Map<String, String> parseAttributes(String tag) {
        HashMap<String, String> attrs = new HashMap<>();
        Matcher matcher = ATTR_PATTERN.matcher(tag);
        while (matcher.find()) {
            attrs.put(matcher.group(1).toLowerCase(Locale.US), matcher.group(3));
        }
        return attrs;
    }

    private boolean looksLikeCarousel(String html) {
        String lower = html.toLowerCase(Locale.US);
        return lower.contains("sidecar") || lower.contains("carousel");
    }

    private int findMatching(String value, int start, char open, char close) {
        int depth = 0;
        boolean escaped = false;
        boolean inString = false;
        for (int i = start; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean isBetterCandidate(VideoCandidate candidate, VideoCandidate best) {
        int candidateArea = candidate.width * candidate.height;
        int bestArea = best.width * best.height;
        if (candidateArea != bestArea) {
            return candidateArea > bestArea;
        }
        return candidate.type > best.type;
    }

    private String readStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toString(StandardCharsets.UTF_8.name());
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

    private static final class HtmlMetadata {
        final ResolvedMedia.Single primaryItem;
        final ArrayList<ResolvedMedia.Single> carouselItems;
        final String title;
        final String description;
        final boolean isCarousel;

        HtmlMetadata(
            ResolvedMedia.Single primaryItem,
            ArrayList<ResolvedMedia.Single> carouselItems,
            String title,
            String description,
            boolean isCarousel
        ) {
            this.primaryItem = primaryItem;
            this.carouselItems = carouselItems;
            this.title = title;
            this.description = description;
            this.isCarousel = isCarousel;
        }
    }

    private static final class VideoCandidate {
        final String url;
        final int width;
        final int height;
        final int type;

        VideoCandidate(String url, int width, int height, int type) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.type = type;
        }
    }

    public abstract static class ResolvedMedia {
        public final String title;
        public final String description;

        private ResolvedMedia(String title, String description) {
            this.title = title;
            this.description = description;
        }

        public abstract static class Single extends ResolvedMedia {
            private Single(String title, String description) {
                super(title, description);
            }
        }

        public static final class Video extends Single {
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

        public static final class Image extends Single {
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

        public static final class Carousel extends ResolvedMedia {
            public final ArrayList<Single> items;

            public Carousel(ArrayList<Single> items, String title, String description) {
                super(title, description);
                this.items = items;
            }
        }
    }
}
