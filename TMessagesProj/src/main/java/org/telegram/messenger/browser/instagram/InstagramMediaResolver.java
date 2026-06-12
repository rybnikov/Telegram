package org.telegram.messenger.browser.instagram;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.external.ExternalHtmlUtils;
import org.telegram.messenger.browser.external.ExternalMediaResolver;
import org.telegram.messenger.browser.external.ParsedLink;
import org.telegram.messenger.browser.external.ResolvedMedia;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class InstagramMediaResolver implements ExternalMediaResolver {

    private static final String TAG = "InstagramResolver";
    private static final String WEB_INFO_MARKER = "\"xdt_api__v1__media__shortcode__web_info\"";
    private static final String ITEMS_KEY = "\"items\":";
    private static final String XDT_PREFIX = "\"xdt_";
    private static final String VIDEO_VERSIONS_MARKER = "\"video_versions\"";
    private static final String CAROUSEL_MEDIA_MARKER = "\"carousel_media\"";
    private static final String IMAGE_VERSIONS_MARKER = "\"image_versions2\"";
    private static final String DEBUG_DUMP_FILE_PREFIX = "resolver_dump_instagram_";
    private static final String DEBUG_DUMP_FILE_SUFFIX = ".html";
    private static final int MAX_HTML_CHARS = 2 * 1024 * 1024;
    private static final Set<String> SITE_NAMES = new HashSet<>(Arrays.asList("instagram"));

    @Override
    public ParsedLink parseLink(Uri uri) {
        return InstagramLinkParser.parse(uri);
    }

    @Override
    public String platformName() {
        return "Instagram";
    }

    @Override
    public Set<String> siteNames() {
        return SITE_NAMES;
    }

    @Override
    public boolean overridesServerPreview() {
        return true;
    }

    private static final Map<String, String> INSTAGRAM_HEADERS = new HashMap<>();
    static {
        INSTAGRAM_HEADERS.put("Sec-CH-Prefers-Color-Scheme", "light");
        INSTAGRAM_HEADERS.put("Sec-CH-UA", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"");
        INSTAGRAM_HEADERS.put("Sec-CH-UA-Mobile", "?0");
        INSTAGRAM_HEADERS.put("Sec-CH-UA-Platform", "\"macOS\"");
        INSTAGRAM_HEADERS.put("Priority", "u=0, i");
    }

    @Override
    public ResolvedMedia resolve(ParsedLink link) throws Exception {
        ExternalHtmlUtils.FetchResult fetchResult = ExternalHtmlUtils.fetchHtmlWithFinalUrl(link.canonicalUrl, null, null, MAX_HTML_CHARS, INSTAGRAM_HEADERS);
        ParsedLink resolvedLink = canonicalizeFinalUrl(link, fetchResult.finalUrl);
        saveDebugHtmlDump(resolvedLink, fetchResult.html);
        return extractMedia(resolvedLink, fetchResult.html);
    }

    private ParsedLink canonicalizeFinalUrl(ParsedLink link, String finalUrl) {
        if (TextUtils.isEmpty(finalUrl) || finalUrl.equals(link.canonicalUrl)) {
            return link;
        }
        try {
            ParsedLink parsedLink = InstagramLinkParser.parse(Uri.parse(finalUrl));
            if (parsedLink != null && !TextUtils.isEmpty(parsedLink.canonicalUrl)) {
                return new ParsedLink(link.originalUrl, parsedLink.canonicalUrl, parsedLink.id, link.platformName);
            }
        } catch (Exception ignore) {
        }
        return link;
    }

    private ResolvedMedia extractMedia(ParsedLink link, String html) {
        String title = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:title");
        String description = ExternalHtmlUtils.findMetaContentDecoded(html, "name", "description");
        String ogImage = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:image");
        if (TextUtils.isEmpty(ogImage)) {
            ogImage = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:image:secure_url");
        }
        String ogVideo = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:video");
        if (TextUtils.isEmpty(ogVideo)) {
            ogVideo = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:video:secure_url");
        }
        String[] mediaNodeAnchor = new String[1];
        JSONObject mediaObject = extractPrimaryMediaObject(link, html, mediaNodeAnchor);
        logMarkers(link, html, ogImage, ogVideo, mediaObject != null, mediaNodeAnchor[0]);

        ResolvedMedia.Single primaryItem = null;
        ArrayList<ResolvedMedia.Single> carouselItems = null;
        boolean isCarousel = looksLikeCarousel(html);

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

        if (carouselItems != null && !carouselItems.isEmpty()) {
            FileLog.d(TAG + ": carousel resolved count=" + carouselItems.size() + " " + link.canonicalUrl);
            logResult(link, "web_info", "carousel count=" + carouselItems.size());
            return new ResolvedMedia.Carousel(carouselItems, title, description);
        }

        if (primaryItem != null) {
            // Attach OG title/description if the media item doesn't have its own
            if (primaryItem instanceof ResolvedMedia.Video) {
                ResolvedMedia.Video v = (ResolvedMedia.Video) primaryItem;
                if (TextUtils.isEmpty(v.title) || TextUtils.isEmpty(v.description)) {
                    primaryItem = new ResolvedMedia.Video(v.videoUrl, v.posterUrl,
                        !TextUtils.isEmpty(v.title) ? v.title : title,
                        !TextUtils.isEmpty(v.description) ? v.description : description,
                        v.width, v.height);
                }
            } else if (primaryItem instanceof ResolvedMedia.Image) {
                ResolvedMedia.Image img = (ResolvedMedia.Image) primaryItem;
                if (TextUtils.isEmpty(img.title) || TextUtils.isEmpty(img.description)) {
                    primaryItem = new ResolvedMedia.Image(img.imageUrl,
                        !TextUtils.isEmpty(img.title) ? img.title : title,
                        !TextUtils.isEmpty(img.description) ? img.description : description,
                        img.width, img.height);
                }
            }
            logResult(link, "web_info", primaryItem instanceof ResolvedMedia.Video ? "video" : "image");
            return primaryItem;
        }

        // Fallback to OG meta tags
        if (primaryItem == null) {
            if (!TextUtils.isEmpty(ogVideo)) {
                FileLog.d(TAG + ": fallback meta video " + ExternalHtmlUtils.trimForLog(ogVideo));
                logResult(link, "og-fallback", "video");
                return new ResolvedMedia.Video(ogVideo, ogImage, title, description, 0, 0);
            } else if (!TextUtils.isEmpty(ogImage)) {
                logResult(link, "og-fallback", "image");
                return new ResolvedMedia.Image(ogImage, title, description, 0, 0);
            }
        }

        if (isCarousel) {
            FileLog.d(TAG + ": fallback carousel " + link.canonicalUrl);
        } else {
            FileLog.d(TAG + ": fallback no media " + link.canonicalUrl);
        }
        logResult(link, "null", isCarousel ? "carousel-marker" : "no-media");
        ExternalHtmlUtils.dumpResolverEvidence("ig", link.canonicalUrl, html);
        return null;
    }

    private void logMarkers(ParsedLink link, String html, String ogImage, String ogVideo, boolean nodeFound, String anchor) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        FileLog.d("resolver ig markers url=" + ExternalHtmlUtils.sanitizeForLog(link.canonicalUrl)
            + " webinfo=" + yn(contains(html, WEB_INFO_MARKER))
            + " items=" + yn(contains(html, ITEMS_KEY))
            + " carousel_media=" + yn(contains(html, CAROUSEL_MEDIA_MARKER))
            + " video_versions=" + yn(contains(html, VIDEO_VERSIONS_MARKER))
            + " og:image=" + yn(!TextUtils.isEmpty(ogImage))
            + " og:video=" + yn(!TextUtils.isEmpty(ogVideo))
            + " loginwall=" + yn(looksLikeLoginWall(html))
            + " node=" + (nodeFound ? "found" : "null")
            + " anchor=" + (!TextUtils.isEmpty(anchor) ? anchor : "none"));
        logMarkerContexts(link, html);
    }

    private void saveDebugHtmlDump(ParsedLink link, String html) {
        if (!BuildVars.DEBUG_PRIVATE_VERSION || TextUtils.isEmpty(html)) {
            return;
        }
        FileOutputStream stream = null;
        try {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            File logsDir = AndroidUtilities.getLogsDir();
            if (logsDir == null) {
                return;
            }
            File file = new File(logsDir, DEBUG_DUMP_FILE_PREFIX + buildDumpName(link) + DEBUG_DUMP_FILE_SUFFIX);
            stream = new FileOutputStream(file, false);
            stream.write(bytes);
            FileLog.d("resolver ig dump saved path=" + file.getAbsolutePath() + " bytes=" + bytes.length);
        } catch (Exception e) {
            FileLog.d("resolver ig dump failed " + e.getClass().getSimpleName());
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void logMarkerContexts(ParsedLink link, String html) {
        if (TextUtils.isEmpty(html)) {
            return;
        }
        StringBuilder xdt = new StringBuilder();
        int searchFrom = 0;
        int count = 0;
        while (count < 10) {
            int index = html.indexOf(XDT_PREFIX, searchFrom);
            if (index < 0) {
                break;
            }
            if (xdt.length() > 0) {
                xdt.append(" | ");
            }
            xdt.append(count + 1).append(":").append(cleanSnippet(html, index, Math.min(html.length(), index + XDT_PREFIX.length() + 80)));
            searchFrom = index + XDT_PREFIX.length();
            count++;
        }
        FileLog.d("resolver ig markers xdt url=" + ExternalHtmlUtils.sanitizeForLog(link.canonicalUrl)
            + " count=" + count
            + " samples=" + (xdt.length() > 0 ? xdt.toString() : "none"));

        int videoIndex = html.indexOf(VIDEO_VERSIONS_MARKER);
        if (videoIndex >= 0) {
            int start = Math.max(0, videoIndex - 300);
            int end = Math.min(html.length(), videoIndex + VIDEO_VERSIONS_MARKER.length() + 300);
            FileLog.d("resolver ig markers video_versions url=" + ExternalHtmlUtils.sanitizeForLog(link.canonicalUrl)
                + " context=" + cleanSnippet(html, start, end));
            FileLog.d("resolver ig markers video_versions_before url=" + ExternalHtmlUtils.sanitizeForLog(link.canonicalUrl)
                + " context=" + cleanSnippet(html, Math.max(0, videoIndex - 400), videoIndex));
        } else {
            FileLog.d("resolver ig markers video_versions url=" + ExternalHtmlUtils.sanitizeForLog(link.canonicalUrl) + " context=none");
        }

        int carouselIndex = html.indexOf(CAROUSEL_MEDIA_MARKER);
        if (carouselIndex >= 0) {
            int start = Math.max(0, carouselIndex - 400);
            int end = Math.min(html.length(), carouselIndex + CAROUSEL_MEDIA_MARKER.length() + 400);
            FileLog.d("resolver ig markers carousel_media url=" + ExternalHtmlUtils.sanitizeForLog(link.canonicalUrl)
                + " context=" + cleanSnippet(html, start, end));
        } else {
            FileLog.d("resolver ig markers carousel_media url=" + ExternalHtmlUtils.sanitizeForLog(link.canonicalUrl) + " context=none");
        }
    }

    private String buildDumpName(ParsedLink link) {
        String value = link != null ? link.id : null;
        if (TextUtils.isEmpty(value) && link != null) {
            value = lastPathSegment(link.canonicalUrl);
        }
        value = sanitizeDumpName(value);
        if (!TextUtils.isEmpty(value)) {
            return value;
        }
        String canonicalUrl = link != null ? link.canonicalUrl : null;
        String hash = TextUtils.isEmpty(canonicalUrl) ? null : Utilities.MD5(canonicalUrl);
        if (!TextUtils.isEmpty(hash)) {
            return hash;
        }
        return "unknown";
    }

    private String lastPathSegment(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(url);
            ArrayList<String> segments = new ArrayList<>(uri.getPathSegments());
            for (int i = segments.size() - 1; i >= 0; i--) {
                String segment = segments.get(i);
                if (!TextUtils.isEmpty(segment)) {
                    return segment;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private String sanitizeDumpName(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= 'A' && ch <= 'Z'
                || ch >= 'a' && ch <= 'z'
                || ch >= '0' && ch <= '9'
                || ch == '_' || ch == '-') {
                builder.append(ch);
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private String cleanSnippet(String value, int start, int end) {
        String snippet = value.substring(start, end)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ');
        return ExternalHtmlUtils.redactQueryStrings(snippet);
    }

    private void logResult(ParsedLink link, String branch, String extra) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        FileLog.d("resolver ig result url=" + ExternalHtmlUtils.sanitizeForLog(link.canonicalUrl)
            + " branch=" + branch
            + (TextUtils.isEmpty(extra) ? "" : " " + extra));
    }

    private boolean contains(String value, String needle) {
        return !TextUtils.isEmpty(value) && value.contains(needle);
    }

    private boolean looksLikeLoginWall(String html) {
        if (TextUtils.isEmpty(html)) {
            return false;
        }
        String lower = html.toLowerCase(Locale.US);
        return lower.contains("login") && (lower.contains("instagram") || lower.contains("accounts/login"));
    }

    private String yn(boolean value) {
        return value ? "y" : "n";
    }

    private JSONObject extractPrimaryMediaObject(ParsedLink link, String html, String[] anchorOut) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        String shortcode = link != null ? link.id : null;
        if (!TextUtils.isEmpty(shortcode)) {
            JSONObject object = extractPrimaryMediaObjectByField(html, shortcode, VIDEO_VERSIONS_MARKER, "video_versions");
            if (object != null) {
                if (anchorOut != null) {
                    anchorOut[0] = "video_versions";
                }
                return object;
            }
            object = extractPrimaryMediaObjectByField(html, shortcode, CAROUSEL_MEDIA_MARKER, "carousel_media");
            if (object != null) {
                if (anchorOut != null) {
                    anchorOut[0] = "carousel_media";
                }
                return object;
            }
            object = extractPrimaryMediaObjectByField(html, shortcode, IMAGE_VERSIONS_MARKER, "image_versions2");
            if (object != null) {
                if (anchorOut != null) {
                    anchorOut[0] = "image_versions2";
                }
                return object;
            }
        }

        if (anchorOut != null) {
            anchorOut[0] = "none";
        }
        FileLog.d(TAG + ": media node not found");
        return null;
    }

    private JSONObject extractPrimaryMediaObjectFromHtml(String html, int markerIndex) {
        if (markerIndex == -1) {
            return null;
        }
        int itemsKeyIndex = html.indexOf(ITEMS_KEY, markerIndex);
        if (itemsKeyIndex == -1) {
            return null;
        }
        int arrayStart = html.indexOf('[', itemsKeyIndex);
        if (arrayStart == -1) {
            return null;
        }
        int arrayEnd = findMatching(html, arrayStart, '[', ']');
        if (arrayEnd == -1) {
            return null;
        }
        try {
            JSONArray items = new JSONArray(html.substring(arrayStart, arrayEnd + 1));
            return items.optJSONObject(0);
        } catch (Exception e) {
            FileLog.d(TAG + ": failed to parse media json " + e.getClass().getSimpleName());
            return null;
        }
    }

    private JSONObject extractPrimaryMediaObjectByField(String html, String shortcode, String marker, String fieldName) {
        int searchFrom = 0;
        while (searchFrom < html.length()) {
            int anchorIndex = html.indexOf(marker, searchFrom);
            if (anchorIndex < 0) {
                break;
            }
            JSONObject object = extractMediaObjectOwner(html, anchorIndex, shortcode, fieldName);
            if (object != null) {
                return object;
            }
            searchFrom = anchorIndex + marker.length();
        }
        return null;
    }

    private JSONObject extractMediaObjectOwner(String html, int anchorIndex, String shortcode, String fieldName) {
        int objectStart = html.lastIndexOf('{', anchorIndex);
        while (objectStart >= 0) {
            int objectEnd = findMatching(html, objectStart, '{', '}');
            if (objectEnd >= anchorIndex) {
                String json = html.substring(objectStart, objectEnd + 1);
                try {
                    JSONObject object = new JSONObject(json);
                    if (TextUtils.equals(shortcode, object.optString("code")) && object.has(fieldName)) {
                        return object;
                    }
                } catch (Exception e) {
                    FileLog.d(TAG + ": failed to parse media node " + e.getClass().getSimpleName());
                }
            }
            objectStart = html.lastIndexOf('{', objectStart - 1);
        }
        return null;
    }

    private ResolvedMedia.Single parseMediaItem(JSONObject mediaObject) {
        String imageUrl = extractBestImageCandidate(mediaObject);
        VideoCandidate videoCandidate = parseVideoCandidate(mediaObject.optJSONArray("video_versions"));

        int width = mediaObject.optInt("original_width", 0);
        int height = mediaObject.optInt("original_height", 0);

        if (videoCandidate != null) {
            return new ResolvedMedia.Video(
                videoCandidate.url,
                imageUrl,
                null,
                null,
                videoCandidate.width > 0 ? videoCandidate.width : width,
                videoCandidate.height > 0 ? videoCandidate.height : height
            );
        }

        if (!TextUtils.isEmpty(imageUrl)) {
            return new ResolvedMedia.Image(imageUrl, null, null, width, height);
        }

        return null;
    }

    private String extractBestImageCandidate(JSONObject mediaObject) {
        JSONObject imageVersions = mediaObject.optJSONObject("image_versions2");
        if (imageVersions != null) {
            JSONArray candidates = imageVersions.optJSONArray("candidates");
            if (candidates != null) {
                JSONObject best = null;
                long bestArea = -1;
                for (int i = 0; i < candidates.length(); i++) {
                    JSONObject item = candidates.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String url = item.optString("url");
                    if (TextUtils.isEmpty(url) || !url.startsWith("https://")) {
                        continue;
                    }
                    long w = item.optInt("width", 0);
                    long h = item.optInt("height", 0);
                    long area = w * h;
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
            if (url.toLowerCase(Locale.US).contains("audio")) {
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
            FileLog.d(TAG + ": mp4 found " + best.width + "x" + best.height + " " + ExternalHtmlUtils.trimForLog(best.url));
        }
        return best;
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
        long candidateArea = (long) candidate.width * candidate.height;
        long bestArea = (long) best.width * best.height;
        if (candidateArea != bestArea) {
            return candidateArea > bestArea;
        }
        return candidate.type > best.type;
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
}
