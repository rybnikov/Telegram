package org.telegram.messenger.browser.external;

import android.net.Uri;

import java.util.Set;

public interface ExternalMediaResolver {
    ParsedLink parseLink(Uri uri);
    ResolvedMedia resolve(ParsedLink link) throws Exception;
    String platformName();
    Set<String> siteNames();

    /**
     * If true, this resolver takes over embed/preview display in chat cells,
     * suppressing Telegram's built-in WebView embed player.
     * Only return true for platforms where Telegram's server preview is bad or missing.
     */
    default boolean overridesServerPreview() {
        return false;
    }
}
