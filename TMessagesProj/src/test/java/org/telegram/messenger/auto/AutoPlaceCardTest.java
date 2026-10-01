package org.telegram.messenger.auto;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.places.Place;
import org.telegram.messenger.places.PlaceDetails;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AutoPlaceCardTest {
    private static final int MONDAY = 0, SUNDAY = 6;

    private static AutoPlaceItem item(String note) {
        return new AutoPlaceItem("1:2", -5, 2, 3, "Mom", "Family", true,
                "Sports 2000", "Street", 52d, 4d, null, Place.Provider.GOOGLE, 1000, note, false, "Sports 2000");
    }

    private static List<String> titles(List<AutoPlaceCard.Line> lines) {
        ArrayList<String> result = new ArrayList<>();
        for (AutoPlaceCard.Line line : lines) result.add(line.title + (line.text == null ? "" : " | " + line.text));
        return result;
    }

    private static PlaceDetails full() {
        PlaceDetails details = new PlaceDetails();
        details.title = "Rijksmuseum";
        details.category = "museum";
        details.stars = 4;
        details.address = "Museumstraat 1, Amsterdam";
        details.openingHours = "Mo-Su 09:00-17:00";
        details.description = "Dutch national museum of arts and history.";
        return details;
    }

    @Test public void fullCardKeepsSenderAndDropsDescriptionFirst() {
        List<String> five = titles(AutoPlaceCard.lines(item("see you"), full(), 1000, MONDAY, 5));
        assertEquals(java.util.Arrays.asList(
                "Rijksmuseum · Museum · ★ 4/5",
                "Museumstraat 1, Amsterdam",
                "Today 9:00–17:00",
                "Dutch national museum of arts and history.",
                "Sent by Mom · Family | just now · see you"), five);
        List<String> four = titles(AutoPlaceCard.lines(item(null), full(), 1000, MONDAY, 4));
        assertEquals(4, four.size());
        assertFalse(four.toString(), four.toString().contains("Dutch national"));
        assertEquals("Sent by Mom · Family | just now", four.get(3));
        List<String> two = titles(AutoPlaceCard.lines(item(null), full(), 1000, MONDAY, 2));
        assertEquals(java.util.Arrays.asList("Museumstraat 1, Amsterdam", "Sent by Mom · Family | just now"), two);
        assertEquals("Sports 2000", AutoPlaceCard.title(item(null)));
        PlaceDetails same = full();
        same.title = "sports 2000";
        assertEquals("Museum · ★ 4/5", AutoPlaceCard.lines(item(null), same, 1000, MONDAY, 4).get(0).title);
    }

    @Test public void missingFieldsAreSkippedAndTitleFallsBackToTheRow() {
        List<String> lines = titles(AutoPlaceCard.lines(item(null), new PlaceDetails(), 1000, MONDAY, 4));
        assertEquals(java.util.Collections.singletonList("Sent by Mom · Family | just now"), lines);
        assertEquals("Sports 2000", AutoPlaceCard.title(item(null)));
    }

    @Test public void todaysHoursFollowTheWeekday() {
        String hours = "Mo-Fr 09:00-18:00; Sa 10:00-14:00; Su off";
        assertEquals("Today 9:00–18:00", AutoOpeningHours.today(hours, MONDAY));
        assertEquals("Today 10:00–14:00", AutoOpeningHours.today(hours, 5));
        assertEquals("Closed today", AutoOpeningHours.today(hours, SUNDAY));
        assertEquals("Today 8:00–12:00, 13:00–17:00", AutoOpeningHours.today("Mo-Fr 08:00-12:00,13:00-17:00", 2));
        assertEquals("Open 24 hours", AutoOpeningHours.today("24/7", 3));
        assertEquals("Today 9:00–21:00", AutoOpeningHours.today("09:00-21:00", 3));
        assertEquals("Today 18:00–2:00", AutoOpeningHours.today("Fr-Mo 18:00-02:00", SUNDAY));
        assertNull(AutoOpeningHours.today("Mo-Fr 09:00-18:00", SUNDAY));
        assertNull(AutoOpeningHours.today("Mo-Fr 09:00-18:00; PH off", MONDAY));
        assertNull(AutoOpeningHours.today(null, MONDAY));
    }

    @Test public void googleStaticMapPreviewGivesCoordinatesOnlyWhenZoomedToAPlace() {
        assertEquals("52.1635386,4.5324219", AutoPlacesRepository.googleStaticMapCenter(
                "https://maps.google.com/maps/api/staticmap?center=52.1635386%2C4.5324219&zoom=17&size=900x900&key=x&signature=y"));
        assertEquals("-33.5,151.25", AutoPlacesRepository.googleStaticMapCenter(
                "https://www.google.com/maps/api/staticmap?center=-33.5,151.25&zoom=15"));
        assertNull(AutoPlacesRepository.googleStaticMapCenter(
                "https://maps.google.com/maps/api/staticmap?center=52.39,4.91&zoom=11"));
        assertNull(AutoPlacesRepository.googleStaticMapCenter(
                "https://evil.example/maps/api/staticmap?center=52.1,4.5&zoom=17"));
        assertNull(AutoPlacesRepository.googleStaticMapCenter(
                "https://maps.google.com/maps/api/staticmap?center=Amsterdam&zoom=17"));
        assertNull(AutoPlacesRepository.googleStaticMapCenter(null));
    }

    @Test public void descriptionIsSpokenInItsOwnLanguage() {
        PlaceDetails details = full();
        details.description = "Государственный музей в Амстердаме.";
        List<List<AutoPlaceSummaryBuilder.Part>> sentences =
                AutoPlaceSummaryBuilder.buildSentences(item(null), details, 1000, 4000);
        boolean found = false;
        for (AutoSpeechLanguage.Utterance utterance : AutoSpeechLanguage.resolve(sentences, "ru", new java.util.Locale("en"))) {
            if (utterance.text.startsWith("Государственный")) {
                assertEquals("ru", utterance.locale.getLanguage());
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test public void cardImageIsDownsampledToCarSize() {
        assertEquals(1, AutoPlaceImageLoader.sampleSize(480, 300));
        assertEquals(2, AutoPlaceImageLoader.sampleSize(960, 600));
        assertEquals(8, AutoPlaceImageLoader.sampleSize(4000, 3000));
    }

    @Test public void liveAndRestrictedPlacesAreLocalOnly() {
        AutoPlaceItem plain = item(null);
        assertFalse(plain.isLocalOnly());
        plain.restricted = true;
        assertTrue(plain.isLocalOnly());
        AutoPlaceItem live = new AutoPlaceItem("1:2", -5, 2, 3, "Mom", null, false,
                "Live location", "Telegram", 52d, 4d, null, Place.Provider.TELEGRAM, 1000, null, true);
        assertTrue(live.isLocalOnly());
    }

    @Test public void cardPictureIsScaledForTheHost() {
        assertArrayEquals(new int[]{320, 320}, AutoPlaceImageLoader.scaledSize(900, 900));
        assertArrayEquals(new int[]{320, 180}, AutoPlaceImageLoader.scaledSize(640, 360));
        assertArrayEquals(new int[]{200, 100}, AutoPlaceImageLoader.scaledSize(200, 100));
    }
}
