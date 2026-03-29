package org.telegram.messenger.browser.youtube;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.external.ExternalHtmlUtils;
import org.telegram.messenger.browser.external.ExternalMediaResolver;
import org.telegram.messenger.browser.external.ParsedLink;
import org.telegram.messenger.browser.external.ResolvedMedia;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class YouTubeMediaResolver implements ExternalMediaResolver {

    private static final String TAG = "YouTubeResolver";
    private static final int MAX_RESPONSE_CHARS = 16 * 1024;
    private static final Set<String> SITE_NAMES = new HashSet<>(Arrays.asList("youtube"));

    @Override
    public ParsedLink parseLink(Uri uri) {
        return YouTubeLinkParser.parse(uri);
    }

    @Override
    public String platformName() {
        return "YouTube";
    }

    @Override
    public Set<String> siteNames() {
        return SITE_NAMES;
    }

    @Override
    public ResolvedMedia resolve(ParsedLink link) throws Exception {
        String oembedUrl = "https://www.youtube.com/oembed?url=" + Uri.encode(link.canonicalUrl) + "&format=json";
        String response = ExternalHtmlUtils.fetchHtml(oembedUrl, null, null, MAX_RESPONSE_CHARS);
        if (TextUtils.isEmpty(response)) {
            FileLog.d(TAG + ": empty oEmbed response for " + link.canonicalUrl);
            return null;
        }

        JSONObject json = new JSONObject(response);

        String title = json.optString("title", null);
        String author = json.optString("author_name", null);
        String description = !TextUtils.isEmpty(author) ? author : null;

        int width = json.optInt("thumbnail_width", 0);
        int height = json.optInt("thumbnail_height", 0);

        String thumbnailUrl = json.optString("thumbnail_url", null);
        String safeId = Uri.encode(link.id);
        if (TextUtils.isEmpty(thumbnailUrl)) {
            thumbnailUrl = "https://i.ytimg.com/vi/" + safeId + "/hqdefault.jpg";
        }

        String imageUrl = "https://i.ytimg.com/vi/" + safeId + "/maxresdefault.jpg";

        if (width <= 0 || height <= 0) {
            width = 1280;
            height = 720;
        }

        FileLog.d(TAG + ": resolved " + link.canonicalUrl + " -> " + ExternalHtmlUtils.trimForLog(imageUrl));
        return new ResolvedMedia.Image(imageUrl, title, description, width, height);
    }
}
