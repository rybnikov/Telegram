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
import java.util.Locale;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AutoSpeechLanguageTest {
    private static final Locale DEVICE = new Locale("en", "NL");

    private static List<String> spoken(List<AutoSpeechLanguage.Utterance> utterances) {
        ArrayList<String> result = new ArrayList<>();
        for (AutoSpeechLanguage.Utterance utterance : utterances) {
            result.add(utterance.locale.getLanguage() + ":" + utterance.text);
        }
        return result;
    }

    @Test public void russianMessageIsReadByARussianVoiceAndWordingStaysEnglish() {
        AutoPlaceItem item = new AutoPlaceItem("1:2", 1, 2, 3, "Мама", "Семья", true,
                "Кафе", "Street", 1d, 2d, null, Place.Provider.TELEGRAM, 1000,
                "встретимся у входа", false, "Кафе Пушкин");
        PlaceDetails details = new PlaceDetails();
        details.title = "Кафе Пушкин";
        details.category = "cafe";
        details.address = "Тверской бульвар 26А, Москва";
        details.openingHours = "Mo-Su 10:00-22:00";
        List<List<AutoPlaceSummaryBuilder.Part>> sentences = AutoPlaceSummaryBuilder.buildSentences(item, details, 1000, 4000);
        assertEquals("Кафе Пушкин. Тверской бульвар 26А, Москва. Мама. Семья. встретимся у входа",
                AutoSpeechLanguage.contentText(sentences));
        assertEquals(java.util.Arrays.asList(
                "ru:Кафе Пушкин.",
                "en:cafe.",
                "ru:Тверской бульвар 26А, Москва.",
                "en:Opening hours: Monday to Sunday, 10:00 to 22:00. Sent by",
                "ru:Мама",
                "en:in",
                "ru:Семья",
                "en:just now. Note:",
                "ru:встретимся у входа."), spoken(AutoSpeechLanguage.resolve(sentences, "ru", DEVICE)));
    }

    @Test public void alphabetDecidesWhenDetectionIsMissingOrDisagrees() {
        assertEquals("ru", AutoSpeechLanguage.contentLocale("Мама", null, DEVICE).getLanguage());
        assertEquals("uk", AutoSpeechLanguage.contentLocale("Мамо, привіт", "uk", DEVICE).getLanguage());
        assertEquals("ru", AutoSpeechLanguage.contentLocale("Мама", "de", DEVICE).getLanguage());
        assertEquals("de", AutoSpeechLanguage.contentLocale("Hauptstraße 5", "de", DEVICE).getLanguage());
        assertEquals("en", AutoSpeechLanguage.contentLocale("Hauptstraße 5", "ru", DEVICE).getLanguage());
        assertEquals("en", AutoSpeechLanguage.contentLocale("Cafe", "und", DEVICE).getLanguage());
        assertEquals("en", AutoSpeechLanguage.contentLocale("Cafe", "ru-Latn", DEVICE).getLanguage());
        assertEquals("ja", AutoSpeechLanguage.contentLocale("東京駅", "ja", DEVICE).getLanguage());
        assertEquals("zh", AutoSpeechLanguage.contentLocale("北京", null, DEVICE).getLanguage());
        assertEquals("ko", AutoSpeechLanguage.contentLocale("서울", null, DEVICE).getLanguage());
        assertEquals("he", AutoSpeechLanguage.contentLocale("תל אביב", null, DEVICE).getLanguage());
        assertEquals("en", AutoSpeechLanguage.contentLocale("26", "ru", DEVICE).getLanguage());
        assertEquals("nl", AutoSpeechLanguage.contentLocale("Straat", null, new Locale("nl", "NL")).getLanguage());
    }

    @Test public void englishMessageStaysInOneUtterancePerVoice() {
        AutoPlaceItem item = new AutoPlaceItem("1:2", 1, 2, 3, "Alice", null, false,
                "Cafe", "Street", 1d, 2d, null, Place.Provider.TELEGRAM, 1000, "see you", false, "Cafe");
        List<AutoSpeechLanguage.Utterance> utterances = AutoSpeechLanguage.resolve(
                AutoPlaceSummaryBuilder.buildSentences(item, new PlaceDetails(), 1000, 4000), "en", DEVICE);
        assertEquals(java.util.Collections.singletonList(
                "en:Cafe, no more details. Sent by Alice just now. Note: see you."), spoken(utterances));
    }

    @Test public void speechSentencesRespectTheEngineLimit() {
        AutoPlaceItem item = new AutoPlaceItem("1:2", 1, 2, 3, "Alice", null, false,
                "Cafe", "Street", 1d, 2d, null, Place.Provider.TELEGRAM, 1000, "see you", false, "Cafe");
        assertEquals(1, AutoPlaceSummaryBuilder.buildSentences(item, new PlaceDetails(), 1000, 30).size());
    }
}
