package org.telegram.messenger.browser.external;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
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
        // Preview-only resolvers (no button, e.g. Maps) don't intercept clicks
        if (ExternalLinkRouter.getInstantButtonText(resolver.platformName(), null) == null) {
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
            Playback resolvedPlayback = null;
            Throwable error = null;
            try {
                resolvedMedia = resolver.resolve(link);
                ResolvedMedia.Video video = PreviewMapper.asVideo(resolvedMedia);
                if (video != null && resolver instanceof PlaybackResolver) {
                    resolvedPlayback = PreviewClickDispatcher.resolvePlayback((PlaybackResolver) resolver, link, video);
                }
            } catch (Throwable e) {
                error = e;
                if (resolvedMedia != null && PreviewMapper.asVideo(resolvedMedia) != null) {
                    resolvedPlayback = Playback.External.INSTANCE;
                }
            }

            final ResolvedMedia finalMedia = resolvedMedia;
            final Playback finalPlayback = resolvedPlayback;
            final Throwable finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                boolean opened = false;
                if (finalMedia != null) {
                    ResolvedMedia.Video vid = PreviewMapper.asVideo(finalMedia);
                    if (vid != null && finalPlayback != null) {
                        opened = PreviewClickDispatcher.openVideo(context, link, vid, finalPlayback, null);
                    } else {
                        opened = openResolvedMedia(context, link, finalMedia);
                    }
                }
                if (opened) {
                    FileLog.d(TAG + ": opened " + link.platformName + " " + finalMedia.getClass().getSimpleName() + " " + link.canonicalUrl);
                    if (progressHandle != null) {
                        progressHandle.end();
                    }
                    return;
                }

                if (finalError != null) {
                    FileLog.d(TAG + ": fallback error " + finalError.getClass().getSimpleName() + " " + link.canonicalUrl);
                } else if (finalMedia != null) {
                    FileLog.d(TAG + ": viewer open failed " + link.canonicalUrl);
                } else {
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
        TLRPC.BotInlineResult result = PreviewMapper.createVideoInlineResult(preview);
        if (result == null) {
            return false;
        }
        entries.add(result);
        boolean opened = photoViewer.openPhotoForSelect(entries, 0, PhotoViewer.SELECT_TYPE_NO_SELECT, false, new ExternalPhotoViewerProvider(preview.sourceName, 1), null);
        if (opened) {
            if (!TextUtils.isEmpty(preview.sourceName)) {
                photoViewer.setTitle(preview.sourceName);
            }
            String captionText = buildCaption(preview.title, preview.description);
            if (captionText != null) {
                photoViewer.setCaption(captionText);
            }
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

        ArrayList<Object> entries = PreviewMapper.createInlineResults(link, media);
        if (entries.isEmpty()) {
            return false;
        }

        boolean opened = photoViewer.openPhotoForSelect(
            entries, 0, PhotoViewer.SELECT_TYPE_NO_SELECT, false,
            new ExternalPhotoViewerProvider(link.platformName, entries.size()), null
        );
        if (opened) {
            photoViewer.setTitle(link.platformName);
            // Set caption for the viewer (title + description)
            String captionText = buildCaption(media);
            if (captionText != null) {
                photoViewer.setCaption(captionText);
            }
        }
        return opened;
    }

    private static String buildCaption(ResolvedMedia media) {
        return buildCaption(media.title, media.description);
    }

    private static String buildCaption(String title, String description) {
        if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(description)) {
            return title + "\n\n" + description;
        } else if (!TextUtils.isEmpty(description)) {
            return description;
        } else if (!TextUtils.isEmpty(title)) {
            return title;
        }
        return null;
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
            return true;
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
