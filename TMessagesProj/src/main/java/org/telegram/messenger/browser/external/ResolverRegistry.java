package org.telegram.messenger.browser.external;

import android.net.Uri;

import org.telegram.messenger.browser.instagram.InstagramMediaResolver;
import org.telegram.messenger.browser.maps.MapsMediaResolver;
import org.telegram.messenger.browser.pinterest.PinterestMediaResolver;
import org.telegram.messenger.browser.tiktok.TikTokMediaResolver;
import org.telegram.messenger.browser.twitter.TwitterMediaResolver;
import org.telegram.messenger.browser.youtube.YouTubeMediaResolver;

import java.util.Locale;

public final class ResolverRegistry {

    private static final ExternalMediaResolver[] RESOLVERS = new ExternalMediaResolver[]{
        new InstagramMediaResolver(),
        new MapsMediaResolver(),
        new PinterestMediaResolver(),
        new TikTokMediaResolver(),
        new TwitterMediaResolver(),
        new YouTubeMediaResolver()
    };

    private ResolverRegistry() {
    }

    public static ExternalMediaResolver findResolver(Uri uri) {
        if (uri == null) {
            return null;
        }
        for (ExternalMediaResolver resolver : RESOLVERS) {
            if (resolver.parseLink(uri) != null) {
                return resolver;
            }
        }
        return null;
    }

    public static boolean isExternalPreviewSite(String siteName) {
        if (siteName == null) {
            return false;
        }
        String lower = siteName.toLowerCase(Locale.US);
        for (ExternalMediaResolver resolver : RESOLVERS) {
            if (resolver.overridesServerPreview() && resolver.siteNames().contains(lower)) {
                return true;
            }
        }
        return false;
    }
}
