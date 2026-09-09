package org.telegram.messenger.places;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class PlacesHistoryTest {
    private TLRPC.Message message(int id, int date) {
        TLRPC.TL_message m = new TLRPC.TL_message(); m.id = id; m.date = date; return m;
    }
    @Test public void sparseLinksAdvanceEvenWhenNoPlaceMatches() {
        PlacesHistory cursor = new PlacesHistory(10, 0);
        for (int id = 10000; id > 0; id -= 50) {
            cursor = cursor.advance(Arrays.asList(message(id, id), message(id - 49, id - 49)));
            assertFalse(cursor.done);
        }
        assertEquals(1, cursor.offset);
        assertTrue(cursor.advance(Collections.emptyList()).done);
    }
    @Test public void mergeFillsNewestFrontierAcrossBothDialogsAndFilters() {
        PlacesHistory links = new PlacesHistory(10, 0), geo = new PlacesHistory(10, 1), old = new PlacesHistory(-20, 0);
        links = links.advance(Arrays.asList(message(100, 100), message(90, 90)));
        geo = geo.advance(Arrays.asList(message(80, 80), message(70, 70)));
        assertSame(old, PlacesHistory.next(Arrays.asList(links, geo, old)));
        old = old.advance(Arrays.asList(message(200, 20)));
        assertSame(links, PlacesHistory.next(Arrays.asList(links, geo, old)));
    }
    @Test public void overlapAdvancesButRepeatedPageIsAnErrorNotEndOfHistory() {
        PlacesHistory cursor = new PlacesHistory(10, 0).advance(Arrays.asList(message(10, 10), message(9, 9)));
        PlacesHistory next = cursor.advance(Arrays.asList(message(9, 9), message(8, 8)));
        assertEquals(8, next.offset);
        try { next.advance(Arrays.asList(message(9, 9), message(8, 8))); fail(); }
        catch (IllegalStateException expected) { assertFalse(next.done); }
    }
}
