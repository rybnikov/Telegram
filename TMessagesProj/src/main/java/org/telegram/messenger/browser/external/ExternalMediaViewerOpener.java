package org.telegram.messenger.browser.external;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;

public final class ExternalMediaViewerOpener {

    public static final long EXTERNAL_STREAM_INLINE_QUERY_ID = -0x4558545052565752L;

    private ExternalMediaViewerOpener() {
    }

    public static boolean tryOpen(Context context, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || !(messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaWebPage)) {
            return false;
        }
        TLRPC.WebPage webPage = messageObject.messageOwner.media.webpage;
        ExternalMediaPreviewStore.VideoPreview preview = webPage == null ? null : ExternalMediaPreviewStore.getVideoPreview(webPage.id);
        return preview != null && openVideoPreview(context, preview);
    }

    public static boolean openVideoPreview(Context context, ExternalMediaPreviewStore.VideoPreview preview) {
        if (preview == null || preview.videoWebFile == null) {
            return false;
        }

        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        Activity activity = fragment != null ? fragment.getParentActivity() : AndroidUtilities.getActivity(context);
        if (activity == null || activity.isFinishing()) {
            return false;
        }

        PhotoViewer photoViewer = PhotoViewer.getInstance();
        if (fragment != null) {
            photoViewer.setParentActivity(fragment);
        } else {
            photoViewer.setParentActivity(activity);
        }

        ArrayList<Object> entries = new ArrayList<>(1);
        TLRPC.BotInlineResult result = createInlineResult(preview);
        if (result == null) {
            return false;
        }
        entries.add(result);
        boolean opened = photoViewer.openPhotoForSelect(entries, 0, PhotoViewer.SELECT_TYPE_NO_SELECT, false, new ExternalPhotoViewerProvider(preview.sourceName), null);
        if (opened && !TextUtils.isEmpty(preview.sourceName)) {
            photoViewer.setTitle(preview.sourceName);
        }
        return opened;
    }

    private static TLRPC.BotInlineResult createInlineResult(ExternalMediaPreviewStore.VideoPreview preview) {
        TLRPC.TL_botInlineResult result = new TLRPC.TL_botInlineResult();
        result.id = Utilities.MD5(preview.videoUrl);
        result.query_id = EXTERNAL_STREAM_INLINE_QUERY_ID;
        result.type = "video";
        result.send_message = new TLRPC.TL_botInlineMessageMediaAuto();
        result.send_message.message = preview.sourceUrl;
        result.title = !TextUtils.isEmpty(preview.title) ? preview.title : preview.sourceName;
        result.description = preview.description;
        result.url = preview.sourceUrl;
        result.content = createWebDocument(preview.videoUrl, "video/mp4", preview.width, preview.height, true);
        if (!TextUtils.isEmpty(preview.posterUrl)) {
            result.thumb = createWebDocument(preview.posterUrl, guessImageMimeType(preview.posterUrl), preview.width, preview.height, false);
            result.flags |= 16;
        }
        result.flags |= 2 | 8 | 32;
        if (!TextUtils.isEmpty(result.description)) {
            result.flags |= 4;
        }
        return result;
    }

    private static TLRPC.WebDocument createWebDocument(String url, String mimeType, int width, int height, boolean isVideo) {
        TLRPC.TL_webDocument document = new TLRPC.TL_webDocument();
        document.url = url;
        document.access_hash = 0;
        document.size = 0;
        document.mime_type = mimeType;
        document.attributes = new ArrayList<>();
        if (isVideo) {
            TLRPC.TL_documentAttributeVideo attribute = new TLRPC.TL_documentAttributeVideo();
            attribute.supports_streaming = true;
            attribute.flags |= 2;
            attribute.w = Math.max(width, 1);
            attribute.h = Math.max(height, 1);
            document.attributes.add(attribute);
        } else if (width > 0 && height > 0) {
            TLRPC.TL_documentAttributeImageSize attribute = new TLRPC.TL_documentAttributeImageSize();
            attribute.w = width;
            attribute.h = height;
            document.attributes.add(attribute);
        }
        return document;
    }

    private static String guessImageMimeType(String url) {
        String extension = ImageLoader.getHttpUrlExtension(url, "jpg");
        if ("png".equalsIgnoreCase(extension)) {
            return "image/png";
        } else if ("webp".equalsIgnoreCase(extension)) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static final class ExternalPhotoViewerProvider extends PhotoViewer.EmptyPhotoViewerProvider {
        private final String title;

        private ExternalPhotoViewerProvider(String title) {
            this.title = title;
        }

        @Override
        public boolean allowCaption() {
            return false;
        }

        @Override
        public boolean canCaptureMorePhotos() {
            return false;
        }

        @Override
        public CharSequence getTitleFor(int index) {
            return title;
        }
    }
}
