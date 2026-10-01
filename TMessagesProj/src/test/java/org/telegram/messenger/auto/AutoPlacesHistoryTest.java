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

    @Test public void walkStopsAfterThePageCap() {
        AutoPlacesHistory history = new AutoPlacesHistory(0);
        for (int page = 0; page < AutoPlacesHistory.MAX_PAGES; page++) {
            assertFalse("page " + page, history.done);
            TLRPC.TL_messages_messagesSlice response = new TLRPC.TL_messages_messagesSlice();
            response.messages.add(message(42, 1000 - page, 1000 - page, "https://news.example/" + page));
            history = history.advance(response);
        }
        assertTrue(history.done);
        assertNull(AutoPlacesHistory.next(new AutoPlacesHistory[]{history}, 0, false));
    }

    @Test public void floodWaitIsParsed() {
        assertEquals(17, AutoPlacesHistory.floodWaitSeconds("FLOOD_WAIT_17"));
        assertEquals(5, AutoPlacesHistory.floodWaitSeconds("FLOOD_PREMIUM_WAIT_5"));
        assertEquals(30, AutoPlacesHistory.floodWaitSeconds("FLOOD_WAIT_X"));
        assertEquals(1, AutoPlacesHistory.floodWaitSeconds("FLOOD_WAIT_0"));
        assertEquals(-1, AutoPlacesHistory.floodWaitSeconds("INPUT_FILTER_INVALID"));
        assertEquals(-1, AutoPlacesHistory.floodWaitSeconds(null));
    }

    @Test public void headResetIsThrottledAcrossReconnects() {
        assertTrue(AutoPlacesHistory.shouldResetHead(5_000, 0));
        assertFalse(AutoPlacesHistory.shouldResetHead(64_999, 5_000));
        assertTrue(AutoPlacesHistory.shouldResetHead(65_000, 5_000));
    }

    @Test public void nextRateIsUsedWithoutRelyingOnFlagBits() {
        TLRPC.TL_messages_messagesSlice response = new TLRPC.TL_messages_messagesSlice();
        response.messages.add(message(42, 10, 500, "https://maps.apple.com/?q=Cafe"));
        response.next_rate = 777;
        assertEquals(777, new AutoPlacesHistory(0).advance(response).offsetRate);
    }

    @Test public void repositoryNeverBlocksTheUiThreadOnStorage() throws Exception {
        java.nio.file.Path current = java.nio.file.Paths.get("").toAbsolutePath();
        String relative = "TMessagesProj/src/main/java/org/telegram/messenger/auto/AutoPlacesRepository.java";
        while (current != null && !java.nio.file.Files.isRegularFile(current.resolve(relative))) current = current.getParent();
        assertNotNull(current);
        String source = new String(java.nio.file.Files.readAllBytes(current.resolve(relative)), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("getChatSync blocks the UI thread on the storage queue", source.contains("getChatSync("));
        assertFalse(source.contains("getUserSync("));
    }

    @Test public void streamsAreSplitByAudienceAndNeverAskForChannels() {
        assertEquals(4, AutoPlacesHistory.KINDS);
        for (int kind = 0; kind < AutoPlacesHistory.KINDS; kind++) {
            TLRPC.TL_messages_searchGlobal request = new AutoPlacesHistory(kind).request(null);
            assertEquals((kind & 1) == 0, request.filter instanceof TLRPC.TL_inputMessagesFilterUrl);
            assertEquals((kind & 1) != 0, request.filter instanceof TLRPC.TL_inputMessagesFilterGeo);
            assertEquals((kind & 2) == 0, request.users_only);
            assertEquals((kind & 2) != 0, request.groups_only);
            assertFalse(request.broadcasts_only);
        }
    }
}
