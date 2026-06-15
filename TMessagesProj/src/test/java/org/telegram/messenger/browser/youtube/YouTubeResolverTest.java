package org.telegram.messenger.browser.youtube;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.browser.external.ParsedLink;
import org.telegram.messenger.browser.external.PreviewTestResources;
import org.telegram.messenger.browser.external.ResolvedMedia;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE)
public final class YouTubeResolverTest {

    private final YouTubeMediaResolver resolver = new YouTubeMediaResolver();
    private boolean logsEnabled;

    @Before
    public void setUp() {
        logsEnabled = PreviewTestResources.disableLogs();
    }

    @After
    public void tearDown() {
        PreviewTestResources.restoreLogs(logsEnabled);
    }

    @Test
    public void regularVideoOEmbedUsesLandscapePoster() throws Exception {
        ResolvedMedia.Video video = parseVideo("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "yt_video_oembed.json");

        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1", video.videoUrl);
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", video.posterUrl);
        assertEquals(1280, video.width);
        assertEquals(720, video.height);
        assertEquals("Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)", video.title);
        assertEquals("Rick Astley", video.description);
    }

    @Test
    public void shortsOEmbedDocumentsCurrentAspectBasedClassifier() throws Exception {
        ResolvedMedia.Video landscapeShort = parseVideo("https://www.youtube.com/shorts/tPEE9ZwTmy0", "yt_shorts_oembed.json");
        ResolvedMedia.Video portraitShort = parseVideo("https://www.youtube.com/shorts/eCOUbueAEd8", "yt_shorts_oembed2.json");

        // KNOWN FLAKY: Shorts oEmbed aspect ratio is unstable; current classifier treats 200x113 as regular video.
        assertTrue(landscapeShort.posterUrl.endsWith("/maxresdefault.jpg"));
        assertEquals(1280, landscapeShort.width);
        assertEquals(720, landscapeShort.height);

        assertTrue(portraitShort.posterUrl.endsWith("/oar2.jpg"));
        assertEquals(405, portraitShort.width);
        assertEquals(720, portraitShort.height);
    }

    private ResolvedMedia.Video parseVideo(String url, String fixture) throws Exception {
        ParsedLink link = resolver.parseLink(Uri.parse(url));
        assertNotNull(link);
        ResolvedMedia media = YouTubeMediaResolver.buildPreviewFromOEmbed(link, PreviewTestResources.readPreviewFixture(fixture));
        assertTrue(media instanceof ResolvedMedia.Video);
        ResolvedMedia.Video video = (ResolvedMedia.Video) media;
        assertFalse(video.posterUrl.isEmpty());
        return video;
    }
}
