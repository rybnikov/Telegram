package org.telegram.messenger.auto;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import android.app.Application;
import org.telegram.messenger.places.Place;
import org.telegram.messenger.places.PlaceDetails;
import org.telegram.tgnet.TLRPC;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AutoPlaceSummaryBuilderTest {
    private static AutoPlaceItem item(long date, String message) {
        return new AutoPlaceItem("1:2", 1, 2, 3, "Alice", "Family", true,
                "Cafe", "Street", 1d, 2d, null, Place.Provider.TELEGRAM,
                (int) date, message, false);
    }

    @Test public void fullSummaryHasStableSentenceOrder() {
        PlaceDetails details = new PlaceDetails();
        details.title = "Cafe";
        details.category = "coffee shop";
        details.stars = 4;
        details.address = "Main Street";
        details.openingHours = "Mo-Fr 09:00-17:00; Sa 10:00-14:00";
        assertEquals("Cafe. coffee shop, 4 stars. Main Street. Opening hours: Monday to Friday, 9:00 to 17:00; Saturday, 10:00 to 14:00. Sent by Alice in Family 5 minutes ago. Note: Meet here",
                AutoPlaceSummaryBuilder.build(item(700, "Meet here"), details, 1000, 4000));
    }

    @Test public void missingDetailsStillIncludesSender() {
        assertEquals("Shared location, no more details. Sent by Alice in Family just now",
                AutoPlaceSummaryBuilder.build(item(1000, null), new PlaceDetails(), 1000, 4000));
    }

    @Test public void relativeTimeBoundaries() {
        assertEquals("just now", AutoPlaceSummaryBuilder.relativeTime(970, 1000));
        assertEquals("2 minutes ago", AutoPlaceSummaryBuilder.relativeTime(880, 1000));
        assertEquals("2 hours ago", AutoPlaceSummaryBuilder.relativeTime(1000 - 7200, 1000));
        assertEquals("yesterday", AutoPlaceSummaryBuilder.relativeTime(1000 - 86400, 1000));
        assertEquals("3 days ago", AutoPlaceSummaryBuilder.relativeTime(1000 - 3 * 86400, 1000));
    }

    @Test public void capCutsAtSentenceBoundary() {
        PlaceDetails details = new PlaceDetails();
        details.title = "First sentence";
        details.address = "Second sentence is very long";
        String summary = AutoPlaceSummaryBuilder.build(item(1000, null), details, 1000, 35);
        assertTrue(summary.length() < 35);
        assertEquals("First sentence", summary);
    }

    @Test public void linkOnlyMessageNeverSpeaksTheLink() {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = "https://maps.app.goo.gl/AbC123";
        message.date = 1000;
        Place place = new Place(Place.Provider.GOOGLE, message.message);
        place.latitude = 52d;
        place.longitude = 4d;
        java.util.ArrayList<Place> places = new java.util.ArrayList<>();
        places.add(place);
        AutoPlaceItem item = AutoPlaceItem.fromMessage(message, 1, 2, places, 3, "Alice", null, false);
        String summary = AutoPlaceSummaryBuilder.build(item, new PlaceDetails(), 1000, 4000);
        assertEquals("Google Maps place", item.title);
        assertEquals("Shared location, no more details. Sent by Alice just now", summary);
        for (String forbidden : new String[]{"http", "goo.gl", "maps.", "Message", "52"}) {
            assertFalse(summary, summary.contains(forbidden));
        }
    }

    @Test public void captionIsATrailingNoteWithoutLinksAndReadOnce() {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = "Great ramen here https://maps.app.goo.gl/x";
        message.date = 1000;
        Place place = new Place(Place.Provider.GOOGLE, "https://maps.app.goo.gl/x");
        place.latitude = 52d;
        place.longitude = 4d;
        java.util.ArrayList<Place> places = new java.util.ArrayList<>();
        places.add(place);
        AutoPlaceItem item = AutoPlaceItem.fromMessage(message, 1, 2, places, 3, "Alice", null, false);
        assertEquals("Great ramen here", item.title);
        assertNull(item.placeName);
        assertEquals("Shared location, no more details. Sent by Alice just now. Note: Great ramen here",
                AutoPlaceSummaryBuilder.build(item, new PlaceDetails(), 1000, 4000));
        PlaceDetails details = new PlaceDetails();
        details.title = "Great ramen here";
        assertEquals("Great ramen here. Sent by Alice just now",
                AutoPlaceSummaryBuilder.build(item, details, 1000, 4000));
    }

    @Test public void venueNameIsSpokenWhenNothingElseIsKnown() {
        AutoPlaceItem item = new AutoPlaceItem("1:2", 1, 2, 3, "Alice", null, false,
                "Cafe", "Street", 1d, 2d, null, Place.Provider.TELEGRAM, 1000, null, false, "Cafe");
        assertEquals("Cafe, no more details. Sent by Alice just now",
                AutoPlaceSummaryBuilder.build(item, new PlaceDetails(), 1000, 4000));
        PlaceDetails onlyHours = new PlaceDetails();
        onlyHours.openingHours = "PH off";
        assertEquals("Cafe, no more details. Sent by Alice just now",
                AutoPlaceSummaryBuilder.build(item, onlyHours, 1000, 4000));
    }
}
