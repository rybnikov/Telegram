package org.telegram.messenger.browser.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.browser.instagram.InstagramMediaResolver;
import org.telegram.messenger.browser.maps.MapsMediaResolver;
import org.telegram.messenger.browser.pinterest.PinterestMediaResolver;
import org.telegram.messenger.browser.tiktok.TikTokMediaResolver;
import org.telegram.messenger.browser.youtube.YouTubeMediaResolver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE)
public final class PlaybackResolverTest {

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
    public void instagramReelPlaybackMatchesDirectStreamBranch() throws Exception {
        InstagramMediaResolver resolver = new InstagramMediaResolver();
        ParsedLink link = resolver.parseLink(Uri.parse("https://www.instagram.com/reel/DZNeXBhxq6H/"));
        assertNotNull(link);
        assertTrue(resolver instanceof PlaybackResolver);

        ResolvedMedia media = (ResolvedMedia) invoke(resolver, "extractMedia",
            new Class[]{ParsedLink.class, String.class},
            link, PreviewTestResources.readPreviewFixture("ig_reel_video.html")
        );
        assertTrue(media instanceof ResolvedMedia.Video);

        Playback playback = (Playback) invoke(resolver, "resolvePlayback",
            new Class[]{ResolvedMedia.class},
            media
        );

        assertTrue(playback instanceof Playback.DirectStream);
        assertEquals(((ResolvedMedia.Video) media).videoUrl, ((Playback.DirectStream) playback).url);
    }

    @Test
    public void instagramResolvedVideoPlaybackDoesNotResolveLinkAgain() throws Exception {
        Method method = InstagramMediaResolver.class.getDeclaredMethod("resolvePlayback", ParsedLink.class, ResolvedMedia.Video.class);
        assertEquals(InstagramMediaResolver.class, method.getDeclaringClass());

        InstagramMediaResolver resolver = new InstagramMediaResolver();
        ParsedLink link = new ParsedLink("not-a-network-url", "not-a-network-url", "offline", resolver.platformName());
        ResolvedMedia.Video video = new ResolvedMedia.Video(
            "https://cdn.example/offline.mp4",
            "https://cdn.example/offline.jpg",
            "Offline",
            "Already resolved",
            640,
            360
        );

        Playback playback = resolver.resolvePlayback(link, video);

        assertTrue(playback instanceof Playback.DirectStream);
        assertEquals(video.videoUrl, ((Playback.DirectStream) playback).url);
    }

    @Test
    public void tiktokPlaybackMatchesStreamEmbedBrowserFallbackOrder() throws Exception {
        TikTokMediaResolver resolver = new TikTokMediaResolver();
        assertTrue(resolver instanceof PlaybackResolver);

        Playback direct = (Playback) invokeStatic(TikTokMediaResolver.class, "resolvePlayback",
            new Class[]{String.class, String.class, String.class},
            "https://v16.tiktokcdn.com/video.mp4",
            null,
            "https://www.tiktok.com/@rodionlyan/video/7649461509629791509"
        );
        assertTrue(direct instanceof Playback.DirectStream);
        assertEquals("https://v16.tiktokcdn.com/video.mp4", ((Playback.DirectStream) direct).url);

        Playback embed = (Playback) invokeStatic(TikTokMediaResolver.class, "resolvePlayback",
            new Class[]{String.class, String.class, String.class},
            null,
            "https://www.tiktok.com/@rodionlyan/video/7649461509629791509",
            "https://vm.tiktok.com/ZGd9LDKge/"
        );
        assertTrue(embed instanceof Playback.Embed);
        assertEquals("https://www.tiktok.com/embed/v2/7649461509629791509", ((Playback.Embed) embed).url);

        Playback external = (Playback) invokeStatic(TikTokMediaResolver.class, "resolvePlayback",
            new Class[]{String.class, String.class, String.class},
            null,
            null,
            "https://vm.tiktok.com/ZGd9LDKge/"
        );
        assertSame(Playback.External.INSTANCE, external);
    }

    @Test
    public void youtubePlaybackMatchesEmbedBranch() throws Exception {
        YouTubeMediaResolver resolver = new YouTubeMediaResolver();
        ParsedLink link = resolver.parseLink(Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertNotNull(link);
        assertTrue(resolver instanceof PlaybackResolver);

        ResolvedMedia media = (ResolvedMedia) invokeStatic(YouTubeMediaResolver.class, "buildPreviewFromOEmbed",
            new Class[]{ParsedLink.class, String.class},
            link, PreviewTestResources.readPreviewFixture("yt_video_oembed.json")
        );
        assertTrue(media instanceof ResolvedMedia.Video);

        Playback playback = (Playback) invoke(resolver, "resolvePlayback",
            new Class[]{ResolvedMedia.class},
            media
        );

        assertTrue(playback instanceof Playback.Embed);
        assertEquals(((ResolvedMedia.Video) media).videoUrl, ((Playback.Embed) playback).url);
    }

    @Test
    public void previewOnlyResolversDoNotImplementPlaybackResolver() {
        Object mapsResolver = new MapsMediaResolver();
        Object pinterestResolver = new PinterestMediaResolver();

        assertFalse(mapsResolver instanceof PlaybackResolver);
        assertFalse(pinterestResolver instanceof PlaybackResolver);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return invokeMethod(method, target, args);
    }

    private static Object invokeStatic(Class<?> targetClass, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = targetClass.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return invokeMethod(method, null, args);
    }

    private static Object invokeMethod(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }
}
