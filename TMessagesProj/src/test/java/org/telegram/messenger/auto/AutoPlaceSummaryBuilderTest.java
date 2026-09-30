package org.telegram.messenger.auto;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import android.app.Application;
import org.telegram.messenger.places.Place;
import org.telegram.messenger.places.PlaceDetails;

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
        assertEquals("Cafe. coffee shop, 4 stars. Main Street. Opening hours: Mo-Fr 09:00-17:00, Sa 10:00-14:00. Sent by Alice in Family 5 minutes ago. Message: Meet here",
                AutoPlaceSummaryBuilder.build(item(700, "Meet here"), details, 1000, 4000));
    }

    @Test public void missingDetailsStillIncludesSender() {
        assertEquals("No details for this place. Sent by Alice in Family just now",
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
}
