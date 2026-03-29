package org.telegram.messenger.browser.external;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;

public final class ExternalMediaOpenHelper {

    public static final long EXTERNAL_STREAM_INLINE_QUERY_ID = -0x4558545052565752L;
    public static final long EXTERNAL_LOCAL_INLINE_QUERY_ID = -0x4558544C4F43414CL;
    private static final String TAG = "ExternalOpen";

    private ExternalMediaOpenHelper() {
    }

    public static boolean isExternalInlineResult(long queryId) {
        return queryId == EXTERNAL_STREAM_INLINE_QUERY_ID;
    }

    public static boolean tryOpen(Context context, Uri uri, Fallback fallback, ProgressHandle progressHandle) {
        ExternalMediaResolver resolver = ExternalLinkRouter.findResolver(uri);
        if (resolver == null || !resolver.overridesServerPreview()) {
            return false;
        }
        ParsedLink link = resolver.parseLink(uri);
        if (link == null) {
            return false;
        }

        if (progressHandle != null) {
            progressHandle.init();
        }

        Utilities.globalQueue.postRunnable(() -> {
            ResolvedMedia resolvedMedia = null;
            Throwable error = null;
            try {
                resolvedMedia = resolver.resolve(link);
            } catch (Throwable e) {
                error = e;
            }

            final ResolvedMedia finalMedia = resolvedMedia;
            final Throwable finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                boolean opened = false;
                if (finalMedia != null) {
                    opened = openResolvedMedia(context, link, finalMedia);
                }
                if (opened) {
                    FileLog.d(TAG + ": opened " + link.platformName + " " + finalMedia.getClass().getSimpleName() + " " + link.canonicalUrl);
                    if (progressHandle != null) {
                        progressHandle.end();
                    }
                    return;
                }

                if (finalError != null) {
                    Log.e(TAG, "fallback error " + link.canonicalUrl, finalError);
                    FileLog.d(TAG + ": fallback error " + finalError.getClass().getSimpleName() + " " + link.canonicalUrl);
                } else if (finalMedia != null) {
                    Log.w(TAG, "viewer open failed " + finalMedia.getClass().getSimpleName() + " " + link.canonicalUrl);
                    FileLog.d(TAG + ": viewer open failed " + link.canonicalUrl);
                } else {
                    Log.d(TAG, "fallback browser " + link.canonicalUrl);
                    FileLog.d(TAG + ": fallback browser " + link.canonicalUrl);
                }

                if (progressHandle != null) {
                    progressHandle.end();
                }
                if (fallback != null) {
                    fallback.run(link.getCanonicalUri());
                }
            });
        });
        return true;
    }

    public static boolean openResolved(Context context, Uri uri, ResolvedMedia media) {
        ExternalMediaResolver resolver = ExternalLinkRouter.findResolver(uri);
        if (resolver == null || media == null) {
            return false;
        }
        ParsedLink link = resolver.parseLink(uri);
        if (link == null) {
            return false;
        }
        return openResolvedMedia(context, link, media);
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
        TLRPC.BotInlineResult result = createVideoInlineResult(preview);
        if (result == null) {
            return false;
        }
        entries.add(result);
        boolean opened = photoViewer.openPhotoForSelect(entries, 0, PhotoViewer.SELECT_TYPE_NO_SELECT, false, new ExternalPhotoViewerProvider(preview.sourceName, 1), null);
        if (opened && !TextUtils.isEmpty(preview.sourceName)) {
            photoViewer.setTitle(preview.sourceName);
        }
        return opened;
    }

    private static boolean openResolvedMedia(Context context, ParsedLink link, ResolvedMedia media) {
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
            entries, 0, PhotoViewer.SELECT_TYPE_NO_SELECT, false,
            new ExternalPhotoViewerProvider(link.platformName, entries.size()), null
        );
        if (opened) {
            photoViewer.setTitle(link.platformName);
        }
        return opened;
    }

    private static ArrayList<Object> createInlineResults(ParsedLink link, ResolvedMedia media) {
        ArrayList<Object> results;
        if (media instanceof ResolvedMedia.Carousel) {
            ArrayList<ResolvedMedia.Single> items = ((ResolvedMedia.Carousel) media).items;
            results = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                TLRPC.BotInlineResult result = createSingleInlineResult(link, items.get(i), i, media.title, media.description);
                if (result != null) {
                    results.add(result);
                }
            }
        } else if (media instanceof ResolvedMedia.Single) {
            results = new ArrayList<>(1);
            TLRPC.BotInlineResult result = createSingleInlineResult(link, (ResolvedMedia.Single) media, 0, media.title, media.description);
            if (result != null) {
                results.add(result);
            }
        } else {
            results = new ArrayList<>(0);
        }
        return results;
    }

    private static TLRPC.BotInlineResult createSingleInlineResult(ParsedLink link, ResolvedMedia.Single media, int index, String title, String description) {
        TLRPC.TL_botInlineResult result = new TLRPC.TL_botInlineResult();
        String md5 = Utilities.MD5(link.canonicalUrl + "#" + index);
        result.id = md5 != null ? md5 : String.valueOf(index);
        result.query_id = EXTERNAL_STREAM_INLINE_QUERY_ID;
        result.send_message = new TLRPC.TL_botInlineMessageMediaAuto();
        result.send_message.message = link.canonicalUrl;
        result.title = !TextUtils.isEmpty(title) ? title : link.platformName;
        result.description = description;

        int flags = 2;
        if (!TextUtils.isEmpty(result.description)) {
            flags |= 4;
        }

        if (media instanceof ResolvedMedia.Video) {
            ResolvedMedia.Video video = (ResolvedMedia.Video) media;
            result.type = "video";
            result.content = createWebDocument(video.videoUrl, "video/mp4", video.width, video.height, true);
            result.url = link.canonicalUrl;
            if (!TextUtils.isEmpty(video.posterUrl)) {
                result.thumb = createWebDocument(video.posterUrl, guessImageMimeType(video.posterUrl), 0, 0, false);
                flags |= 16;
            }
            flags |= 8 | 32;
        } else if (media instanceof ResolvedMedia.Image) {
            ResolvedMedia.Image image = (ResolvedMedia.Image) media;
            result.type = "photo";
            result.content = createWebDocument(image.imageUrl, guessImageMimeType(image.imageUrl), image.width, image.height, false);
            result.thumb = result.content;
            result.url = link.canonicalUrl;
            flags |= 8 | 16 | 32;
        } else if (media instanceof ResolvedMedia.Preview) {
            ResolvedMedia.Preview preview = (ResolvedMedia.Preview) media;
            result.type = "photo";
            result.content = createWebDocument(preview.posterUrl, guessImageMimeType(preview.posterUrl), preview.width, preview.height, false);
            result.thumb = result.content;
            result.url = link.canonicalUrl;
            flags |= 8 | 16 | 32;
        } else {
            return null;
        }

        result.flags = flags;
        return result;
    }

    private static TLRPC.BotInlineResult createVideoInlineResult(ExternalMediaPreviewStore.VideoPreview preview) {
        TLRPC.TL_botInlineResult result = new TLRPC.TL_botInlineResult();
        String md5 = Utilities.MD5(preview.videoUrl);
        result.id = md5 != null ? md5 : "ext_video";
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

    static TLRPC.WebDocument createWebDocument(String url, String mimeType, int width, int height, boolean isVideo) {
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

    static String guessImageMimeType(String url) {
        String extension = ImageLoader.getHttpUrlExtension(url, "jpg");
        if ("png".equalsIgnoreCase(extension)) {
            return "image/png";
        } else if ("webp".equalsIgnoreCase(extension)) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    public interface Fallback {
        void run(Uri fallbackUri);
    }

    public interface ProgressHandle {
        void init();
        void end();
    }

    private static final class ExternalPhotoViewerProvider extends PhotoViewer.EmptyPhotoViewerProvider {
        private final String title;
        private final int count;

        private ExternalPhotoViewerProvider(String title, int count) {
            this.title = title;
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
            return title;
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
