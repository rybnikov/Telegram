package org.telegram.messenger.browser.external;

import android.content.Context;
import android.net.Uri;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.browser.instagram.InstagramExternalLinkHandler;
import org.telegram.messenger.browser.pinterest.PinterestExternalLinkHandler;
import org.telegram.messenger.browser.tiktok.TikTokExternalLinkHandler;
import org.telegram.tgnet.TLRPC;

public final class ExternalLinkRouter {

    private static final ExternalLinkHandler[] HANDLERS = new ExternalLinkHandler[] {
        new InstagramExternalLinkHandler(),
        new PinterestExternalLinkHandler(),
        new TikTokExternalLinkHandler()
    };

    private ExternalLinkRouter() {
    }

    public static void requestPreviewIfNeeded(MessageObject messageObject) {
        for (int i = 0; i < HANDLERS.length; i++) {
            HANDLERS[i].requestPreviewIfNeeded(messageObject);
        }
    }

    public static boolean openCachedPreview(Context context, Uri uri) {
        for (int i = 0; i < HANDLERS.length; i++) {
            if (HANDLERS[i].openCachedPreview(context, uri)) {
                return true;
            }
        }
        return false;
    }

    public static boolean openCachedPreview(Context context, TLRPC.Message message) {
        for (int i = 0; i < HANDLERS.length; i++) {
            if (HANDLERS[i].openCachedPreview(context, message)) {
                return true;
            }
        }
        return false;
    }

    public static boolean tryOpen(Context context, Uri uri, UriFallback fallback, ProgressHandle progressHandle) {
        for (int i = 0; i < HANDLERS.length; i++) {
            if (HANDLERS[i].tryOpen(context, uri, fallback, progressHandle)) {
                return true;
            }
        }
        return false;
    }

    public interface UriFallback {
        void run(Uri fallbackUri);
    }

    public interface ProgressHandle {
        void init();
        void end();
    }
}
