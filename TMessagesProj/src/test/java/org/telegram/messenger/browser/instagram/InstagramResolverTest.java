package org.telegram.messenger.browser.instagram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;
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
public final class InstagramResolverTest {

    private final InstagramMediaResolver resolver = new InstagramMediaResolver();
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
    public void reelFixtureResolvesVideoFromShortcodeMediaNode() throws Exception {
        ParsedLink link = parse("https://www.instagram.com/reel/DZNeXBhxq6H/");
        String html = PreviewTestResources.readPreviewFixture("ig_reel_video.html");
        String[] anchor = new String[1];

        JSONObject mediaNode = resolver.extractPrimaryMediaObject(link, html, anchor);
        ResolvedMedia media = resolver.extractMedia(link, html);

        assertNotNull(mediaNode);
        assertEquals("video_versions", anchor[0]);
        assertTrue(media instanceof ResolvedMedia.Video);
        ResolvedMedia.Video video = (ResolvedMedia.Video) media;
        assertTrue(video.videoUrl.startsWith("https://"));
        assertTrue(video.videoUrl.contains(".mp4"));
        assertFalse(TextUtils.isEmpty(video.posterUrl));
        assertTrue("OG fallback would not carry dimensions from the media node", video.width > 0 || video.height > 0);
    }

    @Test
    public void allReelFixturesResolveVideo() throws Exception {
        assertReelVideo("DZNeXBhxq6H", "ig_reel_video.html");
        assertReelVideo("DZBHXvlzFpR", "ig_reel_video2.html");
        assertReelVideo("DYfvXwSAp-O", "ig_reel_video3.html");
    }

    @Test
    public void photoCarouselFixtureResolvesThreeImages() throws Exception {
        ParsedLink link = parse("https://www.instagram.com/p/DZZ8qcggLRi/");
        String html = PreviewTestResources.readPreviewFixture("ig_carousel_photo.html");
        String[] anchor = new String[1];

        JSONObject mediaNode = resolver.extractPrimaryMediaObject(link, html, anchor);
        ResolvedMedia media = resolver.extractMedia(link, html);

        assertNotNull(mediaNode);
        assertEquals("carousel_media", anchor[0]);
        assertTrue(media instanceof ResolvedMedia.Carousel);
        ResolvedMedia.Carousel carousel = (ResolvedMedia.Carousel) media;
        assertEquals(3, carousel.items.size());
        for (int i = 0; i < carousel.items.size(); i++) {
            assertTrue(carousel.items.get(i) instanceof ResolvedMedia.Image);
            ResolvedMedia.Image image = (ResolvedMedia.Image) carousel.items.get(i);
            assertTrue(image.imageUrl.startsWith("https://"));
        }
    }

    @Test
    public void mixedCarouselFixtureDocumentsCurrentSingleVideoDegradation() throws Exception {
        ParsedLink link = parse("https://www.instagram.com/p/DZbxX0vMwXO/");
        String html = PreviewTestResources.readPreviewFixture("ig_carousel_mixed.html");
        String[] anchor = new String[1];

        JSONObject mediaNode = resolver.extractPrimaryMediaObject(link, html, anchor);
        ResolvedMedia media = resolver.extractMedia(link, html);

        assertNotNull(mediaNode);
        assertEquals("video_versions", anchor[0]);
        // KNOWN BUG: mixed carousel resolved as single video, fix in later phase.
        assertTrue(media instanceof ResolvedMedia.Video);
    }

    private void assertReelVideo(String shortcode, String fixture) throws Exception {
        ParsedLink link = parse("https://www.instagram.com/reel/" + shortcode + "/");
        ResolvedMedia media = resolver.extractMedia(link, PreviewTestResources.readPreviewFixture(fixture));

        assertTrue(media instanceof ResolvedMedia.Video);
        ResolvedMedia.Video video = (ResolvedMedia.Video) media;
        assertTrue(video.videoUrl.startsWith("https://"));
        assertTrue(video.videoUrl.contains(".mp4"));
        assertFalse(TextUtils.isEmpty(video.posterUrl));
    }

    private ParsedLink parse(String url) {
        ParsedLink link = resolver.parseLink(Uri.parse(url));
        assertNotNull(link);
        return link;
    }
}
