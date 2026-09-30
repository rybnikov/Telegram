package org.telegram.messenger.auto;

import android.app.Application;

import androidx.car.app.model.Row;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AutoPlaceItemFactoryTest {
    private static Row row(boolean speaking, Runnable onTap, Runnable onAbout) {
        return AutoPlaceItemFactory.buildRow("Alice · Family", "Cafe", "Main Street",
                null, null, speaking, onTap, onAbout);
    }

    @Test public void rowStaysInsideListTemplateLimitsWithASingleAction() {
        Row row = row(false, () -> { }, () -> { });
        assertEquals("Alice · Family", row.getTitle().toString());
        assertEquals(2, row.getTexts().size());
        assertEquals("Cafe", row.getTexts().get(0).toString());
        assertEquals("Main Street", row.getTexts().get(1).toString());
        assertFalse("A row with actions must not be browsable", row.isBrowsable());
        assertNotNull("Tapping the row navigates", row.getOnClickDelegate());
        // A ListTemplate row renders one secondary action; a second one is dropped by the host.
        assertEquals(1, row.getActions().size());
        assertEquals("About", row.getActions().get(0).getTitle().toString());
    }

    @Test public void speakingRowOffersStopInstead() {
        assertEquals("Stop", row(true, () -> { }, () -> { }).getActions().get(0).getTitle().toString());
    }
}
