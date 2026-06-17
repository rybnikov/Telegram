package org.telegram.messenger.browser.youtube;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.external.ExternalHttpClient;
import org.telegram.messenger.browser.external.ExternalMediaResolver;
import org.telegram.messenger.browser.external.ParsedLink;
import org.telegram.messenger.browser.external.Playback;
import org.telegram.messenger.browser.external.PlaybackResolver;
import org.telegram.messenger.browser.external.ResolvedMedia;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class YouTubeMediaResolver implements ExternalMediaResolver, PlaybackResolver {

    private static final String TAG = "YouTubeResolver";
    private static final int MAX_RESPONSE_CHARS = 16 * 1024;
    private static final Set<String> SITE_NAMES = new HashSet<>(Arrays.asList("youtube"));

    @Override
    public ParsedLink parseLink(Uri uri) {
        return YouTubeLinkParser.parse(uri);
    }

    @Override
    public String platformName() {
        return "YouTube";
    }

    @Override
    public Set<String> siteNames() {
        return SITE_NAMES;
    }

    // overridesServerPreview = false (default): Telegram's server preview + embed player work great.
    // supportsDirectVideoStreaming = true (default): not used since we don't override.

    @Override
    public ResolvedMedia resolve(ParsedLink link) throws Exception {
        String oembedUrl = "https://www.youtube.com/oembed?url=" + Uri.encode(link.canonicalUrl) + "&format=json";
        String response = ExternalHttpClient.fetchHtml(oembedUrl, null, null, MAX_RESPONSE_CHARS);
        if (TextUtils.isEmpty(response)) {
            FileLog.d(TAG + ": empty oEmbed response for " + link.canonicalUrl);
            return null;
        }

        return buildPreviewFromOEmbed(link, response);
    }

    @Override
    public Playback resolvePlayback(ParsedLink link) throws Exception {
        return resolvePlayback(resolve(link));
    }

    @Override
    public Playback resolvePlayback(ParsedLink link, ResolvedMedia.Video video) throws Exception {
        if (video != null && !TextUtils.isEmpty(video.videoUrl)) {
            return resolvePlayback(video);
        }
        return resolvePlayback(link);
    }

    Playback resolvePlayback(ResolvedMedia media) {
        if (media instanceof ResolvedMedia.Video) {
            ResolvedMedia.Video video = (ResolvedMedia.Video) media;
            if (!TextUtils.isEmpty(video.videoUrl)) {
                return new Playback.Embed(video.videoUrl);
            }
        }
        return Playback.External.INSTANCE;
    }

    static ResolvedMedia buildPreviewFromOEmbed(ParsedLink link, String response) throws Exception {
        JSONObject json = new JSONObject(response);

        String title = json.optString("title", null);
        String author = json.optString("author_name", null);
        String description = !TextUtils.isEmpty(author) ? author : null;

        // oEmbed width/height tells us aspect ratio (Shorts: height > width)
        int oembedWidth = json.optInt("width", 200);
        int oembedHeight = json.optInt("height", 113);
        boolean isShorts = oembedHeight > oembedWidth;

        String safeId = Uri.encode(link.id);

        // HD thumbnail — maxresdefault for regular, hq for shorts
        String posterUrl;
        int posterWidth;
        int posterHeight;
        if (isShorts) {
            posterUrl = "https://i.ytimg.com/vi/" + safeId + "/oar2.jpg";
            posterWidth = 405;
            posterHeight = 720;
        } else {
            posterUrl = "https://i.ytimg.com/vi/" + safeId + "/maxresdefault.jpg";
            posterWidth = 1280;
            posterHeight = 720;
        }

        // Embed URL for WebView playback
        String embedUrl = "https://www.youtube.com/embed/" + safeId + "?autoplay=1";

        FileLog.d(TAG + ": resolved " + (isShorts ? "shorts " : "") + link.canonicalUrl);
        return new ResolvedMedia.Video(embedUrl, posterUrl, title, description, posterWidth, posterHeight);
    }
}
