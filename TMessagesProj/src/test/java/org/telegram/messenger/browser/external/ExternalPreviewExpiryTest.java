package org.telegram.messenger.browser.external;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE)
public final class ExternalPreviewExpiryTest {

    // Expiry from the failing Reels cover; omit its path and signature.
    private static final String POSTER = "https://instagram.frtm1-1.fna.fbcdn.net/poster.jpg?oe=6AD3F98B";
    private static final long EXPIRES_AT = 0x6AD3F98BL;

    @Test
    public void signedCoverExpiresAtItsDeadline() {
        assertFalse(ExternalPreviewExpiry.isExpired(POSTER, EXPIRES_AT - 1));
        assertTrue(ExternalPreviewExpiry.isExpired(POSTER, EXPIRES_AT));
        assertTrue(ExternalPreviewExpiry.isExpired(POSTER, EXPIRES_AT + 1));
        assertTrue(ExternalPreviewExpiry.isExpired("https://scontent.cdninstagram.com/p.jpg?oe=6ad3f98b", EXPIRES_AT));
    }

    @Test
    public void unrelatedOrUnknownExpiryFormatsRemainUsable() {
        String[] urls = {
            null, "", "https://cdn.example/p.jpg?oe=1",
            "https://fbcdn.net.example/p.jpg?oe=1",
            "https://notfbcdn.net/p.jpg?oe=1",
            "https://scontent.fbcdn.net/p.jpg",
            "https://scontent.fbcdn.net/p.jpg?oe=invalid",
            "https://scontent.fbcdn.net/p.jpg?oe=FFFFFFFFFFFFFFFF",
            "https://scontent.fbcdn.net/p.jpg?oe=-1",
            "https://scontent.fbcdn.net/p.jpg?oe="
        };
        for (String url : urls) {
            assertFalse(url, ExternalPreviewExpiry.isExpired(url, EXPIRES_AT));
        }
    }

    @Test
    public void expiredPosterOrVideoInvalidatesMediaIncludingLaterCarouselItems() {
        ResolvedMedia.Video expiredPoster = new ResolvedMedia.Video("https://cdn.example/video.mp4", POSTER, null, null, 720, 1280);
        ResolvedMedia.Video expiredVideo = new ResolvedMedia.Video("https://scontent.fbcdn.net/v.mp4?oe=1", "https://cdn.example/p.jpg", null, null, 720, 1280);
        assertTrue(ExternalPreviewExpiry.isExpired(expiredPoster, EXPIRES_AT));
        assertTrue(ExternalPreviewExpiry.isExpired(expiredVideo, EXPIRES_AT));
        ArrayList<ResolvedMedia.Single> items = new ArrayList<>();
        items.add(new ResolvedMedia.Image("https://cdn.example/first.jpg", null, null, 100, 100));
        items.add(expiredPoster);
        assertTrue(ExternalPreviewExpiry.isExpired(new ResolvedMedia.Carousel(items, null, null), EXPIRES_AT));
        assertFalse(ExternalPreviewExpiry.isExpired(new ResolvedMedia.Carousel(items, null, null), EXPIRES_AT - 1));
    }
}
