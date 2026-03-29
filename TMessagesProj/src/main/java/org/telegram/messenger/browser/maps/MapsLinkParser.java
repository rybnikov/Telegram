package org.telegram.messenger.browser.maps;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.browser.external.ParsedLink;

import java.util.List;
import java.util.Locale;

public final class MapsLinkParser {

    static final String PLATFORM_NAME = "Google Maps";

    private MapsLinkParser() {
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

        // Short links: maps.app.goo.gl/{shortCode}
        if ("maps.app.goo.gl".equals(host)) {
            List<String> segments = uri.getPathSegments();
            if (segments == null || segments.isEmpty()) {
                return null;
            }
            String shortCode = segments.get(0);
            if (TextUtils.isEmpty(shortCode)) {
                return null;
            }
            String url = uri.toString();
            return new ParsedLink(url, url, shortCode, PLATFORM_NAME);
        }

        // Old short links: goo.gl/maps/{shortCode}
        if ("goo.gl".equals(host) || "www.goo.gl".equals(host)) {
            List<String> segments = uri.getPathSegments();
            if (segments == null || segments.size() < 2) {
                return null;
            }
            if (!"maps".equalsIgnoreCase(segments.get(0))) {
                return null;
            }
            String shortCode = segments.get(1);
            if (TextUtils.isEmpty(shortCode)) {
                return null;
            }
            String url = uri.toString();
            return new ParsedLink(url, url, shortCode, PLATFORM_NAME);
        }

        // Full URLs: google.com/maps/..., www.google.com/maps/..., maps.google.com/...
        if ("google.com".equals(host) || "www.google.com".equals(host)) {
            List<String> segments = uri.getPathSegments();
            if (segments == null || segments.isEmpty()) {
                return null;
            }
            if (!"maps".equalsIgnoreCase(segments.get(0))) {
                return null;
            }
            String url = uri.toString();
            String id = segments.size() >= 2 ? segments.get(1) : "map";
            return new ParsedLink(url, url, id, PLATFORM_NAME);
        }

        if ("maps.google.com".equals(host)) {
            String url = uri.toString();
            List<String> segments = uri.getPathSegments();
            String id = (segments != null && !segments.isEmpty()) ? segments.get(0) : "map";
            return new ParsedLink(url, url, id, PLATFORM_NAME);
        }

        return null;
    }
}
