package org.telegram.messenger.browser.external;

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
import org.telegram.messenger.browser.youtube.YouTubeMediaResolver;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE)
public final class ExternalPreviewCellBinderTest {

    private static final String SHORT_URL = "https://youtube.com/shorts/-8IaXpu3kdQ?is=IL562CHcvLGsoNtM";

    private boolean logsEnabled;

    @Before
    public void setUp() {
        logsEnabled = PreviewTestResources.disableLogs();
        ExternalMediaPreviewStore.clearDebugState();
    }

    @After
    public void tearDown() {
        ExternalMediaPreviewStore.clearDebugState();
        PreviewTestResources.restoreLogs(logsEnabled);
    }

    @Test
    public void forkBuiltYouTubePreviewRendersAsCustomPreview() {
        TLRPC.WebPage webPage = buildForkYouTubeWebPage();

        assertTrue(ExternalPreviewCellBinder.isFoldogramWebPage(webPage));
        assertTrue(ExternalPreviewCellBinder.isExternalPreviewSite(webPage.site_name, webPage));
    }

    @Test
    public void forkBuiltYouTubePreviewStaysCustomAfterVideoStoreIsCleared() {
        TLRPC.WebPage webPage = buildForkYouTubeWebPage();
        // Simulates a restart: the in-memory video store is empty, the stored webpage remains.
        ExternalMediaPreviewStore.clearDebugState();

        assertTrue(ExternalPreviewCellBinder.isExternalPreviewSite(webPage.site_name, webPage));
    }

    @Test
    public void serverYouTubePreviewKeepsUpstreamRendering() {
        TLRPC.TL_webPage server = new TLRPC.TL_webPage();
        server.id = 5_123_456_789L;
        server.url = "https://www.youtube.com/shorts/-8IaXpu3kdQ";
        server.site_name = "YouTube";
        server.embed_url = "https://www.youtube.com/embed/-8IaXpu3kdQ";

        assertFalse(ExternalPreviewCellBinder.isFoldogramWebPage(server));
        assertFalse(ExternalPreviewCellBinder.isExternalPreviewSite(server.site_name, server));
    }

    @Test
    public void missingOrIncompleteWebPagesAreNotForkPreviews() {
        assertFalse(ExternalPreviewCellBinder.isFoldogramWebPage(null));
        assertFalse(ExternalPreviewCellBinder.isFoldogramWebPage(new TLRPC.TL_webPagePending()));
        TLRPC.TL_webPage noUrl = new TLRPC.TL_webPage();
        noUrl.id = 1;
        assertFalse(ExternalPreviewCellBinder.isFoldogramWebPage(noUrl));
        assertFalse(ExternalPreviewCellBinder.isExternalPreviewSite(null, (TLRPC.WebPage) null));
    }

    @Test
    public void overrideSitesStayCustomForServerPages() {
        TLRPC.TL_webPage server = new TLRPC.TL_webPage();
        server.id = 42L;
        server.url = "https://www.tiktok.com/@a/video/1";
        server.site_name = "TikTok";

        assertTrue(ExternalPreviewCellBinder.isExternalPreviewSite(server.site_name, server));
    }

    @Test
    public void forkBuiltYouTubePreviewKeepsYouTubeEmbedForTelegramPlayer() {
        TLRPC.WebPage webPage = buildForkYouTubeWebPage();

        // Telegram's YouTube player (PhotoViewer) reads embed_url; the poster comes from the video store.
        assertEquals("https://www.youtube.com/embed/-8IaXpu3kdQ?autoplay=1", webPage.embed_url);
        assertEquals("video", webPage.type);
        assertEquals("https://i.ytimg.com/vi/-8IaXpu3kdQ/oar2.jpg",
                ExternalMediaPreviewStore.getVideoPreview(webPage.id).posterUrl);
    }

    @Test
    public void overridingSitesKeepPosterAsEmbedUrl() {
        TLRPC.TL_webPage webPage = new TLRPC.TL_webPage();
        webPage.url = "https://www.tiktok.com/@a/video/1";
        webPage.type = "photo";
        webPage.embed_url = "https://poster.example/p.jpg";

        PreviewMapper.applyServerStyleEmbed(webPage, "https://cdn.example/v.mp4");

        assertEquals("https://poster.example/p.jpg", webPage.embed_url);
        assertEquals("photo", webPage.type);
    }

    private static TLRPC.WebPage buildForkYouTubeWebPage() {
        YouTubeMediaResolver resolver = new YouTubeMediaResolver();
        ParsedLink link = resolver.parseLink(Uri.parse(SHORT_URL));
        assertNotNull(link);
        ResolvedMedia.Video video = new ResolvedMedia.Video(
                "https://www.youtube.com/embed/-8IaXpu3kdQ?autoplay=1",
                "https://i.ytimg.com/vi/-8IaXpu3kdQ/oar2.jpg",
                "title", "author", 405, 720);
        TLRPC.WebPage webPage = PreviewMapper.buildWebPage(link, resolver, video);
        assertNotNull(webPage);
        return webPage;
    }
}
