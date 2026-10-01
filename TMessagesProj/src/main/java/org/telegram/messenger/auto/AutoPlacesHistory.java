package org.telegram.messenger.auto;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent global frontiers: URL and geo, each for private chats and for groups. Broadcast
 * channels are never requested (the car excludes them), so the page cap is spent on sources the
 * list can show. Offsets belong to a peer, not just a message id.
 */
final class AutoPlacesHistory {
    static final int PAGE = 50;
    /** kind: bit 0 = geo (else URL), bit 1 = groups (else private chats). */
    static final int KINDS = 4;
    /** Pages per stream per head reset: bounds a walk through link-heavy history (50 each). */
    static final int MAX_PAGES = 10;
    static final long HEAD_RESET_INTERVAL_MS = 60_000L;
    static final int DEFAULT_FLOOD_WAIT_SECONDS = 30;
    private static final Pattern FLOOD_SECONDS = Pattern.compile("_(\\d+)$");
    final int kind;
    int offsetRate, offsetId;
    long offsetDialog;
    int date = Integer.MAX_VALUE;
    boolean done, failed, busy;
    int requestId;
    int pages;
    /** Times this page lost the epoch check and was requested again. */
    int staleAttempts;

    AutoPlacesHistory(int kind) { this.kind = kind; }

    TLRPC.TL_messages_searchGlobal request(TLRPC.InputPeer peer) {
        TLRPC.TL_messages_searchGlobal request = new TLRPC.TL_messages_searchGlobal();
        request.q = "";
        request.filter = isUrl() ? new TLRPC.TL_inputMessagesFilterUrl() : new TLRPC.TL_inputMessagesFilterGeo();
        request.users_only = (kind & 2) == 0;
        request.groups_only = (kind & 2) != 0;
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
        next.pages = pages + 1;
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
        // next_rate is 0 when absent; do not depend on the TL flag bit layout.
        next.offsetRate = response.next_rate != 0 ? response.next_rate : last.date;
        next.date = last.date;
        // Older history stays reachable from the phone Places tab; the car shows the newest.
        if (next.pages >= MAX_PAGES) next.done = true;
        return next;
    }

    /** Seconds to wait for FLOOD_WAIT_N-style errors, or -1 for any other error. */
    static int floodWaitSeconds(String errorText) {
        if (errorText == null || !errorText.startsWith("FLOOD_")) return -1;
        Matcher matcher = FLOOD_SECONDS.matcher(errorText);
        if (!matcher.find()) return DEFAULT_FLOOD_WAIT_SECONDS;
        try {
            return Math.max(1, Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            return DEFAULT_FLOOD_WAIT_SECONDS;
        }
    }

    /** Car connectivity flaps; restart the global head at most once per interval. */
    static boolean shouldResetHead(long now, long lastReset) {
        return lastReset == 0 || now - lastReset >= HEAD_RESET_INTERVAL_MS;
    }

    boolean isUrl() {
        return (kind & 1) == 0;
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
            if (resolvingLinks && stream.isUrl() && stream.offsetId != 0) continue;
            if (next == null || stream.date >= next.date) next = stream;
        }
        return next;
    }
}
