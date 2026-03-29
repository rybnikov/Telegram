package org.telegram.messenger.browser.twitter;

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

public final class TwitterMediaResolver implements ExternalMediaResolver {

    private static final String TAG = "TwitterResolver";
    private static final int MAX_HEAD_CHARS = 96 * 1024;
    private static final Set<String> SITE_NAMES = new HashSet<>(Arrays.asList("twitter", "x"));

    @Override
    public ParsedLink parseLink(Uri uri) {
        return TwitterLinkParser.parse(uri);
    }

    @Override
    public String platformName() {
        return "Twitter";
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
        String videoUrl = null;
        String posterUrl = null;
        String title = null;
        String description = null;
        int width = 0;
        int height = 0;

        try {
            String html = ExternalHtmlUtils.fetchHtml(link.canonicalUrl, null, "</head>", MAX_HEAD_CHARS);

            videoUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:video");
            if (TextUtils.isEmpty(videoUrl)) {
                videoUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:video:secure_url");
            }
            if (TextUtils.isEmpty(videoUrl)) {
                videoUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "name", "twitter:player:stream");
            }

            posterUrl = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:image");
            title = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:title");
            description = ExternalHtmlUtils.findMetaContentDecoded(html, "property", "og:description");
            width = ExternalHtmlUtils.parseIntSafe(ExternalHtmlUtils.findMetaContent(html, "property", "og:video:width"));
            height = ExternalHtmlUtils.parseIntSafe(ExternalHtmlUtils.findMetaContent(html, "property", "og:video:height"));
        } catch (Exception e) {
            FileLog.d(TAG + ": direct fetch failed " + e.getClass().getSimpleName());
        }

        if (TextUtils.isEmpty(videoUrl)) {
            try {
                String oembedUrl = "https://publish.twitter.com/oembed?url=" + Uri.encode(link.canonicalUrl);
                String response = ExternalHtmlUtils.fetchHtml(oembedUrl, null, null, 32 * 1024);
                if (!TextUtils.isEmpty(response)) {
                    JSONObject json = new JSONObject(response);
                    String oembedTitle = json.optString("author_name", null);
                    if (!TextUtils.isEmpty(oembedTitle) && TextUtils.isEmpty(title)) {
                        title = oembedTitle;
                    }
                }
            } catch (Exception e) {
                FileLog.d(TAG + ": oembed fallback failed " + e.getClass().getSimpleName());
            }
        }

        if (!TextUtils.isEmpty(videoUrl)) {
            FileLog.d(TAG + ": video found " + ExternalHtmlUtils.trimForLog(videoUrl));
            return new ResolvedMedia.Video(videoUrl, posterUrl, title, description, width, height);
        }

        FileLog.d(TAG + ": no video for " + link.canonicalUrl);
        return null;
    }
}
