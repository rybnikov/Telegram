package org.telegram.messenger.browser.tiktok;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.external.ExternalHtmlUtils;
import org.telegram.messenger.browser.external.ExternalMediaResolver;
import org.telegram.messenger.browser.external.ParsedLink;
import org.telegram.messenger.browser.external.ResolvedMedia;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TikTokMediaResolver implements ExternalMediaResolver {

    private static final String TAG = "TikTokResolver";
    private static final String REHYDRATION_SCRIPT_ID = "__UNIVERSAL_DATA_FOR_REHYDRATION__";
    private static final int MAX_SCRIPT_CHARS = 512 * 1024;
    private static final int MAX_HEAD_CHARS = 96 * 1024;
    private static final int MAX_OEMBED_CHARS = 16 * 1024;
    private static final Set<String> SITE_NAMES = new HashSet<>(Arrays.asList("tiktok"));

    private static final ConcurrentHashMap<String, String> videoCookies = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> videoUrls = new ConcurrentHashMap<>();

    public static String getCookiesForUrl(String url) {
        return videoCookies.get(url);
    }

    /**
     * Resolve video URL + cookies for streaming. Called on click (background thread).
     * Returns CDN video URL or null.
     */
    public static String resolveVideoForPlayback(String canonicalUrl) {
        // Check cache first — exact match by canonical URL
        String cachedVideoUrl = videoUrls.get(canonicalUrl);
        if (cachedVideoUrl != null && videoCookies.containsKey(cachedVideoUrl)) {
            return cachedVideoUrl;
        }

        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler previousHandler = CookieHandler.getDefault();
        CookieHandler.setDefault(cookieManager);
        String html = null;
        try {
            html = ExternalHtmlUtils.fetchHtml(canonicalUrl, REHYDRATION_SCRIPT_ID, "</script>", MAX_SCRIPT_CHARS);
            String scriptContent = ExternalHtmlUtils.findScriptContentById(html, REHYDRATION_SCRIPT_ID);
            if (TextUtils.isEmpty(scriptContent)) {
                logPlaybackResult(canonicalUrl, html, false, false, "null");
                ExternalHtmlUtils.dumpResolverEvidence("tt-playback", canonicalUrl, html);
                return null;
            }

            JSONObject root = new JSONObject(scriptContent);
            JSONObject detail = root.optJSONObject("__DEFAULT_SCOPE__");
            if (detail != null) detail = detail.optJSONObject("webapp.video-detail");
            JSONObject itemInfo = detail != null ? detail.optJSONObject("itemInfo") : null;
            JSONObject itemStruct = itemInfo != null ? itemInfo.optJSONObject("itemStruct") : null;
            JSONObject video = itemStruct != null ? itemStruct.optJSONObject("video") : null;
            if (video == null) {
                logPlaybackResult(canonicalUrl, html, true, false, "null");
                ExternalHtmlUtils.dumpResolverEvidence("tt-playback", canonicalUrl, html);
                return null;
            }

            String videoUrl = pickVideoUrl(video);
            if (TextUtils.isEmpty(videoUrl)) {
                logPlaybackResult(canonicalUrl, html, true, false, "null");
                ExternalHtmlUtils.dumpResolverEvidence("tt-playback", canonicalUrl, html);
                return null;
            }

            String cookies = extractCookieString(cookieManager);
            if (!TextUtils.isEmpty(cookies)) {
                videoCookies.put(videoUrl, cookies);
            }
            videoUrls.put(canonicalUrl, videoUrl);
            FileLog.d(TAG + ": playback resolved " + ExternalHtmlUtils.trimForLog(videoUrl));
            logPlaybackResult(canonicalUrl, html, true, true, "rehydration");
            return videoUrl;
        } catch (Exception e) {
            FileLog.d(TAG + ": playback resolve failed " + e.getClass().getSimpleName());
            if (html != null) {
                logPlaybackResult(canonicalUrl, html, contains(html, REHYDRATION_SCRIPT_ID), false, "error");
                ExternalHtmlUtils.dumpResolverEvidence("tt-playback", canonicalUrl, html);
            }
            return null;
        } finally {
            CookieHandler.setDefault(previousHandler);
        }
    }

    @Override
    public ParsedLink parseLink(Uri uri) {
        return TikTokLinkParser.parse(uri);
    }

    @Override
    public String platformName() {
        return "TikTok";
    }

    @Override
    public Set<String> siteNames() {
        return SITE_NAMES;
    }

    @Override
    public boolean overridesServerPreview() {
        return true;
    }

    @Override
    public boolean supportsDirectVideoStreaming() {
        return false; // Streaming requires on-click cookie fetch
    }

    @Override
    public ResolvedMedia resolve(ParsedLink link) throws Exception {
        String previewUrl = link.canonicalUrl;
        String previewHtml = null;

        if (isShortTikTokUrl(link.canonicalUrl)) {
            ExternalHtmlUtils.FetchResult fetchResult = ExternalHtmlUtils.fetchHtmlWithFinalUrl(link.canonicalUrl, null, "</head>", MAX_HEAD_CHARS);
            previewHtml = fetchResult.html;
            previewUrl = canonicalizePreviewUrl(fetchResult.finalUrl, link.canonicalUrl);
        }

        JSONObject json = fetchOEmbed(previewUrl);

        String title = json != null ? json.optString("title", null) : null;
        String author = json != null ? json.optString("author_name", null) : null;
        String posterUrl = json != null ? json.optString("thumbnail_url", null) : null;
        int width = json != null ? json.optInt("thumbnail_width", 0) : 0;
        int height = json != null ? json.optInt("thumbnail_height", 0) : 0;
        String description = !TextUtils.isEmpty(author) ? author : null;
        String branch = !TextUtils.isEmpty(posterUrl) ? "oembed" : null;

        if (TextUtils.isEmpty(posterUrl)) {
            if (previewHtml == null) {
                ExternalHtmlUtils.FetchResult fetchResult = ExternalHtmlUtils.fetchHtmlWithFinalUrl(previewUrl, null, "</head>", MAX_HEAD_CHARS);
                previewHtml = fetchResult.html;
                previewUrl = canonicalizePreviewUrl(fetchResult.finalUrl, previewUrl);
            }
            posterUrl = firstNonEmpty(
                ExternalHtmlUtils.findMetaContentDecoded(previewHtml, "property", "og:image"),
                ExternalHtmlUtils.findMetaContentDecoded(previewHtml, "name", "twitter:image")
            );
            if (!TextUtils.isEmpty(posterUrl)) {
                branch = "html-fallback";
            }
            title = firstNonEmpty(title, ExternalHtmlUtils.findMetaContentDecoded(previewHtml, "property", "og:title"));
            description = firstNonEmpty(description, ExternalHtmlUtils.findMetaContentDecoded(previewHtml, "property", "og:description"));
            if (width == 0) {
                width = ExternalHtmlUtils.parseIntSafe(ExternalHtmlUtils.findMetaContent(previewHtml, "property", "og:image:width"));
            }
            if (height == 0) {
                height = ExternalHtmlUtils.parseIntSafe(ExternalHtmlUtils.findMetaContent(previewHtml, "property", "og:image:height"));
            }
        }

        if (TextUtils.isEmpty(posterUrl)) {
            FileLog.d(TAG + ": no preview image " + link.canonicalUrl);
            logPreviewResult(link.canonicalUrl, previewUrl, previewHtml, null, posterUrl, false);
            ExternalHtmlUtils.dumpResolverEvidence("tt", previewUrl, previewHtml);
            return null;
        }

        // Return final video page URL as a marker; resolveVideoForPlayback still fetches
        // the stream from the cache key, and embed fallback extracts /video/<id> from this.
        FileLog.d(TAG + ": oEmbed preview " + ExternalHtmlUtils.trimForLog(posterUrl));
        logPreviewResult(link.canonicalUrl, previewUrl, previewHtml, branch, posterUrl, true);
        return new ResolvedMedia.Video(previewUrl, posterUrl, title, description, width, height);
    }

    private static void logPreviewResult(String sourceUrl, String previewUrl, String html, String branch, String posterUrl, boolean hasVideoUrl) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        String ogImage = html == null ? null : ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:image");
        String twitterImage = html == null ? null : ExternalHtmlUtils.findMetaContentDecoded(html, "name", "twitter:image");
        FileLog.d("resolver tt markers url=" + ExternalHtmlUtils.sanitizeForLog(sourceUrl)
            + " finalUrl=" + ExternalHtmlUtils.sanitizeForLog(previewUrl)
            + " rehydration=" + yn(contains(html, REHYDRATION_SCRIPT_ID))
            + " oembed=" + yn("oembed".equals(branch))
            + " og:image=" + yn(!TextUtils.isEmpty(ogImage))
            + " twitter:image=" + yn(!TextUtils.isEmpty(twitterImage))
            + " poster=" + yn(!TextUtils.isEmpty(posterUrl))
            + " video=" + yn(hasVideoUrl)
            + " branch=" + (branch != null ? branch : "null"));
    }

    private static void logPlaybackResult(String canonicalUrl, String html, boolean hasRehydration, boolean hasVideoUrl, String branch) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        String ogImage = html == null ? null : ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:image");
        String twitterImage = html == null ? null : ExternalHtmlUtils.findMetaContentDecoded(html, "name", "twitter:image");
        FileLog.d("resolver tt playback markers url=" + ExternalHtmlUtils.sanitizeForLog(canonicalUrl)
            + " rehydration=" + yn(hasRehydration)
            + " og:image=" + yn(!TextUtils.isEmpty(ogImage))
            + " twitter:image=" + yn(!TextUtils.isEmpty(twitterImage))
            + " poster=n"
            + " video=" + yn(hasVideoUrl)
            + " branch=" + branch);
    }

    private static boolean contains(String value, String needle) {
        return !TextUtils.isEmpty(value) && value.contains(needle);
    }

    private static String yn(boolean value) {
        return value ? "y" : "n";
    }

    private static JSONObject fetchOEmbed(String previewUrl) {
        try {
            String oembedUrl = "https://www.tiktok.com/oembed?url=" + Uri.encode(previewUrl);
            String response = ExternalHtmlUtils.fetchHtml(oembedUrl, null, null, MAX_OEMBED_CHARS);
            if (TextUtils.isEmpty(response)) {
                return null;
            }
            return new JSONObject(response);
        } catch (Exception e) {
            FileLog.d(TAG + ": oEmbed failed " + e.getClass().getSimpleName() + " " + ExternalHtmlUtils.trimForLog(previewUrl));
            return null;
        }
    }

    private static boolean isShortTikTokUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            String host = Uri.parse(url).getHost();
            if (TextUtils.isEmpty(host)) {
                return false;
            }
            host = host.toLowerCase();
            return "vm.tiktok.com".equals(host) || "www.vm.tiktok.com".equals(host)
                || "vt.tiktok.com".equals(host) || "www.vt.tiktok.com".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    private static String canonicalizePreviewUrl(String finalUrl, String fallbackUrl) {
        if (TextUtils.isEmpty(finalUrl)) {
            return fallbackUrl;
        }
        try {
            ParsedLink parsedLink = TikTokLinkParser.parse(Uri.parse(finalUrl));
            if (parsedLink != null && !TextUtils.isEmpty(parsedLink.canonicalUrl)) {
                return parsedLink.canonicalUrl;
            }
        } catch (Exception ignore) {
        }
        return finalUrl;
    }

    private static String firstNonEmpty(String first, String second) {
        return !TextUtils.isEmpty(first) ? first : second;
    }

    private static String pickVideoUrl(JSONObject video) {
        JSONObject playAddrStruct = video.optJSONObject("PlayAddrStruct");
        String url = firstHttpUrl(playAddrStruct != null ? playAddrStruct.optJSONArray("UrlList") : null);
        if (!TextUtils.isEmpty(url)) return url;
        url = firstHttpUrl(video.optJSONArray("playAddr"));
        if (!TextUtils.isEmpty(url)) return url;
        String dl = video.optString("downloadAddr", null);
        if (!TextUtils.isEmpty(dl) && dl.startsWith("http")) return dl;
        return null;
    }

    private static String firstHttpUrl(JSONArray array) {
        if (array == null) return null;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i);
            if (!TextUtils.isEmpty(value) && value.startsWith("http")) return value;
        }
        return null;
    }

    private static String extractCookieString(CookieManager cookieManager) {
        try {
            List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
            if (cookies.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cookies.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(cookies.get(i).getName()).append("=").append(cookies.get(i).getValue());
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(JSONObject object, String key) {
        if (object == null) return null;
        return object.optString(key, null);
    }
}
