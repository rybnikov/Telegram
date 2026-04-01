package org.telegram.messenger.browser.twitter;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.browser.external.ParsedLink;

import java.util.List;
import java.util.Locale;

public final class TwitterLinkParser {

    static final String PLATFORM_NAME = "Twitter";

    private TwitterLinkParser() {
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

        if (!"twitter.com".equals(host)
                && !"www.twitter.com".equals(host)
                && !"mobile.twitter.com".equals(host)
                && !"x.com".equals(host)
                && !"www.x.com".equals(host)
                && !"mobile.x.com".equals(host)) {
            return null;
        }

        // Expected path: /{user}/status/{id}
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() < 3) {
            return null;
        }
        if (!"status".equalsIgnoreCase(segments.get(1))) {
            return null;
        }

        String user = segments.get(0);
        String statusId = segments.get(2);
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(statusId)) {
            return null;
        }

        String canonicalUrl = "https://x.com/" + user + "/status/" + statusId;
        return new ParsedLink(uri.toString(), canonicalUrl, statusId, PLATFORM_NAME);
    }
}
