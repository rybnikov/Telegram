package org.telegram.messenger.browser.external;

import android.content.Context;
import android.net.Uri;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.browser.instagram.InstagramMediaResolver;
import org.telegram.messenger.browser.maps.MapsMediaResolver;
import org.telegram.messenger.browser.pinterest.PinterestMediaResolver;
import org.telegram.messenger.browser.tiktok.TikTokMediaResolver;
import org.telegram.messenger.browser.twitter.TwitterMediaResolver;
import org.telegram.messenger.browser.youtube.YouTubeMediaResolver;
import org.telegram.tgnet.TLRPC;

import java.util.Locale;

public final class ExternalLinkRouter {

    private static final ExternalMediaResolver[] RESOLVERS = new ExternalMediaResolver[]{
        new InstagramMediaResolver(),
        new MapsMediaResolver(),
        new PinterestMediaResolver(),
        new TikTokMediaResolver(),
        new TwitterMediaResolver(),
        new YouTubeMediaResolver()
    };

    private ExternalLinkRouter() {
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

    public static String getInstantButtonText(CharSequence siteName, org.telegram.tgnet.TLRPC.WebPage webpage) {
        if (siteName == null) {
            return null;
        }
        String name = siteName.toString();
        String type = webpage != null ? webpage.type : null;
        if ("instagram".equalsIgnoreCase(name)) {
            if ("video".equals(type)) {
                return "INSTAGRAM REEL";
            }
            return "INSTAGRAM POST";
        } else if ("tiktok".equalsIgnoreCase(name)) {
            return "TIKTOK";
        } else if ("pinterest".equalsIgnoreCase(name)) {
            return "VIEW PINTEREST";
        }
        return name.toUpperCase();
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

    public static void requestPreviewIfNeeded(MessageObject messageObject) {
        ExternalPreviewManager.requestPreviewIfNeeded(messageObject);
    }

    public static boolean openCachedPreview(Context context, Uri uri) {
        return ExternalPreviewManager.openCachedPreview(context, uri);
    }

    public static boolean openCachedPreview(Context context, TLRPC.Message message) {
        return ExternalPreviewManager.openCachedPreview(context, message);
    }

    public static boolean tryOpen(Context context, Uri uri, ExternalMediaOpenHelper.Fallback fallback, ExternalMediaOpenHelper.ProgressHandle progressHandle) {
        return ExternalMediaOpenHelper.tryOpen(context, uri, fallback, progressHandle);
    }

    public static boolean tryOpenMessagePreview(Context context, MessageObject messageObject) {
        return ExternalPreviewManager.tryOpenMessagePreview(context, messageObject);
    }
}
