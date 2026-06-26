package org.telegram.messenger.browser.tiktok;

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
public final class TikTokResolverTest {

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
    public void oEmbedFixtureBuildsVideoPreviewMetadata() throws Exception {
        JSONObject json = new JSONObject(PreviewTestResources.readPreviewFixture("tt_oembed.json"));
        ResolvedMedia media = TikTokMediaResolver.buildPreviewFromOEmbed("https://www.tiktok.com/@rodionlyan/video/7649461509629791509", json);

        assertTrue(media instanceof ResolvedMedia.Video);
        ResolvedMedia.Video video = (ResolvedMedia.Video) media;
        assertEquals("https://www.tiktok.com/@rodionlyan/video/7649461509629791509", video.videoUrl);
        assertFalse(TextUtils.isEmpty(video.posterUrl));
        assertTrue(video.posterUrl.startsWith("https://"));
        assertEquals("#змееголов #зовприроды #трофей ", video.title);
        assertEquals("♏️Зов_Природы♉️", video.description);
        assertEquals(576, video.width);
        assertEquals(1024, video.height);
    }

    @Test
    public void parserExtractsDirectAndShortVideoIds() {
        TikTokMediaResolver resolver = new TikTokMediaResolver();

        ParsedLink direct = resolver.parseLink(Uri.parse("https://www.tiktok.com/@rodionlyan/video/7649461509629791509"));
        ParsedLink shortLink = resolver.parseLink(Uri.parse("https://vm.tiktok.com/ZGd9LDKge/"));

        assertNotNull(direct);
        assertEquals("7649461509629791509", direct.id);
        assertNotNull(shortLink);
        assertEquals("ZGd9LDKge", shortLink.id);
    }
}
