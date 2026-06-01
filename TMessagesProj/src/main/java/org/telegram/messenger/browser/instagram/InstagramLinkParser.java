package org.telegram.messenger.browser.instagram;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.browser.external.ParsedLink;

import java.util.List;
import java.util.Locale;

public final class InstagramLinkParser {

    static final String PLATFORM_NAME = "Instagram";

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

        ParsedLink redirectedLink = parseRedirectLink(uri, host);
        if (redirectedLink != null) {
            return redirectedLink;
        }

        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() < 2) {
            return null;
        }

        String pathSegment;
        String firstSegment = segments.get(0).toLowerCase(Locale.US);
        switch (firstSegment) {
            case "reel":
            case "reels":
                pathSegment = "reel";
                break;
            case "p":
                pathSegment = "p";
                break;
            case "tv":
                pathSegment = "tv";
                break;
            case "share":
                return parseShareLink(uri, segments);
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

        String canonicalUrl = "https://www.instagram.com/" + pathSegment + "/" + shortcode + "/";
        return new ParsedLink(uri.toString(), canonicalUrl, shortcode, PLATFORM_NAME);
    }

    private static ParsedLink parseRedirectLink(Uri uri, String host) {
        if (!"l.instagram.com".equals(host) && !"lm.instagram.com".equals(host)) {
            return null;
        }
        String target = uri.getQueryParameter("u");
        if (TextUtils.isEmpty(target) || target.equals(uri.toString())) {
            return null;
        }
        ParsedLink parsedLink = parse(Uri.parse(target));
        if (parsedLink == null) {
            return null;
        }
        return new ParsedLink(uri.toString(), parsedLink.canonicalUrl, parsedLink.id, PLATFORM_NAME);
    }

    private static ParsedLink parseShareLink(Uri uri, List<String> segments) {
        String shareId = segments.get(segments.size() - 1);
        if (TextUtils.isEmpty(shareId)) {
            return null;
        }
        shareId = shareId.trim();
        if (TextUtils.isEmpty(shareId)) {
            return null;
        }
        return new ParsedLink(uri.toString(), uri.toString(), shareId, PLATFORM_NAME);
    }
}
