package org.telegram.messenger.browser.external;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.EmbedBottomSheet;
import org.telegram.ui.LaunchActivity;

public final class PreviewClickDispatcher {

    private static final String TAG = "ExternalPreviewClick";

    private PreviewClickDispatcher() {
    }

    static Playback resolvePlayback(PlaybackResolver resolver, ParsedLink link, ResolvedMedia.Video video) throws Exception {
        return resolver.resolvePlayback(link, video);
    }

    static void resolveAndOpenVideoAsync(Context context, ParsedLink link, PlaybackResolver resolver, ResolvedMedia.Video video, ExternalMediaPreviewStore.VideoPreview storedPreview) {
        new Thread(() -> {
            Playback playback;
            try {
                playback = resolvePlayback(resolver, link, video);
            } catch (Exception e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d(TAG + ": playback resolve failed " + e.getClass().getSimpleName() + " url=" + link.canonicalUrl);
                }
                playback = Playback.External.INSTANCE;
            }
            Playback finalPlayback = playback;
            AndroidUtilities.runOnUIThread(() -> openVideo(context, link, video, finalPlayback, storedPreview));
        }, "ExtPreview-playback").start();
    }

    static boolean openVideo(Context context, ParsedLink link, ResolvedMedia.Video video, Playback playback, ExternalMediaPreviewStore.VideoPreview storedPreview) {
        Branch branch = branchFor(playback);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d(TAG + ": open branch=" + branch + " url=" + link.canonicalUrl);
        }
        switch (branch) {
            case DIRECT_STREAM:
                return openDirectStream(context, link, video, (Playback.DirectStream) playback, storedPreview);
            case EMBED:
                return openEmbed(context, link, video, (Playback.Embed) playback);
            case EXTERNAL:
            default:
                Browser.openUrl(context, link.getCanonicalUri(), true, true, false, null, null, false, true, false);
                return true;
        }
    }

    static Branch branchFor(Playback playback) {
        if (playback instanceof Playback.DirectStream) {
            return Branch.DIRECT_STREAM;
        } else if (playback instanceof Playback.Embed) {
            return Branch.EMBED;
        }
        return Branch.EXTERNAL;
    }

    private static boolean openDirectStream(Context context, ParsedLink link, ResolvedMedia.Video video, Playback.DirectStream playback, ExternalMediaPreviewStore.VideoPreview storedPreview) {
        String streamUrl = playback.url;
        if (TextUtils.isEmpty(streamUrl)) {
            Browser.openUrl(context, link.getCanonicalUri(), true, true, false, null, null, false, true, false);
            return true;
        }
        if (storedPreview != null && TextUtils.equals(storedPreview.videoUrl, streamUrl) && storedPreview.videoWebFile != null) {
            return ExternalMediaOpenHelper.openVideoPreview(context, storedPreview);
        }
        ResolvedMedia.Video streamVideo = new ResolvedMedia.Video(
            streamUrl,
            video != null ? video.posterUrl : null,
            video != null ? video.title : null,
            video != null ? video.description : null,
            video != null ? video.width : 0,
            video != null ? video.height : 0
        );
        return ExternalMediaOpenHelper.openResolved(context, link.getCanonicalUri(), streamVideo);
    }

    private static boolean openEmbed(Context context, ParsedLink link, ResolvedMedia.Video video, Playback.Embed playback) {
        if (TextUtils.isEmpty(playback.url)) {
            Browser.openUrl(context, link.getCanonicalUri(), true, true, false, null, null, false, true, false);
            return true;
        }
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            Browser.openUrl(context, Uri.parse(playback.url), true, true, false, null, null, false, true, false);
            return true;
        }
        EmbedBottomSheet.show(
            fragment,
            null,
            null,
            link.platformName,
            video != null ? video.title : null,
            link.canonicalUrl,
            playback.url,
            video != null ? video.width : 0,
            video != null ? video.height : 0,
            false
        );
        return true;
    }

    enum Branch {
        DIRECT_STREAM,
        EMBED,
        EXTERNAL
    }
}
