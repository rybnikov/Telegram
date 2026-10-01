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

    @Test public void cacheExpiryDiffersForEmptyAndPositiveDetails() {
        long now = 1_000_000;
        assertTrue(PlaceDetailsResolver.isFresh("{\"empty\":true}", now - 86399, now));
        assertFalse(PlaceDetailsResolver.isFresh("{\"empty\":true}", now - 86401, now));
        assertTrue(PlaceDetailsResolver.isFresh("{\"title\":\"x\"}", now - 604799, now));
        assertFalse(PlaceDetailsResolver.isFresh("{\"title\":\"x\"}", now - 604801, now));
        assertEquals("details3:52.123457,4.987654", PlaceDetailsResolver.cacheKey(52.1234567, 4.9876543, ""));
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

    @Test public void nominatimPoiGivesNameCategoryHoursAndShortAddress() throws Exception {
        PlaceDetails details = PlaceDetailsResolver.parseNominatim(new JSONObject()
                .put("category", "amenity").put("type", "fast_food").put("name", "Кафе Пушкин")
                .put("extratags", new JSONObject().put("opening_hours", "Mo-Su 10:00-22:00").put("contact:phone", "+7"))
                .put("address", new JSONObject().put("road", "Тверской бульвар").put("house_number", "26А")
                        .put("city", "Москва").put("postcode", "125009")));
        assertEquals("Кафе Пушкин", details.title);
        assertEquals("fast food", details.category);
        assertEquals("Mo-Su 10:00-22:00", details.openingHours);
        assertEquals("+7", details.phone);
        assertEquals("Тверской бульвар 26А, Москва", details.address);
    }

    @Test public void streetsAndAreasOnlyGiveAnAddress() throws Exception {
        PlaceDetails street = PlaceDetailsResolver.parseNominatim(new JSONObject()
                .put("category", "highway").put("type", "footway").put("name", "Dam")
                .put("address", new JSONObject().put("road", "Dam").put("city", "Amsterdam")));
        assertNull(street.title);
        assertNull(street.category);
        assertEquals("Dam, Amsterdam", street.address);
        PlaceDetails house = PlaceDetailsResolver.parseNominatim(new JSONObject()
                .put("category", "building").put("type", "yes").put("name", ""));
        assertTrue(house.isEmpty());
        assertTrue(PlaceDetailsResolver.parseNominatim(new JSONObject().put("error", "Unable to geocode")).isEmpty());
        assertNull(PlaceDetailsResolver.parseNominatimSearch("[]"));
        try {
            PlaceDetailsResolver.parseNominatimSearch("<html>captive portal</html>");
            fail("A non-JSON body is an error, not an empty answer");
        } catch (org.json.JSONException expected) {
            // Not cached by the resolver.
        }
        assertEquals("Cafe", PlaceDetailsResolver.parseNominatimSearch(
                "[{\"category\":\"amenity\",\"type\":\"cafe\",\"name\":\"Cafe\"}]").title);
    }

    @Test public void nominatimUrlsAreEncodedBoundedAndLocalized() throws Exception {
        String search = PlaceDetailsResolver.searchUrl(52.0, 4.0, "Ben & Jerry's #1", "ru");
        assertTrue(search, search.startsWith("https://nominatim.openstreetmap.org/search?"));
        assertTrue(search, search.contains("q=Ben+%26+Jerry%27s+%231"));
        assertTrue(search, search.contains("bounded=1") && search.contains("limit=1"));
        assertTrue(search, search.contains("accept-language=ru%2Cen"));
        assertTrue(search, search.contains("viewbox=3.9959"));
        String reverse = PlaceDetailsResolver.reverseUrl(52.1234567, 4.9876543, "en-NL");
        assertTrue(reverse, reverse.contains("lat=52.123457") && reverse.contains("lon=4.987654") && reverse.contains("zoom=18"));
        assertFalse(reverse, reverse.contains("q="));
    }

    @Test public void queryOnlyLinksAreLookedUpByLinkNameAndAddress() throws Exception {
        PlaceDetails base = new PlaceDetails();
        base.address = "Main Street 1";
        PlaceDetailsResolver.Request request = new PlaceDetailsResolver.Request("1:2", 1, null, null,
                "Cafe Luigi", base, false);
        assertEquals("Cafe Luigi, Main Street 1", PlaceDetailsResolver.lookupQuery(request));
        assertEquals("details3:qb:cafe luigi, main street 1", PlaceDetailsResolver.queryKey("Cafe Luigi, Main Street 1", ""));
        assertNull(PlaceDetailsResolver.lookupQuery(new PlaceDetailsResolver.Request("1:2", 1, null, null,
                null, base, false)));
        String url = PlaceDetailsResolver.searchUrl(null, null, "Cafe Luigi, Main Street 1", "nl");
        assertTrue(url, url.contains("q=Cafe+Luigi%2C+Main+Street+1"));
        assertFalse(url, url.contains("viewbox") || url.contains("bounded"));
    }

    @Test public void nameOnlySearchKeepsNothingBranchSpecific() {
        PlaceDetails found = new PlaceDetails();
        found.title = "Sports 2000";
        found.category = "sports";
        found.address = "Carrer de Sant Vicent Màrtir, Valencia";
        found.openingHours = "Mo-Fr 09:00-18:30";
        found.phone = "+34";
        found.website = "https://example.es";
        PlaceDetails kept = PlaceDetailsResolver.brandOnly(found);
        assertEquals("Sports 2000", kept.title);
        assertEquals("sports", kept.category);
        assertNull(kept.address);
        assertNull(kept.openingHours);
        assertNull(kept.phone);
        assertNull(kept.website);
        assertNull(PlaceDetailsResolver.brandOnly(null));
    }

    @Test public void wikipediaTagsSitelinksAndSummariesParse() throws Exception {
        PlaceDetails tagged = PlaceDetailsResolver.parseNominatim(new JSONObject().put("category", "tourism")
                .put("type", "museum").put("name", "Rijksmuseum")
                .put("extratags", new JSONObject().put("wikipedia", "nl:Rijksmuseum Amsterdam").put("wikidata", "Q190804")));
        assertEquals("nl:Rijksmuseum Amsterdam", tagged.wikipedia);
        assertEquals("Q190804", tagged.wikidata);
        assertArrayEquals(new String[]{"nl", "Rijksmuseum Amsterdam"}, PlaceDetailsResolver.parseWikipediaTag(tagged.wikipedia));
        assertNull(PlaceDetailsResolver.parseWikipediaTag("Rijksmuseum"));
        assertNull(PlaceDetailsResolver.parseWikipediaTag("https://nl.wikipedia.org/x"));
        assertTrue(PlaceDetailsResolver.isWikidataId("Q190804"));
        assertFalse(PlaceDetailsResolver.isWikidataId("Q19; drop"));
        assertEquals("ru", PlaceDetailsResolver.primaryLanguage("ru-RU"));
        assertEquals("en", PlaceDetailsResolver.primaryLanguage(null));
        assertEquals("en", PlaceDetailsResolver.primaryLanguage("../x"));
        assertTrue(PlaceDetailsResolver.sitelinkUrl("Q190804", "ru").contains("sitefilter=ruwiki"));
        assertEquals("Рейксмюсеум", PlaceDetailsResolver.parseSitelink(
                "{\"entities\":{\"Q190804\":{\"sitelinks\":{\"ruwiki\":{\"title\":\"Рейксмюсеум\"}}}}}", "Q190804", "ru"));
        assertNull(PlaceDetailsResolver.parseSitelink("{\"entities\":{\"Q190804\":{\"sitelinks\":{}}}}", "Q190804", "ru"));
        assertEquals("https://nl.wikipedia.org/api/rest_v1/page/summary/Rijksmuseum_Amsterdam",
                PlaceDetailsResolver.summaryUrl("nl", "Rijksmuseum Amsterdam"));
        assertEquals("https://ru.wikipedia.org/api/rest_v1/page/summary/%D0%9C%D1%83%D0%B7%D0%B5%D0%B9_(%D0%90)",
                PlaceDetailsResolver.summaryUrl("ru", "Музей (А)").replace("%28", "(").replace("%29", ")"));
        PlaceDetails summary = PlaceDetailsResolver.parseSummary("{\"type\":\"standard\",\"extract\":\"A museum. In Amsterdam.\","
                + "\"thumbnail\":{\"source\":\"https://upload.wikimedia.org/a.jpg\"}}");
        assertEquals("A museum. In Amsterdam.", summary.description);
        assertEquals("https://upload.wikimedia.org/a.jpg", summary.imageUrl);
        assertTrue(PlaceDetailsResolver.parseSummary("{\"type\":\"disambiguation\",\"extract\":\"x\"}").isEmpty());
        assertTrue(PlaceDetailsResolver.parseSummary("{\"extract\":\"x\",\"thumbnail\":{\"source\":\"http://a\"}}").imageUrl == null);
    }

    @Test public void longExtractsAreCutAtASentence() {
        String first = repeat("a", 150) + ". ";
        String extract = first + repeat("b", 200) + ".";
        assertEquals(first.trim(), PlaceDetailsResolver.shortDescription(extract));
        String noSentence = repeat("word ", 80);
        String cut = PlaceDetailsResolver.shortDescription(noSentence);
        assertTrue(cut, cut.endsWith("…") && cut.length() <= 241);
    }

    @Test public void cachedDetailsKeepCardFields() throws Exception {
        PlaceDetails details = new PlaceDetails();
        details.description = "d";
        details.imageUrl = "https://x/a.jpg";
        details.wikidata = "Q1";
        PlaceDetails back = PlaceDetails.fromJson(details.toJson());
        assertEquals("d", back.description);
        assertEquals("https://x/a.jpg", back.imageUrl);
        assertEquals("Q1", back.wikidata);
        details.imageUrl = "http://x/a.jpg";
        assertNull(PlaceDetails.fromJson(details.toJson()).imageUrl);
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }

    @Test public void answersAreKeptPerMessageLanguage() {
        PlaceDetailsResolver.Request point = new PlaceDetailsResolver.Request("1:2", 1, 52.0, 4.0, "Cafe", null, false);
        assertEquals("details3:52.000000,4.000000|cafe", PlaceDetailsResolver.storageKey(point));
        assertEquals("details3:ru:52.000000,4.000000|cafe", PlaceDetailsResolver.storageKey(point.withLanguage("ru-RU")));
        PlaceDetailsResolver.Request museum = new PlaceDetailsResolver.Request("1:3", 1, 52.0, 4.0, "Museum Y", null, false);
        assertNotEquals("Two venues at one point never share an answer",
                PlaceDetailsResolver.storageKey(point), PlaceDetailsResolver.storageKey(museum));
        assertEquals("details3:52.000000,4.000000",
                PlaceDetailsResolver.storageKey(new PlaceDetailsResolver.Request("1:4", 1, 52.0, 4.0, null, null, false)));
        assertNotEquals(PlaceDetailsResolver.storageKey(point.withLanguage("de")),
                PlaceDetailsResolver.storageKey(point.withLanguage("ru")));
        PlaceDetailsResolver.Request link = new PlaceDetailsResolver.Request("1:2", 1, null, null, "Cafe", null, false);
        assertEquals("details3:qb:nl:cafe", PlaceDetailsResolver.storageKey(link.withLanguage("nl")));
        assertNull(PlaceDetailsResolver.storageKey(new PlaceDetailsResolver.Request("1:2", 1, null, null, null, null, false)));
    }

    @Test public void wikimediaErrorBodiesAreFailuresNotMissingArticles() {
        try {
            PlaceDetailsResolver.parseSitelink("{\"error\":{\"code\":\"maxlag\"}}", "Q1", "ru");
            fail("maxlag body must not read as 'no article'");
        } catch (org.json.JSONException expected) {
            // The resolver does not cache this lookup.
        }
        try {
            PlaceDetailsResolver.parseSummary("<html>portal</html>");
            fail("a non-JSON summary must not read as 'no description'");
        } catch (org.json.JSONException expected) {
            // Not cached.
        }
    }

    @Test public void missingWikidataItemIsAPermanentNoArticle() throws Exception {
        assertNull(PlaceDetailsResolver.parseSitelink("{\"entities\":{\"Q9\":{\"id\":\"Q9\",\"missing\":\"\"}}}", "Q9", "ru"));
        assertNull(PlaceDetailsResolver.parseSitelink("{\"entities\":{}}", "Q9", "ru"));
        assertNull(PlaceDetailsResolver.parseSitelink("{\"entities\":{\"Q9\":{\"id\":\"Q9\"}}}", "Q9", "ru"));
    }
}
