package org.telegram.messenger.browser.tiktok;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
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
        try {
            String html = ExternalHtmlUtils.fetchHtml(canonicalUrl, REHYDRATION_SCRIPT_ID, "</script>", MAX_SCRIPT_CHARS);
            String scriptContent = ExternalHtmlUtils.findScriptContentById(html, REHYDRATION_SCRIPT_ID);
            if (TextUtils.isEmpty(scriptContent)) return null;

            JSONObject root = new JSONObject(scriptContent);
            JSONObject detail = root.optJSONObject("__DEFAULT_SCOPE__");
            if (detail != null) detail = detail.optJSONObject("webapp.video-detail");
            JSONObject itemInfo = detail != null ? detail.optJSONObject("itemInfo") : null;
            JSONObject itemStruct = itemInfo != null ? itemInfo.optJSONObject("itemStruct") : null;
            JSONObject video = itemStruct != null ? itemStruct.optJSONObject("video") : null;
            if (video == null) return null;

            String videoUrl = pickVideoUrl(video);
            if (TextUtils.isEmpty(videoUrl)) return null;

            String cookies = extractCookieString(cookieManager);
            if (!TextUtils.isEmpty(cookies)) {
                videoCookies.put(videoUrl, cookies);
            }
            videoUrls.put(canonicalUrl, videoUrl);
            FileLog.d(TAG + ": playback resolved " + ExternalHtmlUtils.trimForLog(videoUrl));
            return videoUrl;
        } catch (Exception e) {
            FileLog.d(TAG + ": playback resolve failed " + e.getClass().getSimpleName());
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
        // Fast path: oEmbed API (~200ms) for poster + title
        String oembedUrl = "https://www.tiktok.com/oembed?url=" + Uri.encode(link.canonicalUrl);
        String response = ExternalHtmlUtils.fetchHtml(oembedUrl, null, null, MAX_OEMBED_CHARS);
        if (TextUtils.isEmpty(response)) {
            FileLog.d(TAG + ": oEmbed empty " + link.canonicalUrl);
            return null;
        }

        JSONObject json = new JSONObject(response);
        String title = json.optString("title", null);
        String author = json.optString("author_name", null);
        String posterUrl = json.optString("thumbnail_url", null);
        int width = json.optInt("thumbnail_width", 0);
        int height = json.optInt("thumbnail_height", 0);
        String description = !TextUtils.isEmpty(author) ? author : null;

        if (TextUtils.isEmpty(posterUrl)) {
            FileLog.d(TAG + ": oEmbed no thumbnail " + link.canonicalUrl);
            return null;
        }

        // Return as Video with placeholder videoUrl — real URL resolved on click
        // The canonical URL is used as videoUrl marker; resolveVideoForPlayback replaces it
        FileLog.d(TAG + ": oEmbed preview " + ExternalHtmlUtils.trimForLog(posterUrl));
        return new ResolvedMedia.Video(link.canonicalUrl, posterUrl, title, description, width, height);
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
