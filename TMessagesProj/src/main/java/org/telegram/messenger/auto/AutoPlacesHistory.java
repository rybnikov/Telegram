package org.telegram.messenger.auto;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

/** Independent global URL/geo frontiers. Offsets belong to a peer, not just a message id. */
final class AutoPlacesHistory {
    static final int PAGE = 50;
    final int kind;
    int offsetRate, offsetId;
    long offsetDialog;
    int date = Integer.MAX_VALUE;
    boolean done, failed, busy;
    int requestId;

    AutoPlacesHistory(int kind) { this.kind = kind; }

    TLRPC.TL_messages_searchGlobal request(TLRPC.InputPeer peer) {
        TLRPC.TL_messages_searchGlobal request = new TLRPC.TL_messages_searchGlobal();
        request.q = "";
        request.filter = kind == 0 ? new TLRPC.TL_inputMessagesFilterUrl() : new TLRPC.TL_inputMessagesFilterGeo();
        request.offset_rate = offsetRate;
        request.offset_id = offsetId;
        request.offset_peer = offsetDialog == 0 ? new TLRPC.TL_inputPeerEmpty() : peer;
        request.limit = PAGE;
        return request;
    }

    AutoPlacesHistory advance(TLRPC.messages_Messages response) {
        AutoPlacesHistory next = new AutoPlacesHistory(kind);
        next.offsetRate = offsetRate;
        next.offsetId = offsetId;
        next.offsetDialog = offsetDialog;
        next.date = date;
        if (response.messages.isEmpty()) {
            next.done = true;
            return next;
        }
        TLRPC.Message last = response.messages.get(response.messages.size() - 1);
        long dialog = MessageObject.getDialogId(last);
        if (last.id == offsetId && dialog == offsetDialog) {
            throw new IllegalStateException("Global Places search did not advance");
        }
        next.offsetId = last.id;
        next.offsetDialog = dialog;
        next.offsetRate = (response.flags & 1) != 0 ? response.next_rate : last.date;
        next.date = last.date;
        return next;
    }

    boolean covers(int oldestPlaceDate) {
        // Equal timestamps can still contain newer rows in another peer or the other stream.
        return done || oldestPlaceDate > date;
    }

    static AutoPlacesHistory next(AutoPlacesHistory[] streams, int oldestPlaceDate, boolean enough) {
        return next(streams, oldestPlaceDate, enough, false);
    }

    static AutoPlacesHistory next(AutoPlacesHistory[] streams, int oldestPlaceDate, boolean enough,
                                  boolean resolvingLinks) {
        AutoPlacesHistory next = null;
        for (AutoPlacesHistory stream : streams) {
            if (stream.busy) return null;
            if (stream.done || stream.failed || enough && stream.covers(oldestPlaceDate)) continue;
            if (resolvingLinks && stream.kind == 0 && stream.offsetId != 0) continue;
            if (next == null || stream.date >= next.date) next = stream;
        }
        return next;
    }
}
