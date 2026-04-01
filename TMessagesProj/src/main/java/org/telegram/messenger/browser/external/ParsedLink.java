package org.telegram.messenger.browser.external;

import android.net.Uri;

public final class ParsedLink {
    public final String originalUrl;
    public final String canonicalUrl;
    public final String id;
    public final String platformName;

    public ParsedLink(String originalUrl, String canonicalUrl, String id, String platformName) {
        this.originalUrl = originalUrl;
        this.canonicalUrl = canonicalUrl;
        this.id = id;
        this.platformName = platformName;
    }

    public Uri getCanonicalUri() {
        return Uri.parse(canonicalUrl);
    }
}
