package org.telegram.messenger.browser.youtube;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.browser.external.ParsedLink;

import java.util.List;
import java.util.Locale;

public final class YouTubeLinkParser {

    static final String PLATFORM_NAME = "YouTube";

    private YouTubeLinkParser() {
    }

    public static ParsedLink parse(Uri uri) {
        if (uri == null) {
            return null;
        }
        String host = uri.getHost();
        if (TextUtils.isEmpty(host)) {
            return null;
        }
        host = host.toLowerCase(Locale.US);

        if ("youtu.be".equals(host) || "www.youtu.be".equals(host)) {
            List<String> segments = uri.getPathSegments();
            if (segments == null || segments.isEmpty()) {
                return null;
            }
            String videoId = segments.get(0);
            if (TextUtils.isEmpty(videoId)) {
                return null;
            }
            String canonicalUrl = "https://www.youtube.com/watch?v=" + videoId;
            return new ParsedLink(uri.toString(), canonicalUrl, videoId, PLATFORM_NAME);
        }

        if (!"youtube.com".equals(host) && !"www.youtube.com".equals(host) && !"m.youtube.com".equals(host)) {
            return null;
        }

        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.isEmpty()) {
            return null;
        }

        String firstSegment = segments.get(0).toLowerCase(Locale.US);

        if ("shorts".equals(firstSegment) && segments.size() >= 2) {
            String videoId = segments.get(1);
            if (TextUtils.isEmpty(videoId)) {
                return null;
            }
            String canonicalUrl = "https://www.youtube.com/shorts/" + videoId;
            return new ParsedLink(uri.toString(), canonicalUrl, videoId, PLATFORM_NAME);
        }

        if ("watch".equals(firstSegment)) {
            String videoId = uri.getQueryParameter("v");
            if (TextUtils.isEmpty(videoId)) {
                return null;
            }
            String canonicalUrl = "https://www.youtube.com/watch?v=" + videoId;
            return new ParsedLink(uri.toString(), canonicalUrl, videoId, PLATFORM_NAME);
        }

        return null;
    }
}
