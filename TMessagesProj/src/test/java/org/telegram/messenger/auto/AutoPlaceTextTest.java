package org.telegram.messenger.auto;

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
public class AutoPlaceTextTest {
    private static TLRPC.TL_message message(String text, TLRPC.MessageEntity... entities) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = text;
        message.entities = new ArrayList<>();
        for (TLRPC.MessageEntity entity : entities) message.entities.add(entity);
        return message;
    }

    private static <T extends TLRPC.MessageEntity> T entity(T entity, String text, String part) {
        entity.offset = text.indexOf(part);
        entity.length = part.length();
        return entity;
    }

    @Test public void linkOnlyCaptionHasNoNote() {
        assertNull(AutoPlaceText.note(message("https://maps.app.goo.gl/AbC123")));
        assertNull(AutoPlaceText.note(message("  maps.app.goo.gl/AbC123 ")));
        assertNull(AutoPlaceText.note(message("geo:52.1,4.2")));
        assertNull(AutoPlaceText.note(message("www.example.com")));
        assertNull(AutoPlaceText.note(message("👉 https://maps.apple.com/?q=Cafe")));
    }

    @Test public void urlsAreRemovedFromOrdinaryText() {
        String text = "Great ramen here https://maps.app.goo.gl/x";
        assertEquals("Great ramen here", AutoPlaceText.note(message(text,
                entity(new TLRPC.TL_messageEntityUrl(), text, "https://maps.app.goo.gl/x"))));
        assertEquals("Meet at the station",
                AutoPlaceText.note(message("Meet at the station: goo.gl/maps/abc\nhttp://x.y/z")));
    }

    @Test public void textLinkKeepsWordsAndDropsTarget() {
        String text = "Dinner at Luigi";
        TLRPC.TL_messageEntityTextUrl link = entity(new TLRPC.TL_messageEntityTextUrl(), text, "Luigi");
        link.url = "https://maps.google.com/?q=Luigi";
        String note = AutoPlaceText.note(message(text, link));
        assertEquals("Dinner at Luigi", note);
        assertFalse(note.contains("http"));
    }

    @Test public void spoilerRangesNeverSurvive() {
        String text = "meet at secret place 🍜 tonight";
        String note = AutoPlaceText.note(message(text,
                entity(new TLRPC.TL_messageEntitySpoiler(), text, "secret")));
        assertEquals("meet at place 🍜 tonight", note);
        String all = "hidden";
        assertNull(AutoPlaceText.note(message(all, entity(new TLRPC.TL_messageEntitySpoiler(), all, all))));
    }

    @Test public void brokenEntityOffsetsAreClamped() {
        TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
        spoiler.offset = 5;
        spoiler.length = 500;
        assertEquals("Hello", AutoPlaceText.note(message("Hello world", spoiler)));
        TLRPC.TL_messageEntitySpoiler negative = new TLRPC.TL_messageEntitySpoiler();
        negative.offset = -3;
        negative.length = 2;
        assertEquals("Hello world", AutoPlaceText.note(message("Hello world", negative)));
    }

    @Test public void linkInsideSpoilerNeverUncoversTheRestOfTheSpoiler() {
        String text = "meet SECRET https://x.y/z MORE here";
        TLRPC.TL_messageEntitySpoiler spoiler = entity(new TLRPC.TL_messageEntitySpoiler(), text, "SECRET https://x.y/z MORE");
        TLRPC.TL_messageEntityUrl url = entity(new TLRPC.TL_messageEntityUrl(), text, "https://x.y/z");
        String note = AutoPlaceText.note(message(text, url, spoiler));
        assertEquals("meet here", note);
        String sameStart = "https://x.y/z SECRET tail";
        TLRPC.TL_messageEntityUrl first = entity(new TLRPC.TL_messageEntityUrl(), sameStart, "https://x.y/z");
        TLRPC.TL_messageEntitySpoiler hidden = entity(new TLRPC.TL_messageEntitySpoiler(), sameStart, "https://x.y/z SECRET");
        String other = AutoPlaceText.note(message(sameStart, first, hidden));
        assertEquals("tail", other);
    }
}
