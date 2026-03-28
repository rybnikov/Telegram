package org.telegram.messenger.browser.instagram;

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

public final class InstagramMediaOpenHelper {

    public static final long EXTERNAL_STREAM_INLINE_QUERY_ID = -0x494e5354414c4f4cL;
    private static final String TAG = "InstagramOpen";

    private InstagramMediaOpenHelper() {
    }

    public static boolean tryOpen(Context context, Uri uri, PhotoViewerProviderFallback fallback, BrowserProgressHandle progressHandle) {
        InstagramLinkParser.ParsedLink link = InstagramLinkParser.parse(uri);
        if (link == null) {
            return false;
        }

        if (progressHandle != null) {
            progressHandle.init();
        }

        Utilities.globalQueue.postRunnable(() -> {
            InstagramMediaResolver.ResolvedMedia resolvedMedia = null;
            Throwable error = null;
            try {
                resolvedMedia = new InstagramMediaResolver().resolve(link);
            } catch (Throwable e) {
                error = e;
            }

            final InstagramMediaResolver.ResolvedMedia finalResolvedMedia = resolvedMedia;
            final Throwable finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalResolvedMedia != null && openResolvedMedia(context, link, finalResolvedMedia)) {
                    FileLog.d(TAG + ": opened internal viewer " + finalResolvedMedia.getClass().getSimpleName() + " " + link.canonicalUrl);
                    if (progressHandle != null) {
                        progressHandle.end();
                    }
                    return;
                }

                if (finalResolvedMedia != null) {
                    FileLog.d(TAG + ": viewer open failed " + finalResolvedMedia.getClass().getSimpleName() + " " + link.canonicalUrl);
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

    public static boolean openResolved(Context context, Uri uri, InstagramMediaResolver.ResolvedMedia media) {
        InstagramLinkParser.ParsedLink link = InstagramLinkParser.parse(uri);
        if (link == null || media == null) {
            return false;
        }
        return openResolvedMedia(context, link, media);
    }

    private static boolean openResolvedMedia(Context context, InstagramLinkParser.ParsedLink link, InstagramMediaResolver.ResolvedMedia media) {
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

        ArrayList<Object> entries = createInlineResults(link, media);
        if (entries.isEmpty()) {
            return false;
        }

        boolean opened = photoViewer.openPhotoForSelect(
            entries,
            0,
            PhotoViewer.SELECT_TYPE_NO_SELECT,
            false,
            new InstagramPhotoViewerProvider(entries.size()),
            null
        );
        if (opened) {
            photoViewer.setTitle("Instagram");
        }
        return opened;
    }

    private static ArrayList<Object> createInlineResults(InstagramLinkParser.ParsedLink link, InstagramMediaResolver.ResolvedMedia media) {
        ArrayList<Object> results;
        if (media instanceof InstagramMediaResolver.ResolvedMedia.Carousel) {
            ArrayList<InstagramMediaResolver.ResolvedMedia.Single> items = ((InstagramMediaResolver.ResolvedMedia.Carousel) media).items;
            results = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                TLRPC.BotInlineResult result = createInlineResult(link, items.get(i), i, media.title, media.description);
                if (result != null) {
                    results.add(result);
                }
            }
        } else if (media instanceof InstagramMediaResolver.ResolvedMedia.Single) {
            results = new ArrayList<>(1);
            TLRPC.BotInlineResult result = createInlineResult(link, (InstagramMediaResolver.ResolvedMedia.Single) media, 0, media.title, media.description);
            if (result != null) {
                results.add(result);
            }
        } else {
            results = new ArrayList<>(0);
        }
        return results;
    }

    private static TLRPC.BotInlineResult createInlineResult(InstagramLinkParser.ParsedLink link, InstagramMediaResolver.ResolvedMedia.Single media, int index, String title, String description) {
        TLRPC.TL_botInlineResult result = new TLRPC.TL_botInlineResult();
        result.id = Utilities.MD5(link.canonicalUrl + "#" + index);
        result.query_id = EXTERNAL_STREAM_INLINE_QUERY_ID;
        result.send_message = new TLRPC.TL_botInlineMessageMediaAuto();
        result.send_message.message = link.canonicalUrl;
        result.title = !TextUtils.isEmpty(title) ? title : "Instagram";
        result.description = description;

        int flags = 2;
        if (!TextUtils.isEmpty(result.description)) {
            flags |= 4;
        }

        if (media instanceof InstagramMediaResolver.ResolvedMedia.Video) {
            InstagramMediaResolver.ResolvedMedia.Video video = (InstagramMediaResolver.ResolvedMedia.Video) media;
            result.type = "video";
            result.content = createWebDocument(video.videoUrl, "video/mp4", video.width, video.height, true);
            result.url = link.canonicalUrl;
            if (!TextUtils.isEmpty(video.posterUrl)) {
                result.thumb = createWebDocument(video.posterUrl, guessImageMimeType(video.posterUrl), 0, 0, false);
                flags |= 16;
            }
            flags |= 8 | 32;
        } else if (media instanceof InstagramMediaResolver.ResolvedMedia.Image) {
            InstagramMediaResolver.ResolvedMedia.Image image = (InstagramMediaResolver.ResolvedMedia.Image) media;
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

    private static final class InstagramPhotoViewerProvider extends PhotoViewer.EmptyPhotoViewerProvider {
        private final int count;

        private InstagramPhotoViewerProvider(int count) {
            this.count = count;
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
            return "Instagram";
        }

        @Override
        public CharSequence getSubtitleFor(int index) {
            if (count > 1) {
                return (index + 1) + " / " + count;
            }
            return LocaleController.getString(R.string.Open);
        }
    }
}
