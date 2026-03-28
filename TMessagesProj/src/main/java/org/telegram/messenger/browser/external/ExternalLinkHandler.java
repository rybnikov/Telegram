package org.telegram.messenger.browser.external;

import android.content.Context;
import android.net.Uri;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

public interface ExternalLinkHandler {
    void requestPreviewIfNeeded(MessageObject messageObject);
    boolean openCachedPreview(Context context, Uri uri);
    boolean openCachedPreview(Context context, TLRPC.Message message);
    boolean tryOpen(Context context, Uri uri, ExternalLinkRouter.UriFallback fallback, ExternalLinkRouter.ProgressHandle progressHandle);
}
