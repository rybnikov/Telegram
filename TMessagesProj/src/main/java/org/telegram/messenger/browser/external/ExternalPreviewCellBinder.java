package org.telegram.messenger.browser.external;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public final class ExternalPreviewCellBinder {

    private ExternalPreviewCellBinder() {
    }

    public static ExternalMediaPreviewStore.VideoPreview getVideoPreview(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        if (!(messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaWebPage)) {
            return null;
        }
        TLRPC.WebPage webPage = ((TLRPC.TL_messageMediaWebPage) messageObject.messageOwner.media).webpage;
        return webPage == null ? null : ExternalMediaPreviewStore.getVideoPreview(webPage.id);
    }

    public static ImageLocation getVideoLocation(ExternalMediaPreviewStore.VideoPreview preview) {
        return preview == null ? null : ImageLocation.getForWebFile(preview.videoWebFile);
    }

    public static ImageLocation getPosterLocation(ExternalMediaPreviewStore.VideoPreview preview, TLRPC.PhotoSize currentPhotoObject, TLRPC.PhotoSize currentPhotoObjectThumb, TLObject photoParentObject) {
        if (preview != null && !TextUtils.isEmpty(preview.posterUrl)) {
            return ImageLocation.getForPath(preview.posterUrl);
        }
        if (preview != null && preview.posterWebFile != null) {
            return ImageLocation.getForWebFile(preview.posterWebFile);
        }
        if (currentPhotoObject != null) {
            return ImageLocation.getForObject(currentPhotoObject, photoParentObject);
        }
        if (currentPhotoObjectThumb != null) {
            return ImageLocation.getForObject(currentPhotoObjectThumb, photoParentObject);
        }
        return null;
    }

    public static ImageLocation getThumbLocation(TLRPC.PhotoSize currentPhotoObjectThumb, TLObject photoParentObject) {
        return currentPhotoObjectThumb == null ? null : ImageLocation.getForObject(currentPhotoObjectThumb, photoParentObject);
    }

    public static boolean isExternalPreviewSite(CharSequence siteName) {
        return siteName != null && ExternalLinkRouter.isExternalPreviewSite(siteName.toString());
    }

    public static String getPosterFilter(TLRPC.PhotoSize currentPhotoObject, String currentPhotoFilter, String currentPhotoFilterThumb) {
        if (currentPhotoObject instanceof TLRPC.TL_photoStrippedSize || currentPhotoObject != null && "s".equals(currentPhotoObject.type)) {
            return currentPhotoFilterThumb;
        }
        return currentPhotoFilter != null ? currentPhotoFilter : currentPhotoFilterThumb;
    }

    public static boolean videoExists(int currentAccount, ExternalMediaPreviewStore.VideoPreview preview) {
        ImageLocation videoLocation = getVideoLocation(preview);
        return videoLocation != null && FileLoader.getInstance(currentAccount).getLocalFile(videoLocation) != null;
    }

    public static void setVideoPreviewImage(
        int currentAccount,
        ImageReceiver photoImage,
        MessageObject messageObject,
        ExternalMediaPreviewStore.VideoPreview preview,
        boolean autoplay,
        TLRPC.PhotoSize currentPhotoObject,
        TLRPC.PhotoSize currentPhotoObjectThumb,
        TLObject photoParentObject,
        BitmapDrawable currentPhotoObjectThumbStripped,
        String currentPhotoFilter,
        String currentPhotoFilterThumb
    ) {
        ImageLocation posterLocation = getPosterLocation(preview, currentPhotoObject, currentPhotoObjectThumb, photoParentObject);
        ImageLocation thumbLocation = getThumbLocation(currentPhotoObjectThumb, photoParentObject);
        ImageLocation videoLocation = getVideoLocation(preview);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("poster-trace setImage autoplay=" + autoplay
                + " previewNull=" + (preview == null)
                + " posterWebFileNull=" + (preview == null || preview.posterWebFile == null)
                + " posterLocationNull=" + (posterLocation == null)
                + " videoLocationNull=" + (videoLocation == null)
                + " photoObj=" + (currentPhotoObject == null ? "null" : "set"));
        }
        String posterFilter = getPosterFilter(currentPhotoObject, currentPhotoFilter, currentPhotoFilterThumb);
        String thumbFilter = currentPhotoFilterThumb != null ? currentPhotoFilterThumb : posterFilter;
        if (autoplay) {
            if (videoLocation != null) {
                photoImage.setImage(videoLocation, ImageLoader.AUTOPLAY_FILTER, posterLocation, posterFilter, thumbLocation, thumbFilter, currentPhotoObjectThumbStripped, preview.videoWebFile != null ? preview.videoWebFile.size : 0, null, messageObject, 1);
                return;
            }
        }
        if (posterLocation != null) {
            photoImage.setImage(posterLocation, posterFilter, thumbLocation, thumbFilter, currentPhotoObjectThumbStripped, 0, null, messageObject, 1);
        } else if (thumbLocation != null || currentPhotoObjectThumbStripped != null) {
            photoImage.setImage(null, null, thumbLocation, thumbFilter, currentPhotoObjectThumbStripped, 0, null, messageObject, 0);
        } else {
            photoImage.setImageBitmap((Drawable) null);
        }
    }
}
