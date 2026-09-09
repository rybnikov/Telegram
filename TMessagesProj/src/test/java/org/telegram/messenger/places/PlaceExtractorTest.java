package org.telegram.messenger.places;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class PlaceExtractorTest {
    private TLRPC.TL_message message(String text) {
        TLRPC.TL_message m = new TLRPC.TL_message();
        m.message = text;
        return m;
    }
    @Test public void recognizesFourProvidersAndShortLinksWithDomainBoundaries() {
        for (String url : new String[]{"https://maps.app.goo.gl/abc", "https://goo.gl/maps/abc", "https://www.google.com/maps/place/Cafe", "https://maps.google.com/?q=Cafe"})
            assertEquals(Place.Provider.GOOGLE, PlaceExtractor.parse(url).provider);
        assertEquals(Place.Provider.APPLE, PlaceExtractor.parse("https://maps.apple.com/?auid=123").provider);
        assertEquals(Place.Provider.APPLE, PlaceExtractor.parse("https://maps.apple/p/abc").provider);
        assertEquals(Place.Provider.YANDEX, PlaceExtractor.parse("https://yandex.ru/maps/-/abc").provider);
        assertEquals(Place.Provider.YANDEX, PlaceExtractor.parse("https://yandex.com/maps/org/abc/123").provider);
        assertEquals(Place.Provider.WAZE, PlaceExtractor.parse("https://waze.com/ul/hu123").provider);
        assertEquals(Place.Provider.WAZE, PlaceExtractor.parse("https://ul.waze.com/ul?ll=1,2&navigate=yes").provider);
        for (String url : new String[]{"https://maps.apple.com.evil.test/?ll=1,2", "https://evilgoogle.com/maps/", "https://google.com/search?q=maps", "https://yandex.ru/news", "https://waze.com/blog", "https://maps.apple.com@evil.test/", "ftp://maps.apple.com/a", "javascript:maps.apple.com/a"})
            assertNull(url, PlaceExtractor.parse(url));
    }
    @Test public void keepsMapCentersOutOfNavigationAndValidatesCoordinates() {
        for (String url : new String[]{"https://google.com/maps/@52,4,12z", "https://maps.apple.com/?ll=52,4", "https://yandex.ru/maps/?ll=4,52", "https://waze.com/live-map?ll=52,4"})
            assertNull(url, PlaceExtractor.parse(url).latitude);
        for (String pair : new String[]{"91,1", "1,181", "NaN,1", "Infinity,1", "1", "1,2,3"})
            assertNull(PlaceExtractor.parse("https://maps.google.com/?q=" + pair).latitude);
        Place point = PlaceExtractor.parse("https://google.com/maps/place/Cafe/data=!3d52.1!4d4.2");
        assertEquals(52.1, point.latitude, 0.00001);
        assertEquals(Place.Confidence.EXPLICIT_POINT, point.confidence);
        assertEquals(52.0, PlaceExtractor.parse("https://yandex.ru/maps/?pt=4,52,pm2rdm").latitude, 0);
    }
    @Test public void routesOnlyUseExplicitUnambiguousDestination() {
        Place p = PlaceExtractor.parse("https://google.com/maps/dir/?api=1&origin=1,2&destination=3,4");
        assertEquals(Place.Confidence.EXPLICIT_DESTINATION, p.confidence);
        assertEquals(3, p.latitude, 0);
        assertNull(PlaceExtractor.parse("https://google.com/maps/dir/A/B/@1,2,4z/data=!3d1!4d2").latitude);
        assertNull(PlaceExtractor.parse("https://yandex.ru/maps/?rtext=1,2~").latitude);
        assertEquals(3, PlaceExtractor.parse("https://yandex.ru/maps/?rtext=1,2~3,4").latitude, 0);
        assertEquals(3, PlaceExtractor.parse("https://maps.apple.com/?saddr=1,2&daddr=3,4").latitude, 0);
        assertEquals(3, PlaceExtractor.parse("https://waze.com/ul?ll=3,4&navigate=yes").latitude, 0);
    }
    @Test public void extractsHiddenLinksCaptionsMultipleUrlsAndSpoilers() {
        TLRPC.TL_message m = message("News https://example.org/ and here https://maps.apple.com/?q=Cafe");
        m.media = new TLRPC.TL_messageMediaPhoto(); // Caption uses the same message field.
        TLRPC.TL_messageEntityTextUrl hidden = new TLRPC.TL_messageEntityTextUrl();
        hidden.offset = 29; hidden.length = 4; hidden.url = "https://yandex.ru/maps/-/abc";
        m.entities.add(hidden);
        TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
        spoiler.offset = 29; spoiler.length = 4; m.entities.add(spoiler);
        ArrayList<Place> places = PlaceExtractor.extract(m);
        assertEquals(2, places.size());
        assertTrue(places.get(0).spoiler);
        assertEquals(Place.Provider.APPLE, places.get(1).provider);
        // Repeated sends are different entries; duplicate entities inside one source are deduplicated.
        m.entities.add(hidden);
        assertEquals(2, PlaceExtractor.extract(m).size());
    }

    @Test public void extractsBareProviderUrlsFromPlainText() {
        TLRPC.TL_message m = message("Meet: google.com/maps/place/Cafe and yandex.ru/maps/-/abc then waze.com/ul?ll=1,2");
        assertEquals(3, PlaceExtractor.extract(m).size());
        assertTrue(PlaceExtractor.extract(message("evilgoogle.com/maps/place/Fake")).isEmpty());
    }
    @Test public void unrelatedPreviewCannotNameOrOpenTheMap() {
        TLRPC.TL_message m = message("https://news.example/story https://maps.apple.com/?q=Cafe");
        m.media = new TLRPC.TL_messageMediaWebPage();
        m.media.webpage = new TLRPC.TL_webPage();
        m.media.webpage.url = "https://news.example/story";
        m.media.webpage.title = "Unrelated news";
        assertEquals("Cafe", PlaceExtractor.extract(m).get(0).title);
    }
    @Test public void canonicalPreviewCanEnrichOneShortMapLink() {
        TLRPC.TL_message m = message("https://maps.app.goo.gl/abc");
        m.media = new TLRPC.TL_messageMediaWebPage();
        m.media.webpage = new TLRPC.TL_webPage();
        m.media.webpage.url = "https://google.com/maps/place/Cafe/data=!3d52.1!4d4.2";
        m.media.webpage.title = "Cafe";
        Place place = PlaceExtractor.extract(m).get(0);
        assertEquals("https://maps.app.goo.gl/abc", place.originalUrl);
        assertEquals(m.media.webpage.url, place.resolvedUrl);
        assertEquals("Cafe", place.title);
        assertEquals(52.1, place.latitude, 0.00001);
    }
    @Test public void nativeVenueAndLiveUpdatesPreserveTheirSource() {
        TLRPC.TL_message m = message("");
        m.media = new TLRPC.TL_messageMediaVenue();
        m.media.geo = new TLRPC.TL_geoPoint();
        m.media.geo.lat = 52; m.media.geo._long = 4;
        m.media.title = "Cafe"; m.media.address = "Street";
        Place p = PlaceExtractor.extract(m).get(0);
        assertEquals("Cafe", p.title); assertEquals("Street", p.address);
        assertEquals(Place.Confidence.TELEGRAM, p.confidence);
        m.media = new TLRPC.TL_messageMediaGeoLive();
        m.media.geo = new TLRPC.TL_geoPoint();
        m.media.geo.lat = 53; m.media.geo._long = 5;
        assertEquals(53, PlaceExtractor.extract(m).get(0).latitude, 0);
        m.media = null; assertTrue(PlaceExtractor.extract(m).isEmpty());
    }
}
