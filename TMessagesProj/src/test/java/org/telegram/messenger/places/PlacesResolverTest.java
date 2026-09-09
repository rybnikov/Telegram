package org.telegram.messenger.places;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.browser.external.ExternalHtmlUtils;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class PlacesResolverTest {
    @Test public void shortRedirectKeepsOriginalUrlAndExtractsOnlyTheDestination() throws Exception {
        Place p = PlaceExtractor.parse("https://maps.apple/p/abc");
        PlacesResolver.apply(p, PlacesResolver.parseMetadata(p.originalUrl, new ExternalHtmlUtils.FetchResult(
                "<meta property=\"og:title\" content=\"Cafe &amp; Bakery\"><meta property=\"og:description\" content=\"Street 1\">",
                "https://maps.apple.com/place?coordinate=52,4&name=Cafe")));
        assertEquals("https://maps.apple/p/abc", p.originalUrl);
        assertEquals("Cafe & Bakery", p.title);
        assertEquals("Street 1", p.address);
        assertEquals(52, p.latitude, 0);
        assertNotNull(p.resolvedUrl);
    }
    @Test public void redirectsOutsideProviderCannotSubstituteMetadata() throws Exception {
        Place p = PlaceExtractor.parse("https://maps.app.goo.gl/abc");
        PlacesResolver.apply(p, PlacesResolver.parseMetadata(p.originalUrl,
                new ExternalHtmlUtils.FetchResult("<meta property=\"og:title\" content=\"Bad\">", "https://google.com.evil.test/maps/?q=52,4")));
        assertNull(p.resolvedUrl); assertNull(p.title); assertNull(p.latitude);
    }
    @Test public void centerOnlyRedirectNeverBecomesNavigationCoordinates() throws Exception {
        Place p = PlaceExtractor.parse("https://maps.app.goo.gl/abc");
        PlacesResolver.apply(p, PlacesResolver.parseMetadata(p.originalUrl,
                new ExternalHtmlUtils.FetchResult("", "https://google.com/maps/@52,4,17z")));
        assertNull(p.latitude); assertEquals(Place.Confidence.UNKNOWN, p.confidence);
        assertNotNull(p.resolvedUrl);
    }
    @Test public void topicsAndSavedDialogsUseSeparateApiScopeFields() {
        TLRPC.TL_messages_search topic = new TLRPC.TL_messages_search();
        PlacesRepository.applyScope(topic, -10, 77, 100, null);
        assertEquals(2, topic.flags); assertEquals(77, topic.top_msg_id); assertNull(topic.saved_peer_id);
        TLRPC.TL_messages_search saved = new TLRPC.TL_messages_search();
        TLRPC.TL_inputPeerUser peer = new TLRPC.TL_inputPeerUser(); peer.user_id = 10;
        PlacesRepository.applyScope(saved, 100, 10, 100, peer);
        assertEquals(4, saved.flags); assertSame(peer, saved.saved_peer_id); assertEquals(0, saved.top_msg_id);
        TLRPC.TL_messages_search all = new TLRPC.TL_messages_search();
        PlacesRepository.applyScope(all, 100, 0, 100, null); assertEquals(0, all.flags);
    }
}
