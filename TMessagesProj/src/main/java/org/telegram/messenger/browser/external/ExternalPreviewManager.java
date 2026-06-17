package org.telegram.messenger.browser.external;

import android.content.Context;
import android.net.Uri;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public final class ExternalPreviewManager {

    public static final int EXTERNAL_PREVIEW_FORMAT_VERSION = PreviewRepository.EXTERNAL_PREVIEW_FORMAT_VERSION;

    private ExternalPreviewManager() {
    }

    public static void clearDebugState() {
        PreviewRepository.clearDebugState();
    }

    public static void preloadPreviews(ArrayList<MessageObject> messages) {
        PreviewRepository.preloadPreviews(messages);
    }

    public static void reprioritizePreviews(ArrayList<MessageObject> visibleMessages, ArrayList<MessageObject> lookaheadMessages) {
        PreviewRepository.reprioritizePreviews(visibleMessages, lookaheadMessages);
    }

    public static boolean applyCachedPreviewIfAvailable(MessageObject messageObject) {
        return PreviewRepository.applyCachedPreviewIfAvailable(messageObject);
    }

    public static void requestPreviewIfNeeded(MessageObject messageObject) {
        PreviewRepository.requestPreviewIfNeeded(messageObject);
    }

    public static boolean openCachedPreview(Context context, Uri uri) {
        return PreviewRepository.openCachedPreview(context, uri);
    }

    public static boolean openCachedPreview(Context context, TLRPC.Message message) {
        return PreviewRepository.openCachedPreview(context, message);
    }

    public static boolean tryOpenMessagePreview(Context context, MessageObject messageObject) {
        return PreviewRepository.tryOpenMessagePreview(context, messageObject);
    }

}
