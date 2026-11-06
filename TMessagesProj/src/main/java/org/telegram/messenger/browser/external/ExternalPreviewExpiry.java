package org.telegram.messenger.browser.external;

import android.net.Uri;

import java.util.Locale;

/** Expiry of signed Meta CDN URLs, independent of the preview database's retention time. */
public final class ExternalPreviewExpiry {

    private ExternalPreviewExpiry() {
    }

    public static boolean isExpired(String url) {
        return isExpired(url, System.currentTimeMillis() / 1000L);
    }

    static boolean isExpired(String url, long nowSeconds) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.US);
            if (!host.equals("fbcdn.net") && !host.endsWith(".fbcdn.net")
                    && !host.equals("cdninstagram.com") && !host.endsWith(".cdninstagram.com")) {
                return false;
            }
            // Meta's "oe" is a hexadecimal Unix timestamp. Unknown formats stay usable.
            String expiry = uri.getQueryParameter("oe");
            if (expiry == null || !expiry.matches("[0-9a-fA-F]{1,16}")) {
                return false;
            }
            return Long.parseLong(expiry, 16) <= nowSeconds;
        } catch (IllegalArgumentException ignore) {
            return false;
        }
    }

    static boolean isExpired(ResolvedMedia media) {
        return isExpired(media, System.currentTimeMillis() / 1000L);
    }

    static boolean isExpired(ResolvedMedia media, long nowSeconds) {
        if (media instanceof ResolvedMedia.Video) {
            ResolvedMedia.Video video = (ResolvedMedia.Video) media;
            return isExpired(video.videoUrl, nowSeconds) || isExpired(video.posterUrl, nowSeconds);
        }
        if (media instanceof ResolvedMedia.Image) {
            return isExpired(((ResolvedMedia.Image) media).imageUrl, nowSeconds);
        }
        if (media instanceof ResolvedMedia.Preview) {
            return isExpired(((ResolvedMedia.Preview) media).posterUrl, nowSeconds);
        }
        if (media instanceof ResolvedMedia.Carousel) {
            for (ResolvedMedia.Single item : ((ResolvedMedia.Carousel) media).items) {
                if (isExpired(item, nowSeconds)) {
                    return true;
                }
            }
        }
        return false;
    }
}
