package org.telegram.messenger.auto;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.places.PlaceExtractor;
import org.telegram.tgnet.TLRPC;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AutoPlacesHistoryTest {
    private TLRPC.TL_message message(long peer, int id, int date, String text) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = peer;
        message.id = id;
        message.date = date;
        message.message = text;
        return message;
    }

    @Test public void ordinaryUrlPagesContinueUntilAnOlderMapIsFound() {
        AutoPlacesHistory history = new AutoPlacesHistory(0);
        TLRPC.TL_messages_messagesSlice first = new TLRPC.TL_messages_messagesSlice();
        for (int id = 100; id > 50; id--) first.messages.add(message(42, id, id, "https://news.example/" + id));
        history = history.advance(first);
        assertFalse(history.done);
        assertEquals(51, history.offsetId);
        assertEquals(51, history.offsetRate); // Without next_rate, use the last message date.
        assertSame(history, AutoPlacesHistory.next(new AutoPlacesHistory[]{history}, 0, false));
        TLRPC.TL_messages_messagesSlice second = new TLRPC.TL_messages_messagesSlice();
        second.messages.add(message(43, 7, 50, "https://maps.apple.com/?coordinate=52,4&name=Cafe"));
        assertEquals(1, PlaceExtractor.extract(second.messages.get(0)).size());
        history = history.advance(second);
        assertEquals(43, history.offsetDialog);
        assertEquals(7, history.offsetId);
        assertFalse(history.done); // A sparse/short page is not proof that the stream ended.
        assertTrue(history.advance(new TLRPC.TL_messages_messagesSlice()).done);
    }

    @Test public void nextRateAndPeerArePreservedInTheNextRequest() {
        AutoPlacesHistory history = new AutoPlacesHistory(0);
        TLRPC.TL_messages_messagesSlice response = new TLRPC.TL_messages_messagesSlice();
        response.flags = 1;
        response.next_rate = 999;
        response.messages.add(message(42, 7, 100, "https://example.org"));
        AutoPlacesHistory advanced = history.advance(response);
        assertEquals(0, history.offsetId); // The response may still lose an epoch check.
        TLRPC.TL_inputPeerUser peer = new TLRPC.TL_inputPeerUser();
        peer.user_id = 42;
        TLRPC.TL_messages_searchGlobal request = advanced.request(peer);
        assertEquals(999, request.offset_rate);
        assertEquals(7, request.offset_id);
        assertSame(peer, request.offset_peer);
        assertTrue(request.filter instanceof TLRPC.TL_inputMessagesFilterUrl);
        assertTrue(new AutoPlacesHistory(1).request(null).filter instanceof TLRPC.TL_inputMessagesFilterGeo);
        assertTrue(new AutoPlacesHistory(0).request(null).offset_peer instanceof TLRPC.TL_inputPeerEmpty);
    }

    @Test public void bothFrontiersMustPassTheTwentiethPlaceBeforeStopping() {
        AutoPlacesHistory url = new AutoPlacesHistory(0);
        AutoPlacesHistory geo = new AutoPlacesHistory(1);
        url.date = 90;
        geo.date = 150;
        AutoPlacesHistory[] streams = {url, geo};
        assertSame(geo, AutoPlacesHistory.next(streams, 100, true));
        geo.date = 100;
        assertSame(geo, AutoPlacesHistory.next(streams, 100, true)); // Include tied timestamps.
        geo.date = 99;
        assertNull(AutoPlacesHistory.next(streams, 100, true));
        assertSame(geo, AutoPlacesHistory.next(streams, 0, false)); // Fewer than 20: keep scanning.
        geo.busy = true;
        assertNull(AutoPlacesHistory.next(streams, 0, false));
        geo.busy = false;
        geo.done = true;
        assertSame(url, AutoPlacesHistory.next(streams, 0, false));
    }

    @Test public void sameMessageIdInAnotherChatAdvancesButRepeatedPageFails() {
        AutoPlacesHistory history = new AutoPlacesHistory(0);
        TLRPC.TL_messages_messagesSlice first = new TLRPC.TL_messages_messagesSlice();
        first.messages.add(message(42, 7, 100, ""));
        history = history.advance(first);
        TLRPC.TL_messages_messagesSlice second = new TLRPC.TL_messages_messagesSlice();
        second.messages.add(message(43, 7, 99, ""));
        history = history.advance(second);
        try {
            history.advance(second);
            fail("Repeated page must not create a network loop");
        } catch (IllegalStateException expected) {
            assertFalse(history.done);
        }
    }

    @Test public void telegramGeoSearchDoesNotWaitForSlowHistoricalLinkMetadata() {
        AutoPlacesHistory url = new AutoPlacesHistory(0);
        AutoPlacesHistory geo = new AutoPlacesHistory(1);
        AutoPlacesHistory[] streams = {url, geo};
        assertSame(geo, AutoPlacesHistory.next(streams, 0, false, true));
        url.date = 150;
        url.offsetId = 7;
        geo.date = 100;
        assertSame(geo, AutoPlacesHistory.next(streams, 0, false, true));
        geo.done = true;
        assertNull(AutoPlacesHistory.next(streams, 0, false, true));
        assertSame(url, AutoPlacesHistory.next(streams, 0, false, false));
    }
}
