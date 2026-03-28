package org.telegram.messenger.browser.instagram;

import android.content.Context;
import android.net.Uri;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.browser.external.ExternalLinkHandler;
import org.telegram.messenger.browser.external.ExternalLinkRouter;
import org.telegram.tgnet.TLRPC;

public final class InstagramExternalLinkHandler implements ExternalLinkHandler {

    @Override
    public void requestPreviewIfNeeded(MessageObject messageObject) {
        InstagramPreviewManager.requestPreviewIfNeeded(messageObject);
    }

    @Override
    public boolean openCachedPreview(Context context, Uri uri) {
        return InstagramPreviewManager.openCachedPreview(context, uri);
    }

    @Override
    public boolean openCachedPreview(Context context, TLRPC.Message message) {
        return InstagramPreviewManager.openCachedPreview(context, message);
    }

    @Override
    public boolean tryOpen(Context context, Uri uri, ExternalLinkRouter.UriFallback fallback, ExternalLinkRouter.ProgressHandle progressHandle) {
        return InstagramMediaOpenHelper.tryOpen(
            context,
            uri,
            fallback::run,
            progressHandle == null ? null : new InstagramMediaOpenHelper.BrowserProgressHandle() {
                @Override
                public void init() {
                    progressHandle.init();
                }

                @Override
                public void end() {
                    progressHandle.end();
                }
            }
        );
    }
}
