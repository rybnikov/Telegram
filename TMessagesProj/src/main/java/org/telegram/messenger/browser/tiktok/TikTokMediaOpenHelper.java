package org.telegram.messenger.browser.tiktok;

import android.content.Context;
import android.net.Uri;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.external.ExternalMediaPreviewStore;
import org.telegram.messenger.browser.external.ExternalMediaViewerOpener;

public final class TikTokMediaOpenHelper {

    private static final String TAG = "TikTokOpen";

    private TikTokMediaOpenHelper() {
    }

    public static boolean tryOpen(Context context, Uri uri, PhotoViewerProviderFallback fallback, BrowserProgressHandle progressHandle) {
        TikTokLinkParser.ParsedLink link = TikTokLinkParser.parse(uri);
        if (link == null) {
            return false;
        }

        if (progressHandle != null) {
            progressHandle.init();
        }

        Utilities.globalQueue.postRunnable(() -> {
            TikTokMediaResolver.ResolvedMedia resolvedMedia = null;
            Throwable error = null;
            try {
                resolvedMedia = new TikTokMediaResolver().resolve(link);
            } catch (Throwable e) {
                error = e;
            }

            final TikTokMediaResolver.ResolvedMedia finalResolvedMedia = resolvedMedia;
            final Throwable finalError = error;
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                if (finalResolvedMedia != null && openResolved(context, uri, finalResolvedMedia)) {
                    FileLog.d(TAG + ": opened internal viewer " + finalResolvedMedia.sourceUrl);
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

    public static boolean openResolved(Context context, Uri uri, TikTokMediaResolver.ResolvedMedia media) {
        TikTokLinkParser.ParsedLink link = TikTokLinkParser.parse(uri);
        if (link == null || !(media instanceof TikTokMediaResolver.ResolvedMedia.Video)) {
            return false;
        }
        TikTokMediaResolver.ResolvedMedia.Video video = (TikTokMediaResolver.ResolvedMedia.Video) media;
        ExternalMediaPreviewStore.VideoPreview preview = ExternalMediaPreviewStore.getVideoPreview(computePreviewId(video.sourceUrl));
        if (preview == null) {
            ExternalMediaPreviewStore.putVideo(
                computePreviewId(video.sourceUrl),
                "TikTok",
                video.sourceUrl,
                video.videoUrl,
                video.posterUrl,
                video.width,
                video.height,
                video.title,
                video.description
            );
            preview = ExternalMediaPreviewStore.getVideoPreview(computePreviewId(video.sourceUrl));
        }
        return preview != null && ExternalMediaViewerOpener.openVideoPreview(context, preview);
    }

    private static long computePreviewId(String sourceUrl) {
        String md5 = org.telegram.messenger.Utilities.MD5(sourceUrl);
        if (md5 == null || md5.length() < 16) {
            return Math.abs((long) sourceUrl.hashCode());
        }
        try {
            return Long.parseUnsignedLong(md5.substring(0, 16), 16);
        } catch (Exception ignore) {
            return Math.abs((long) sourceUrl.hashCode());
        }
    }

    public interface PhotoViewerProviderFallback {
        void run(Uri fallbackUri);
    }

    public interface BrowserProgressHandle {
        void init();
        void end();
    }
}
