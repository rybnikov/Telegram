package org.telegram.messenger.browser.pinterest;

import android.content.Context;
import android.net.Uri;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.browser.external.ExternalLinkHandler;
import org.telegram.messenger.browser.external.ExternalLinkRouter;
import org.telegram.tgnet.TLRPC;

public final class PinterestExternalLinkHandler implements ExternalLinkHandler {

    @Override
    public void requestPreviewIfNeeded(MessageObject messageObject) {
        PinterestPreviewManager.requestPreviewIfNeeded(messageObject);
    }

    @Override
    public boolean openCachedPreview(Context context, Uri uri) {
        return PinterestPreviewManager.openCachedPreview(context, uri);
    }

    @Override
    public boolean openCachedPreview(Context context, TLRPC.Message message) {
        return PinterestPreviewManager.openCachedPreview(context, message);
    }

    @Override
    public boolean tryOpen(Context context, Uri uri, ExternalLinkRouter.UriFallback fallback, ExternalLinkRouter.ProgressHandle progressHandle) {
        return PinterestMediaOpenHelper.tryOpen(
            context,
            uri,
            fallback::run,
            progressHandle == null ? null : new PinterestMediaOpenHelper.BrowserProgressHandle() {
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
