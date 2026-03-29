package org.telegram.messenger.browser.pinterest;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.browser.external.ParsedLink;

import java.util.List;
import java.util.Locale;

public final class PinterestLinkParser {

    static final String PLATFORM_NAME = "Pinterest";

    private PinterestLinkParser() {
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

        if ("pin.it".equals(host) || "www.pin.it".equals(host)) {
            List<String> segments = uri.getPathSegments();
            if (segments == null || segments.isEmpty()) {
                return null;
            }
            String shortCode = segments.get(0);
            if (TextUtils.isEmpty(shortCode)) {
                return null;
            }
            String canonicalUrl = "https://pin.it/" + shortCode + "/";
            return new ParsedLink(uri.toString(), canonicalUrl, shortCode, PLATFORM_NAME);
        }

        if (!"pinterest.com".equals(host) && !"www.pinterest.com".equals(host) && !host.endsWith(".pinterest.com")) {
            return null;
        }

        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() < 2) {
            return null;
        }
        if (!"pin".equalsIgnoreCase(segments.get(0))) {
            return null;
        }

        String pinId = segments.get(1);
        if (TextUtils.isEmpty(pinId)) {
            return null;
        }
        String canonicalUrl = "https://www.pinterest.com/pin/" + pinId + "/";
        return new ParsedLink(uri.toString(), canonicalUrl, pinId, PLATFORM_NAME);
    }
}
