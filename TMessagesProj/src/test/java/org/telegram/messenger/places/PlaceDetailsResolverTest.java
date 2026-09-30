package org.telegram.messenger.places;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import android.app.Application;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class PlaceDetailsResolverTest {
    @Test public void mergeOnlyFillsMissingFields() {
        PlaceDetails first = new PlaceDetails();
        first.title = "Venue";
        first.stars = 4;
        PlaceDetails second = new PlaceDetails();
        second.title = "Other";
        second.address = "Street";
        second.category = "cafe";
        first.merge(second);
        assertEquals("Venue", first.title);
        assertEquals("Street", first.address);
        assertEquals("cafe", first.category);
        assertEquals(4, first.stars);
    }

    @Test public void googleOpenGraphCardParsesOnlyRecognizedForms() throws Exception {
        PlaceDetails details = PlaceDetailsResolver.parseOpenGraph(new JSONObject()
                .put("title", "Cafe · Main Street")
                .put("address", "★★★★☆ · Coffee · brunch"));
        assertEquals("Cafe", details.title);
        assertEquals("Main Street", details.address);
        assertEquals(4, details.stars);
        assertEquals("Coffee · brunch", details.category);

        PlaceDetails generic = PlaceDetailsResolver.parseOpenGraph(new JSONObject()
                .put("title", "Google Maps").put("address", "Cafe"));
        assertNull(generic.title);
        assertNull(generic.category);
        assertEquals(-1, generic.stars);
    }

    @Test public void overpassPicksNearestAndLocalizedFields() throws Exception {
        String body = "{\"elements\":["
                + "{\"type\":\"way\",\"center\":{\"lat\":52.001,\"lon\":4.001},\"tags\":{\"name\":\"Far\"}},"
                + "{\"type\":\"node\",\"lat\":52.0001,\"lon\":4.0001,\"tags\":{\"name:nl\":\"Dichtbij\",\"name\":\"Near\",\"amenity\":\"coffee_shop\",\"opening_hours\":\"Mo-Fr 09:00-17:00\",\"contact:website\":\"https://x\",\"phone\":\"123\"}}]}";
        PlaceDetails details = PlaceDetailsResolver.parseOverpass(body, 52, 4, "nl");
        assertEquals("Dichtbij", details.title);
        assertEquals("coffee shop", details.category);
        assertEquals("Mo-Fr 09:00-17:00", details.openingHours);
        assertEquals("https://x", details.website);
        assertEquals("123", details.phone);
    }

    @Test public void invalidOverpassBecomesEmptyAndCacheExpiryDiffers() {
        assertTrue(PlaceDetailsResolver.parseOverpass("<html>", 1, 2, "en").isEmpty());
        long now = 1_000_000;
        assertTrue(PlaceDetailsResolver.isFresh("{\"empty\":true}", now - 86399, now));
        assertFalse(PlaceDetailsResolver.isFresh("{\"empty\":true}", now - 86401, now));
        assertTrue(PlaceDetailsResolver.isFresh("{\"title\":\"x\"}", now - 604799, now));
        assertFalse(PlaceDetailsResolver.isFresh("{\"title\":\"x\"}", now - 604801, now));
        assertEquals("details:52.123457,4.987654", PlaceDetailsResolver.cacheKey(52.1234567, 4.9876543));
    }

    @Test public void throttlingBacksOffPerServerAndPerKey() {
        long now = 1_000_000L;
        assertEquals(now + 5 * 60 * 1000L, PlaceDetailsResolver.pauseUntil(429, now));
        assertEquals(now + 5 * 60 * 1000L, PlaceDetailsResolver.pauseUntil(503, now));
        assertEquals(0, PlaceDetailsResolver.pauseUntil(404, now));
        assertEquals(now + 10 * 60 * 1000L, PlaceDetailsResolver.failureBackoff(now));
        assertTrue(PlaceDetailsResolver.throttled(now, now + 1, null));
        assertFalse(PlaceDetailsResolver.throttled(now, now, null));
        assertTrue(PlaceDetailsResolver.throttled(now, 0, now + 1));
        assertFalse(PlaceDetailsResolver.throttled(now, 0, now));
    }

    @Test public void overpassQueryEscapesInputAndUsesExpectedRadius() {
        String named = PlaceDetailsResolver.buildOverpassBody(1.2, 3.4, "A \\\"Cafe\\\"");
        assertTrue(named.contains("around:200,1.200000,3.400000"));
        assertTrue(named, named.contains("name") && named.contains("Cafe"));
        assertTrue(PlaceDetailsResolver.buildOverpassBody(1.2, 3.4, null).contains("around:50"));
    }
}
