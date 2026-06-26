package org.telegram.messenger.browser.external;

import android.net.Uri;

import org.telegram.tgnet.TLRPC;

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

    /**
     * If true, resolved videos can be streamed directly in ExoPlayer.
     * If false, video tap opens the source URL in browser/WebView instead.
     */
    default boolean supportsDirectVideoStreaming() {
        return true;
    }

    /**
     * Platform-specific repair hook for stale persisted video previews.
     */
    default boolean shouldRefreshResolvedVideoPreview(TLRPC.WebPage webPage, ParsedLink link) {
        return false;
    }
}
