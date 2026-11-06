package org.telegram.messenger.browser.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    public void expiredPersistedVideoDoesNotRepopulateStore() {
        assertExpiredRecordRejected(new ResolvedMedia.Video(
            "https://cdn.example/video.mp4", "https://scontent.fbcdn.net/poster.jpg?oe=1", null, null, 720, 1280));
        assertExpiredRecordRejected(new ResolvedMedia.Video(
            "https://scontent.cdninstagram.com/video.mp4?oe=1", "https://cdn.example/poster.jpg", null, null, 720, 1280));
    }

    @Test
    public void expiredPersistedImagesAndCarouselItemsRequireResolve() {
        ResolvedMedia.Image expired = new ResolvedMedia.Image("https://scontent.fbcdn.net/poster.jpg?oe=1", null, null, 720, 1280);
        assertExpiredRecordRejected(expired);
        ArrayList<ResolvedMedia.Single> items = new ArrayList<>();
        items.add(new ResolvedMedia.Image("https://cdn.example/first.jpg", null, null, 720, 1280));
        items.add(expired);
        assertExpiredRecordRejected(new ResolvedMedia.Carousel(items, null, null));
    }

    private void assertExpiredRecordRejected(ResolvedMedia media) {
        ParsedLink link = new ParsedLink("https://www.instagram.com/reel/test/", "https://www.instagram.com/reel/test/", "test", "Instagram");
        TLRPC.WebPage webPage = PreviewMapper.buildWebPage(link, null, media);
        MessagesStorage.ExternalPreviewRecord record = PreviewMapper.createExternalPreviewRecord(link, webPage, media);
        ExternalMediaPreviewStore.clearDebugState();

        assertNull(PreviewMapper.hydrateCachedPreview(record));
        assertNull(ExternalMediaPreviewStore.getVideoPreview(webPage.id));
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
        assertEquals("photo", webPage.type);
        assertEquals(media.posterUrl, webPage.embed_url);
        assertNull(webPage.document);
        ExternalMediaPreviewStore.VideoPreview storedPreview = ExternalMediaPreviewStore.getVideoPreview(webPage.id);
        assertNotNull(storedPreview);
        assertEquals(media.videoUrl, storedPreview.videoUrl);

        MessagesStorage.ExternalPreviewRecord record = PreviewMapper.createExternalPreviewRecord(link, webPage, media);
        assertEquals(MessagesStorage.EXTERNAL_PREVIEW_KIND_VIDEO, record.previewKind);
        assertEquals(media.videoUrl, record.mediaUrl);
        assertEquals(media.posterUrl, record.posterUrl);

        PreviewMapper.HydratedPreview hydrated = PreviewMapper.hydrateCachedPreview(record);
        assertNotNull(hydrated);
        assertEquals(webPage.id, hydrated.webPage.id);
        assertEquals("photo", hydrated.webPage.type);
        assertEquals(media.posterUrl, hydrated.webPage.embed_url);
        assertNull(hydrated.webPage.document);
        assertNotNull(ExternalMediaPreviewStore.getVideoPreview(hydrated.webPage.id));
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
        assertNull(ExternalMediaPreviewStore.getVideoPreview(webPage.id));

        MessagesStorage.ExternalPreviewRecord record = PreviewMapper.createExternalPreviewRecord(link, webPage, media);
        assertEquals(MessagesStorage.EXTERNAL_PREVIEW_KIND_CAROUSEL, record.previewKind);
        assertNotNull(record.extra);

        PreviewMapper.HydratedPreview hydrated = PreviewMapper.hydrateCachedPreview(record);
        assertNotNull(hydrated);
        assertNull(ExternalMediaPreviewStore.getVideoPreview(hydrated.webPage.id));
        assertTrue(hydrated.media instanceof ResolvedMedia.Carousel);
        ResolvedMedia.Carousel hydratedCarousel = (ResolvedMedia.Carousel) hydrated.media;
        assertEquals(2, hydratedCarousel.items.size());
        assertTrue(hydratedCarousel.items.get(0) instanceof ResolvedMedia.Image);
        assertTrue(hydratedCarousel.items.get(1) instanceof ResolvedMedia.Video);
        assertEquals(media.title, hydratedCarousel.title);
        assertEquals(media.description, hydratedCarousel.description);
    }
}
