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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TikTokMediaResolver implements ExternalMediaResolver {

    private static final String TAG = "TikTokResolver";
    private static final String REHYDRATION_SCRIPT_ID = "__UNIVERSAL_DATA_FOR_REHYDRATION__";
    private static final int MAX_SCRIPT_CHARS = 512 * 1024;
    private static final Set<String> SITE_NAMES = new HashSet<>(Arrays.asList("tiktok"));

    // Cookies captured during resolve, keyed by video URL
    private static final ConcurrentHashMap<String, String> videoCookies = new ConcurrentHashMap<>();

    public static String getCookiesForUrl(String url) {
        return videoCookies.get(url);
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
    public ResolvedMedia resolve(ParsedLink link) throws Exception {
        // Use CookieManager to capture session cookies needed for video streaming
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler previousHandler = CookieHandler.getDefault();
        CookieHandler.setDefault(cookieManager);
        try {
            return resolveWithCookies(link, cookieManager);
        } finally {
            CookieHandler.setDefault(previousHandler);
        }
    }

    private ResolvedMedia resolveWithCookies(ParsedLink link, CookieManager cookieManager) throws Exception {
        ExternalHtmlUtils.FetchResult fetchResult = ExternalHtmlUtils.fetchHtmlWithFinalUrl(
            link.canonicalUrl, REHYDRATION_SCRIPT_ID, "</script>", MAX_SCRIPT_CHARS
        );
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
        String title = optStringDecoded(detail != null ? detail.optJSONObject("shareMeta") : null, "title");
        String description = firstNonEmpty(
            optStringDecoded(detail != null ? detail.optJSONObject("shareMeta") : null, "desc"),
            optStringDecoded(itemStruct, "desc")
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

        if (!TextUtils.isEmpty(videoUrl) && !TextUtils.isEmpty(posterUrl)) {
            // Capture cookies for this video URL
            String cookies = extractCookieString(cookieManager);
            if (!TextUtils.isEmpty(cookies)) {
                videoCookies.put(videoUrl, cookies);
                FileLog.d(TAG + ": cookies captured for streaming");
            }
            FileLog.d(TAG + ": video found " + ExternalHtmlUtils.trimForLog(videoUrl));
            return new ResolvedMedia.Video(videoUrl, posterUrl, title, description, width, height);
        }

        if (!TextUtils.isEmpty(posterUrl)) {
            FileLog.d(TAG + ": poster only " + ExternalHtmlUtils.trimForLog(posterUrl));
            return new ResolvedMedia.Preview(sourceUrl, posterUrl, title, description, width, height);
        }

        FileLog.d(TAG + ": fallback no poster " + sourceUrl);
        return null;
    }

    private String extractCookieString(CookieManager cookieManager) {
        try {
            List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
            if (cookies.isEmpty()) {
                return null;
            }
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
        if (!TextUtils.isEmpty(url)) return url;
        url = firstHttpUrl(video.optJSONArray("playAddr"));
        if (!TextUtils.isEmpty(url)) return url;
        url = optString(video, "downloadAddr");
        if (!TextUtils.isEmpty(url) && url.startsWith("http")) return url;
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

    private static String optString(JSONObject object, String key) {
        if (object == null) return null;
        return object.optString(key, null);
    }

    private static String optStringDecoded(JSONObject object, String key) {
        String value = optString(object, key);
        return ExternalHtmlUtils.decodeHtml(value);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (!TextUtils.isEmpty(v)) return v;
        }
        return null;
    }
}
