package org.telegram.messenger.browser.external;

import android.content.Context;
import android.net.Uri;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

public final class ExternalLinkRouter {

    private ExternalLinkRouter() {
    }

    public static ExternalMediaResolver findResolver(Uri uri) {
        return ResolverRegistry.findResolver(uri);
    }

    public static String getInstantButtonText(CharSequence siteName, org.telegram.tgnet.TLRPC.WebPage webpage) {
        if (siteName == null) {
            return null;
        }
        String name = siteName.toString();
        if ("google maps".equalsIgnoreCase(name)) {
            return null; // No button for Maps — standard link handler on click
        }
        String type = webpage != null ? webpage.type : null;
        if ("instagram".equalsIgnoreCase(name)) {
            if ("video".equals(type) || webpage != null && ExternalMediaPreviewStore.getVideoPreview(webpage.id) != null) {
                return "INSTAGRAM REEL";
            }
            return "INSTAGRAM POST";
        } else if ("tiktok".equalsIgnoreCase(name)) {
            return "TIKTOK";
        } else if ("pinterest".equalsIgnoreCase(name)) {
            return "VIEW PINTEREST";
        } else if ("youtube".equalsIgnoreCase(name)) {
            return null; // YouTube button handled separately (type 41)
        }
        return name.toUpperCase();
    }

    public static boolean isExternalPreviewSite(String siteName) {
        return ResolverRegistry.isExternalPreviewSite(siteName);
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
