package org.telegram.messenger.browser.instagram;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.external.ExternalHtmlUtils;
import org.telegram.messenger.browser.external.ExternalMediaResolver;
import org.telegram.messenger.browser.external.ParsedLink;
import org.telegram.messenger.browser.external.ResolvedMedia;

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
        String html = ExternalHtmlUtils.fetchHtml(link.canonicalUrl, null, null, MAX_HTML_CHARS, INSTAGRAM_HEADERS);
        return extractMedia(link, html);
    }

    private ResolvedMedia extractMedia(ParsedLink link, String html) {
        String title = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:title");
        String description = ExternalHtmlUtils.findMetaContentDecoded(html, "name", "description");
        JSONObject mediaObject = extractPrimaryMediaObject(html);

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
            return primaryItem;
        }

        // Fallback to OG meta tags
        if (primaryItem == null) {
            String imageUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:image");
            if (TextUtils.isEmpty(imageUrl)) {
                imageUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:image:secure_url");
            }

            String ogVideo = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:video");
            if (TextUtils.isEmpty(ogVideo)) {
                ogVideo = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:video:secure_url");
            }

            if (!TextUtils.isEmpty(ogVideo)) {
                FileLog.d(TAG + ": fallback meta video " + ExternalHtmlUtils.trimForLog(ogVideo));
                return new ResolvedMedia.Video(ogVideo, imageUrl, title, description, 0, 0);
            } else if (!TextUtils.isEmpty(imageUrl)) {
                return new ResolvedMedia.Image(imageUrl, title, description, 0, 0);
            }
        }

        if (isCarousel) {
            FileLog.d(TAG + ": fallback carousel " + link.canonicalUrl);
        } else {
            FileLog.d(TAG + ": fallback no media " + link.canonicalUrl);
        }
        return null;
    }

    private JSONObject extractPrimaryMediaObject(String html) {
        // Direct string search instead of regex — avoids O(n²) on 2MB HTML
        int markerIndex = html.indexOf(WEB_INFO_MARKER);
        if (markerIndex < 0) {
            FileLog.d(TAG + ": marker not found");
            return null;
        }
        return extractPrimaryMediaObjectFromHtml(html, markerIndex);
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
