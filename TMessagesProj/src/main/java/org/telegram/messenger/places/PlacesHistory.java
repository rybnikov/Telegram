package org.telegram.messenger.places;

import org.telegram.tgnet.TLRPC;
import java.util.List;

/** Cursor advances over all results, not only map links. Empty pages alone end a stream. */
public final class PlacesHistory {
    public final long dialog;
    public final int kind;
    public int offset, date = Integer.MAX_VALUE;
    public boolean done;
    public int head, refreshUntil;
    public boolean refresh;

    public PlacesHistory(long dialog, int kind) { this.dialog = dialog; this.kind = kind; }

    public PlacesHistory advance(List<TLRPC.Message> page) {
        PlacesHistory next = new PlacesHistory(dialog, kind);
        next.head = head;
        next.offset = offset;
        next.date = date;
        next.done = page.isEmpty();
        for (TLRPC.Message message : page) {
            next.head = Math.max(next.head, message.id);
            if (message.id > 0 && (next.offset == 0 || message.id < next.offset)) next.offset = message.id;
            next.date = Math.min(next.date, message.date);
        }
        if (!next.done && next.offset == offset) throw new IllegalStateException("Search cursor did not advance");
        return next;
    }

    public static PlacesHistory next(List<PlacesHistory> streams) {
        PlacesHistory next = null;
        for (PlacesHistory stream : streams) if (!stream.done && (next == null || stream.date > next.date)) next = stream;
        return next;
    }
}
