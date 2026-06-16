package org.telegram.messenger.browser.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE)
public final class PreviewMapperTest {

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
    public void videoPreviewRoundTripPreservesRecordFields() {
        ParsedLink link = new ParsedLink(
            "https://www.instagram.com/reel/DZNeXBhxq6H/",
            "https://www.instagram.com/reel/DZNeXBhxq6H/",
            "DZNeXBhxq6H",
            "Instagram"
        );
        ResolvedMedia.Video media = new ResolvedMedia.Video(
            "https://cdn.example/video.mp4",
            "https://cdn.example/poster.jpg",
            "Ferry Seksie",
            "Amsterdam",
            640,
            360
        );

        TLRPC.WebPage webPage = PreviewMapper.buildWebPage(link, null, media);
        assertNotNull(webPage);
        assertEquals("video", webPage.type);
        assertNotNull(webPage.document);

        MessagesStorage.ExternalPreviewRecord record = PreviewMapper.createExternalPreviewRecord(link, webPage, media);
        assertEquals(MessagesStorage.EXTERNAL_PREVIEW_KIND_VIDEO, record.previewKind);
        assertEquals(media.videoUrl, record.mediaUrl);
        assertEquals(media.posterUrl, record.posterUrl);

        PreviewMapper.HydratedPreview hydrated = PreviewMapper.hydrateCachedPreview(record);
        assertNotNull(hydrated);
        assertEquals(webPage.id, hydrated.webPage.id);
        assertTrue(hydrated.media instanceof ResolvedMedia.Video);
        ResolvedMedia.Video hydratedVideo = (ResolvedMedia.Video) hydrated.media;
        assertEquals(media.videoUrl, hydratedVideo.videoUrl);
        assertEquals(media.posterUrl, hydratedVideo.posterUrl);
        assertEquals(media.title, hydratedVideo.title);
        assertEquals(media.description, hydratedVideo.description);
    }

    @Test
    public void carouselPreviewRoundTripPreservesItems() {
        ParsedLink link = new ParsedLink(
            "https://www.instagram.com/p/DZZ8qcggLRi/",
            "https://www.instagram.com/p/DZZ8qcggLRi/",
            "DZZ8qcggLRi",
            "Instagram"
        );
        ArrayList<ResolvedMedia.Single> items = new ArrayList<>();
        items.add(new ResolvedMedia.Image("https://cdn.example/first.jpg", null, null, 1080, 1350));
        items.add(new ResolvedMedia.Video("https://cdn.example/second.mp4", "https://cdn.example/second.jpg", null, null, 1080, 1920));
        ResolvedMedia.Carousel media = new ResolvedMedia.Carousel(items, "Carousel title", "Carousel description");

        TLRPC.WebPage webPage = PreviewMapper.buildWebPage(link, null, media);
        assertNotNull(webPage);
        assertEquals("photo", webPage.type);

        MessagesStorage.ExternalPreviewRecord record = PreviewMapper.createExternalPreviewRecord(link, webPage, media);
        assertEquals(MessagesStorage.EXTERNAL_PREVIEW_KIND_CAROUSEL, record.previewKind);
        assertNotNull(record.extra);

        PreviewMapper.HydratedPreview hydrated = PreviewMapper.hydrateCachedPreview(record);
        assertNotNull(hydrated);
        assertTrue(hydrated.media instanceof ResolvedMedia.Carousel);
        ResolvedMedia.Carousel hydratedCarousel = (ResolvedMedia.Carousel) hydrated.media;
        assertEquals(2, hydratedCarousel.items.size());
        assertTrue(hydratedCarousel.items.get(0) instanceof ResolvedMedia.Image);
        assertTrue(hydratedCarousel.items.get(1) instanceof ResolvedMedia.Video);
        assertEquals(media.title, hydratedCarousel.title);
        assertEquals(media.description, hydratedCarousel.description);
    }
}
