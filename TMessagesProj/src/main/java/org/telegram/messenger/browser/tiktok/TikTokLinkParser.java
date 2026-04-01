package org.telegram.messenger.browser.tiktok;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.browser.external.ParsedLink;

import java.util.List;
import java.util.Locale;

public final class TikTokLinkParser {

    static final String PLATFORM_NAME = "TikTok";

    private TikTokLinkParser() {
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

        if ("vm.tiktok.com".equals(host) || "www.vm.tiktok.com".equals(host) || "vt.tiktok.com".equals(host) || "www.vt.tiktok.com".equals(host)) {
            List<String> segments = uri.getPathSegments();
            if (segments == null || segments.isEmpty()) {
                return null;
            }
            String shortCode = trimSegment(segments.get(0));
            if (TextUtils.isEmpty(shortCode)) {
                return null;
            }
            String canonicalUrl = "https://" + host + "/" + shortCode + "/";
            return new ParsedLink(uri.toString(), canonicalUrl, shortCode, PLATFORM_NAME);
        }

        if (!"tiktok.com".equals(host) && !"www.tiktok.com".equals(host) && !"m.tiktok.com".equals(host) && !host.endsWith(".tiktok.com")) {
            return null;
        }

        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.isEmpty()) {
            return null;
        }

        if ("t".equalsIgnoreCase(segments.get(0)) && segments.size() >= 2) {
            String shortCode = trimSegment(segments.get(1));
            if (TextUtils.isEmpty(shortCode)) {
                return null;
            }
            return new ParsedLink(uri.toString(), "https://www.tiktok.com/t/" + shortCode + "/", shortCode, PLATFORM_NAME);
        }

        if (segments.size() >= 3 && segments.get(0).startsWith("@") && "video".equalsIgnoreCase(segments.get(1))) {
            String user = trimSegment(segments.get(0));
            String videoId = trimSegment(segments.get(2));
            if (TextUtils.isEmpty(user) || TextUtils.isEmpty(videoId)) {
                return null;
            }
            String canonicalUrl = "https://www.tiktok.com/" + user + "/video/" + videoId;
            return new ParsedLink(uri.toString(), canonicalUrl, videoId, PLATFORM_NAME);
        }

        return null;
    }

    private static String trimSegment(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        String trimmed = value.trim();
        return TextUtils.isEmpty(trimmed) ? null : trimmed;
    }
}
