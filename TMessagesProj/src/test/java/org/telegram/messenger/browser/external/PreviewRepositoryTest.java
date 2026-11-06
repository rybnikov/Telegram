package org.telegram.messenger.browser.external;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE)
public final class PreviewRepositoryTest {

    private boolean logsEnabled;

    @Before
    public void setUp() {
        logsEnabled = PreviewTestResources.disableLogs();
        PreviewRepository.clearDebugState();
    }

    @After
    public void tearDown() {
        PreviewRepository.clearDebugState();
        PreviewTestResources.restoreLogs(logsEnabled);
    }

    @Test
    public void openingExpiredMemoryEntryFallsThroughToFreshResolution() throws Exception {
        String url = "https://www.instagram.com/reel/test/";
        TLRPC.TL_webPage webPage = new TLRPC.TL_webPage();
        webPage.url = url;
        ResolvedMedia.Video video = new ResolvedMedia.Video(
            "https://cdn.example/video.mp4", "https://scontent.fbcdn.net/poster.jpg?oe=1", null, null, 720, 1280);
        // Seed the state of a long-running session without invoking a network resolver.
        Class<?> entryClass = Class.forName(PreviewRepository.class.getName() + "$CachedPreview");
        Constructor<?> constructor = entryClass.getDeclaredConstructor(TLRPC.WebPage.class, ResolvedMedia.class);
        constructor.setAccessible(true);
        Field cacheField = PreviewRepository.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) cacheField.get(null);
        cache.put(url, constructor.newInstance(webPage, video));

        assertFalse(PreviewRepository.openCachedPreview(null, Uri.parse(url)));
        assertTrue(cache.isEmpty());
    }
}
