package org.telegram.messenger.auto;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import android.app.Application;
import org.telegram.messenger.places.Place;
import org.telegram.messenger.places.PlaceExtractor;
import org.telegram.tgnet.TLRPC;
import org.json.JSONObject;

import java.util.Locale;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AutoPlaceItemTest {
    private static TLRPC.TL_message message(String text, TLRPC.MessageMedia media) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 7;
        message.date = 100;
        message.dialog_id = -10;
        message.message = text;
        message.media = media;
        return message;
    }

    private static AutoPlaceItem map(TLRPC.TL_message message, long senderId,
                                     String senderName, String chatTitle, boolean group) {
        return AutoPlaceItem.fromMessage(message, message.dialog_id, message.id,
                PlaceExtractor.extract(message), senderId, senderName, chatTitle, group);
    }

    @Test public void venueMappingUsesExpectedPrecedenceAndCompaction() {
        TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
        venue.title = "  Cafe\nNorth  ";
        venue.address = "  Main\nStreet  ";
        venue.geo = new TLRPC.TL_geoPoint();
        venue.geo.lat = 52.123456;
        venue.geo._long = 4.987654;
        AutoPlaceItem item = map(message("caption", venue), 42, "Alice", "Family", true);
        assertNotNull(item);
        assertEquals("Cafe North", item.title);
        assertEquals("Main Street", item.subtitle);
        assertEquals("Alice", item.senderName);
        assertEquals("Family", item.chatTitle);
        assertEquals("caption", item.messageText);
        assertEquals(52.123456, item.latitude, 0);
        assertNull(item.query);
    }

    @Test public void liveLocationAndQueryOnlyPlacesMapCorrectly() {
        TLRPC.TL_messageMediaGeoLive live = new TLRPC.TL_messageMediaGeoLive();
        live.geo = new TLRPC.TL_geoPoint();
        live.geo.lat = 1.2;
        live.geo._long = 3.4;
        AutoPlaceItem liveItem = map(message("", live), 0, "", null, false);
        assertEquals("Live location", liveItem.title);
        assertEquals("Telegram · 1.20000, 3.40000", liveItem.subtitle);
        assertTrue(liveItem.isLive);

        AutoPlaceItem queryItem = map(message("https://maps.apple.com/?q=Central%20Park", null), 0, "", null, false);
        assertEquals("Central Park", queryItem.title);
        assertEquals("Central Park", queryItem.query);
        assertNull(queryItem.latitude);
    }

    @Test public void firstNavigableNonSpoilerPlaceWinsAndEmptyEntryIsDropped() {
        TLRPC.TL_message message = message("https://maps.app.goo.gl/a https://maps.apple.com/?q=Cafe", null);
        java.util.ArrayList<Place> places = PlaceExtractor.extract(message);
        assertEquals(Place.Provider.APPLE,
                AutoPlaceItem.fromMessage(message, -10, 7, places, 1, "A", null, false).provider);
        places.get(0).title = "Resolved first";
        AutoPlaceItem item = AutoPlaceItem.fromMessage(message, -10, 7, places, 1, "A", null, false);
        assertEquals(Place.Provider.GOOGLE, item.provider);
        places.get(0).spoiler = true;
        assertEquals(Place.Provider.APPLE, AutoPlaceItem.fromMessage(message, -10, 7, places, 1, "A", null, false).provider);
        places.get(1).spoiler = true;
        assertNull(AutoPlaceItem.fromMessage(message, -10, 7, places, 1, "A", null, false));
    }

    @Test public void incomingAndOwnTelegramLocationsAreBothNavigable() {
        TLRPC.TL_messageMediaGeo geo = new TLRPC.TL_messageMediaGeo();
        geo.geo = new TLRPC.TL_geoPoint();
        geo.geo.lat = 52;
        geo.geo._long = 4;
        TLRPC.TL_message source = message("Meet here", geo);
        AutoPlaceItem received = map(source, 42, "Wife", null, false);
        assertNotNull(received);
        assertEquals("Wife", received.displaySenderTitle());
        assertTrue(received.buildNavigationUri().startsWith("geo:52.00000,4.00000"));
        source.out = true;
        AutoPlaceItem sent = map(source, 1, "You", "Wife", false);
        assertEquals("You · Wife", sent.displaySenderTitle());
        assertEquals(received.buildNavigationUri(), sent.buildNavigationUri());
    }

    @Test public void unresolvedShortLinkBecomesNavigableAfterDestinationMetadata() throws Exception {
        TLRPC.TL_message source = message("https://maps.app.goo.gl/destination", null);
        java.util.ArrayList<Place> places = PlaceExtractor.extract(source);
        assertNull(AutoPlaceItem.fromMessage(source, -10, 7, places, 42, "Wife", null, false));
        AutoPlacesRepository.applyCarMetadata(places.get(0), new JSONObject()
                .put("resolved", "https://google.com/maps/place/Cafe/data=!3d52.1!4d4.2")
                .put("title", "Cafe · Main Street"));
        AutoPlaceItem item = AutoPlaceItem.fromMessage(source, -10, 7, places, 42, "Wife", null, false);
        assertEquals("Cafe", item.title);
        assertTrue(item.buildNavigationUri().startsWith("geo:52.10000,4.20000"));
    }

    @Test public void liveUpdateChangesModelEvenWhenRoundedLabelDoesNotChange() {
        AutoPlaceItem first = new AutoPlaceItem("10:1", 10, 1, 42, "Wife", null, false,
                "Live location", "Telegram", 52.000001, 4.0, null, Place.Provider.TELEGRAM, 100, null, true);
        AutoPlaceItem next = new AutoPlaceItem("10:1", 10, 1, 42, "Wife", null, false,
                "Live location", "Telegram", 52.000002, 4.0, null, Place.Provider.TELEGRAM, 100, null, true);
        assertNotEquals(AutoPlacesRepository.signature(java.util.Collections.singletonList(first), false),
                AutoPlacesRepository.signature(java.util.Collections.singletonList(next), false));
    }

    @Test public void labelNavigationIncludesAddressAndRejectsGenericProviderCard() {
        Place place = new Place(Place.Provider.GOOGLE, "https://maps.app.goo.gl/a");
        place.title = "Cafe";
        place.address = "Main Street 1, Amsterdam";
        AutoPlaceItem item = AutoPlaceItem.fromMessage(message("", null), -10, 7,
                java.util.Collections.singletonList(place), 42, "Wife", null, false);
        assertEquals("Cafe, Main Street 1, Amsterdam", Uri.decode(item.buildNavigationUri().substring("geo:0,0?q=".length())));
        place.title = "Google Maps";
        assertNull(AutoPlaceItem.firstNavigable(java.util.Collections.singletonList(place)));
    }

    @Test public void navigationUriIsLocaleIndependentAndSanitized() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ru"));
            AutoPlaceItem item = new AutoPlaceItem("-10:7", -10, 7, 1, "A", "G", true,
                    " Кафе (центр),\n☕ ", "Address", 52.123456, 4.987654, null,
                    Place.Provider.TELEGRAM, 100, null, false);
            String uri = item.buildNavigationUri();
            assertTrue(uri.startsWith("geo:52.12346,4.98765?q=52.12346,4.98765("));
            assertEquals("geo", Uri.parse(uri).getScheme());
            assertFalse(uri.contains("%28центр%29"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test public void queryOnlyUriAndLengthCapsAreSafe() {
        AutoPlaceItem item = new AutoPlaceItem("1:2", 1, 2, 0, "", null, false,
                repeat("x", 1000), repeat("y", 1000), null, null, "Café ☕",
                Place.Provider.APPLE, 1, repeat("m", 1000), false);
        assertEquals("Unknown", item.displaySenderTitle());
        assertTrue(item.title.length() <= 80);
        assertTrue(item.subtitle.length() <= 80);
        assertTrue(item.messageText.length() <= 200);
        assertEquals("geo", Uri.parse(item.buildNavigationUri()).getScheme());
    }

    @Test public void carMetadataSeparatesCanonicalGoogleNameAndAddress() throws Exception {
        Place place = PlaceExtractor.parse("https://maps.google.com/?q=52,4");
        AutoPlacesRepository.applyCarMetadata(place, new JSONObject()
                .put("title", "Cafe · Main Street")
                .put("address", "★★★★☆ · Coffee"));
        assertEquals("Cafe", place.title);
        assertEquals("Main Street", place.address);
    }

    @Test public void carMetadataIgnoresGenericGoogleCard() throws Exception {
        Place place = PlaceExtractor.parse("https://maps.app.goo.gl/abc");
        AutoPlacesRepository.applyCarMetadata(place, new JSONObject()
                .put("title", "Google Maps").put("address", "Search nearby"));
        assertNull(place.title);
        assertNull(place.address);
    }

    @Test public void genericGoogleCardPreservesDestinationFromTheResolvedUrl() throws Exception {
        Place place = PlaceExtractor.parse("https://maps.app.goo.gl/abc");
        AutoPlacesRepository.applyCarMetadata(place, new JSONObject()
                .put("resolved", "https://maps.google.com/?q=Main%20Street%201%2C%20Amsterdam")
                .put("title", "Google Maps").put("address", "Search nearby"));
        assertEquals("Main Street 1, Amsterdam", place.title);
        assertNull(place.address);
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }

    @Test public void onlyVenueOrLinkNamesMayReachOverpass() {
        TLRPC.TL_messageMediaGeo geo = new TLRPC.TL_messageMediaGeo();
        geo.geo = new TLRPC.TL_geoPoint();
        geo.geo.lat = 52;
        geo.geo._long = 4;
        AutoPlaceItem captioned = map(message("pick me up at the back door", geo), 42, "Wife", null, false);
        assertEquals("pick me up at the back door", captioned.title);
        assertNull(captioned.placeName);

        TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
        venue.title = "Cafe North";
        venue.geo = new TLRPC.TL_geoPoint();
        venue.geo.lat = 52;
        venue.geo._long = 4;
        assertEquals("Cafe North", map(message("private words", venue), 42, "Wife", null, false).placeName);
    }

    @Test public void spoilerCaptionAndBareLinkNeverBecomeTheTitle() {
        TLRPC.TL_messageMediaGeo geo = new TLRPC.TL_messageMediaGeo();
        geo.geo = new TLRPC.TL_geoPoint();
        geo.geo.lat = 52;
        geo.geo._long = 4;
        TLRPC.TL_message hidden = message("secret", geo);
        TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
        spoiler.length = 6;
        hidden.entities.add(spoiler);
        AutoPlaceItem item = map(hidden, 42, "Wife", null, false);
        assertEquals("Location", item.title);
        assertNull(item.messageText);
        assertEquals(AutoPlaceItem.fromMessage(hidden, -10, 7, PlaceExtractor.extract(hidden), 42, "Wife", null, false).key, item.key);

        TLRPC.TL_message link = message("https://maps.google.com/?q=52.1,4.2", null);
        AutoPlaceItem linkItem = map(link, 42, "Wife", null, false);
        assertNotNull(linkItem);
        assertFalse(linkItem.title, linkItem.title.contains("http"));
        assertNull(linkItem.messageText);
    }
}
