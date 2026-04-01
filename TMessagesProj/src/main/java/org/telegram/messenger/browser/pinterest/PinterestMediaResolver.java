package org.telegram.messenger.browser.pinterest;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.external.ExternalHtmlUtils;
import org.telegram.messenger.browser.external.ExternalMediaResolver;
import org.telegram.messenger.browser.external.ParsedLink;
import org.telegram.messenger.browser.external.ResolvedMedia;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PinterestMediaResolver implements ExternalMediaResolver {

    private static final String TAG = "PinterestResolver";
    private static final int MAX_HEAD_CHARS = 96 * 1024;
    private static final Set<String> SITE_NAMES = new HashSet<>(Arrays.asList("pinterest"));

    @Override
    public ParsedLink parseLink(Uri uri) {
        return PinterestLinkParser.parse(uri);
    }

    @Override
    public String platformName() {
        return "Pinterest";
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
        String html = ExternalHtmlUtils.fetchHtml(link.canonicalUrl, null, "</head>", MAX_HEAD_CHARS);
        String title = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:title");
        if (TextUtils.isEmpty(title)) {
            title = ExternalHtmlUtils.findMetaContentDecoded(html, "name", "twitter:title");
        }
        String description = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:description");
        if (TextUtils.isEmpty(description)) {
            description = ExternalHtmlUtils.findMetaContentDecoded(html, "name", "description");
        }

        String imageUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:image");
        if (TextUtils.isEmpty(imageUrl)) {
            imageUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "name", "twitter:image");
        }
        String videoUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:video");
        if (TextUtils.isEmpty(videoUrl)) {
            videoUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:video:secure_url");
        }

        int width = ExternalHtmlUtils.parseIntSafe(ExternalHtmlUtils.findMetaContent(html, "property", "og:image:width"));
        int height = ExternalHtmlUtils.parseIntSafe(ExternalHtmlUtils.findMetaContent(html, "property", "og:image:height"));

        if (!TextUtils.isEmpty(videoUrl)) {
            FileLog.d(TAG + ": video found " + ExternalHtmlUtils.trimForLog(videoUrl));
            return new ResolvedMedia.Video(videoUrl, imageUrl, title, description, width, height);
        }
        if (!TextUtils.isEmpty(imageUrl)) {
            FileLog.d(TAG + ": image found " + ExternalHtmlUtils.trimForLog(imageUrl));
            return new ResolvedMedia.Image(imageUrl, title, description, width, height);
        }

        FileLog.d(TAG + ": fallback no media " + link.canonicalUrl);
        return null;
    }
}
