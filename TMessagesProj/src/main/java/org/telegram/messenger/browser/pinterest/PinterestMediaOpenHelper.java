package org.telegram.messenger.browser.pinterest;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;

public final class PinterestMediaOpenHelper {

    public static final long EXTERNAL_STREAM_INLINE_QUERY_ID = -0x50494e5445524553L;
    private static final String TAG = "PinterestOpen";

    private PinterestMediaOpenHelper() {
    }

    public static boolean tryOpen(Context context, Uri uri, PhotoViewerProviderFallback fallback, BrowserProgressHandle progressHandle) {
        PinterestLinkParser.ParsedLink link = PinterestLinkParser.parse(uri);
        if (link == null) {
            return false;
        }

        if (progressHandle != null) {
            progressHandle.init();
        }

        Utilities.globalQueue.postRunnable(() -> {
            PinterestMediaResolver.ResolvedMedia resolvedMedia = null;
            Throwable error = null;
            try {
                resolvedMedia = new PinterestMediaResolver().resolve(link);
            } catch (Throwable e) {
                error = e;
            }

            final PinterestMediaResolver.ResolvedMedia finalResolvedMedia = resolvedMedia;
            final Throwable finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalResolvedMedia != null && openResolvedMedia(context, link, finalResolvedMedia)) {
                    FileLog.d(TAG + ": opened internal viewer " + finalResolvedMedia.getClass().getSimpleName() + " " + link.canonicalUrl);
                    if (progressHandle != null) {
                        progressHandle.end();
                    }
                    return;
                }

                if (finalError != null) {
                    FileLog.d(TAG + ": fallback error " + finalError.getClass().getSimpleName() + " " + link.canonicalUrl);
                } else {
                    FileLog.d(TAG + ": fallback browser " + link.canonicalUrl);
                }

                if (progressHandle != null) {
                    progressHandle.end();
                }
                fallback.run(link.getCanonicalUri());
            });
        });
        return true;
    }

    public static boolean openResolved(Context context, Uri uri, PinterestMediaResolver.ResolvedMedia media) {
        PinterestLinkParser.ParsedLink link = PinterestLinkParser.parse(uri);
        if (link == null || media == null) {
            return false;
        }
        return openResolvedMedia(context, link, media);
    }

    private static boolean openResolvedMedia(Context context, PinterestLinkParser.ParsedLink link, PinterestMediaResolver.ResolvedMedia media) {
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
        TLRPC.BotInlineResult result = createInlineResult(link, media);
        if (result == null) {
            return false;
        }
        entries.add(result);

        boolean opened = photoViewer.openPhotoForSelect(
            entries,
            0,
            PhotoViewer.SELECT_TYPE_NO_SELECT,
            false,
            new PinterestPhotoViewerProvider(),
            null
        );
        if (opened) {
            photoViewer.setTitle("Pinterest");
        }
        return opened;
    }

    private static TLRPC.BotInlineResult createInlineResult(PinterestLinkParser.ParsedLink link, PinterestMediaResolver.ResolvedMedia media) {
        TLRPC.TL_botInlineResult result = new TLRPC.TL_botInlineResult();
        result.id = Utilities.MD5(link.canonicalUrl);
        result.query_id = EXTERNAL_STREAM_INLINE_QUERY_ID;
        result.send_message = new TLRPC.TL_botInlineMessageMediaAuto();
        result.send_message.message = link.canonicalUrl;
        result.title = !TextUtils.isEmpty(media.title) ? media.title : "Pinterest";
        result.description = media.description;

        int flags = 2;
        if (!TextUtils.isEmpty(result.description)) {
            flags |= 4;
        }

        if (media instanceof PinterestMediaResolver.ResolvedMedia.Video) {
            PinterestMediaResolver.ResolvedMedia.Video video = (PinterestMediaResolver.ResolvedMedia.Video) media;
            result.type = "video";
            result.content = createWebDocument(video.videoUrl, "video/mp4", video.width, video.height, true);
            result.url = link.canonicalUrl;
            if (!TextUtils.isEmpty(video.posterUrl)) {
                result.thumb = createWebDocument(video.posterUrl, guessImageMimeType(video.posterUrl), 0, 0, false);
                flags |= 16;
            }
            flags |= 8 | 32;
        } else if (media instanceof PinterestMediaResolver.ResolvedMedia.Image) {
            PinterestMediaResolver.ResolvedMedia.Image image = (PinterestMediaResolver.ResolvedMedia.Image) media;
            result.type = "photo";
            result.content = createWebDocument(image.imageUrl, guessImageMimeType(image.imageUrl), image.width, image.height, false);
            result.thumb = result.content;
            result.url = link.canonicalUrl;
            flags |= 8 | 16 | 32;
        } else {
            return null;
        }

        result.flags = flags;
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
            attribute.w = width;
            attribute.h = height;
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

    public interface PhotoViewerProviderFallback {
        void run(Uri fallbackUri);
    }

    public interface BrowserProgressHandle {
        void init();
        void end();
    }

    private static final class PinterestPhotoViewerProvider extends PhotoViewer.EmptyPhotoViewerProvider {
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
            return "Pinterest";
        }

        @Override
        public CharSequence getSubtitleFor(int index) {
            return LocaleController.getString(R.string.Open);
        }
    }
}
