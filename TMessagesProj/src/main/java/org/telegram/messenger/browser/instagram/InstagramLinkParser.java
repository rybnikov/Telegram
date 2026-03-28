package org.telegram.messenger.browser.instagram;

import android.net.Uri;
import android.text.TextUtils;

import java.util.List;
import java.util.Locale;

public final class InstagramLinkParser {

    private InstagramLinkParser() {
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
        if (!"instagram.com".equals(host) && !"www.instagram.com".equals(host) && !host.endsWith(".instagram.com")) {
            return null;
        }

        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() < 2) {
            return null;
        }

        PathType pathType;
        String firstSegment = segments.get(0).toLowerCase(Locale.US);
        switch (firstSegment) {
            case "reel":
                pathType = PathType.REEL;
                break;
            case "p":
                pathType = PathType.POST;
                break;
            case "tv":
                pathType = PathType.TV;
                break;
            default:
                return null;
        }

        String shortcode = segments.get(1);
        if (TextUtils.isEmpty(shortcode)) {
            return null;
        }
        shortcode = shortcode.trim();
        if (TextUtils.isEmpty(shortcode)) {
            return null;
        }

        String canonicalUrl = "https://www.instagram.com/" + pathType.segment + "/" + shortcode + "/";
        return new ParsedLink(uri.toString(), canonicalUrl, shortcode, pathType);
    }

    public enum PathType {
        REEL("reel"),
        POST("p"),
        TV("tv");

        public final String segment;

        PathType(String segment) {
            this.segment = segment;
        }
    }

    public static final class ParsedLink {
        public final String originalUrl;
        public final String canonicalUrl;
        public final String shortcode;
        public final PathType pathType;

        public ParsedLink(String originalUrl, String canonicalUrl, String shortcode, PathType pathType) {
            this.originalUrl = originalUrl;
            this.canonicalUrl = canonicalUrl;
            this.shortcode = shortcode;
            this.pathType = pathType;
        }

        public Uri getCanonicalUri() {
            return Uri.parse(canonicalUrl);
        }
    }
}
