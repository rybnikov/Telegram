package org.telegram.messenger.browser.maps;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.external.ExternalHtmlUtils;
import org.telegram.messenger.browser.external.ExternalMediaResolver;
import org.telegram.messenger.browser.external.ParsedLink;
import org.telegram.messenger.browser.external.ResolvedMedia;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class MapsMediaResolver implements ExternalMediaResolver {

    private static final String TAG = "MapsResolver";
    private static final int MAX_HEAD_CHARS = 96 * 1024;
    private static final Set<String> SITE_NAMES = new HashSet<>(Arrays.asList("google maps"));

    private static final Map<String, String> BOT_HEADERS = new HashMap<>();
    static {
        BOT_HEADERS.put("User-Agent", "TelegramBot (like TwitterBot)");
    }

    @Override
    public ParsedLink parseLink(Uri uri) {
        return MapsLinkParser.parse(uri);
    }

    @Override
    public String platformName() {
        return "Google Maps";
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
        ExternalHtmlUtils.FetchResult result = ExternalHtmlUtils.fetchHtmlWithFinalUrl(
            link.canonicalUrl, null, "</head>", MAX_HEAD_CHARS, BOT_HEADERS
        );
        String html = result.html;

        String title = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:title");
        String description = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:description");
        String imageUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:image");
        int width = ExternalHtmlUtils.parseIntSafe(ExternalHtmlUtils.findMetaContent(html, "property", "og:image:width"));
        int height = ExternalHtmlUtils.parseIntSafe(ExternalHtmlUtils.findMetaContent(html, "property", "og:image:height"));

        if (!TextUtils.isEmpty(imageUrl)) {
            FileLog.d(TAG + ": resolved " + ExternalHtmlUtils.trimForLog(imageUrl));
            return new ResolvedMedia.Image(imageUrl, title, description, width, height);
        }

        FileLog.d(TAG + ": no media for " + link.canonicalUrl);
        return null;
    }
}
