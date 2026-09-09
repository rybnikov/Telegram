package org.telegram.messenger.places;

import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.duress.EmergencyPasscode;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import org.telegram.messenger.SharedConfig;
import java.util.Locale;

/** Local-first, resumable union of URL and geo searches. Owned by its reader, not a UI widget. */
public final class PlacesRepository implements NotificationCenter.NotificationCenterDelegate {
    public enum State { LOADING, READY, EMPTY, END, ERROR }
    private static final int PAGE = 50;
    public static final Comparator<PlaceEntry> NEWEST_FIRST = (a, b) -> {
        int date = Integer.compare(b.message.messageOwner.date, a.message.messageOwner.date);
        if (date != 0) return date;
        int dialog = Long.compare(b.message.getDialogId(), a.message.getDialogId());
        return dialog != 0 ? dialog : Integer.compare(b.message.getId(), a.message.getId());
    };
    private static final int[] EVENTS = { NotificationCenter.didReceiveNewMessages,
            NotificationCenter.replaceMessagesObjects, NotificationCenter.messagesDeleted,
            NotificationCenter.removeAllMessagesFromDialog, NotificationCenter.dialogsNeedReload };
    private final int account;
    private final long dialog, topic;
    private long merged;
    private final MessagesStorage storage;
    private final Runnable listener;
    private final ArrayList<PlacesHistory> streams = new ArrayList<>();
    private final HashSet<String> searchMatches = new HashSet<>();
    public final ArrayList<PlaceEntry> entries = new ArrayList<>();
    public State state = State.LOADING;
    private boolean closed, initialized, busy;
    private int generation, requestId, target = PAGE;
    private String query = "";

    public PlacesRepository(int account, long dialog, long topic, long merged, Runnable listener) {
        this.account = account;
        this.dialog = dialog;
        this.topic = topic;
        this.merged = topic == 0 ? merged : 0;
        this.listener = listener;
        storage = MessagesStorage.getInstance(account);
        for (int event : EVENTS) NotificationCenter.getInstance(account).addObserver(this, event);
    }

    private boolean hidden() {
        return EmergencyPasscode.isHidden(account, dialog)
                || topic != 0 && dialog == UserConfig.getInstance(account).getClientUserId() && EmergencyPasscode.isHidden(account, topic);
    }

    public void start() {
        if (initialized || closed) return;
        initialized = true;
        resetStreams();
        read(true, true);
    }

    public void search(String text) {
        text = text == null ? "" : text.trim();
        if (query.equals(text) && initialized) return;
        cancel();
        query = text;
        target = PAGE;
        searchMatches.clear();
        entries.clear();
        initialized = true;
        resetStreams();
        read(true, true);
    }

    private void resetStreams() {
        streams.clear();
        for (long did : new long[]{dialog, merged}) {
            if (did == 0 || EmergencyPasscode.isHidden(account, did)) continue;
            for (int kind = 0; kind < 2; kind++) {
                PlacesHistory stream = new PlacesHistory(did, kind);
                stream.done = DialogObject.isEncryptedDialog(did);
                streams.add(stream);
            }
        }
    }

    public void loadMore() {
        if (busy || closed || state == State.END || state == State.EMPTY) return;
        if (!initialized) { start(); return; }
        if (state != State.ERROR) target += PAGE;
        read(false, true);
    }

    public void retry() {
        if (!busy && !closed) read(false, true);
    }

    public boolean hasQuery() {
        return !query.isEmpty();
    }

    public void setMergedDialog(long merged) {
        if (topic != 0 || this.merged == merged || closed) return;
        this.merged = merged;
        if (!initialized) {
            resetStreams();
            return;
        }
        cancel();
        resetStreams();
        read(true, true);
    }

    private void read(boolean restore, boolean continueLoading) {
        if (closed) return;
        if (hidden()) { entries.clear(); state = State.EMPTY; listener.run(); return; }
        busy = true;
        state = State.LOADING;
        listener.run();
        int token = generation;
        String search = query.toLowerCase(Locale.ROOT);
        ArrayList<PlacesHistory> snapshot = new ArrayList<>(streams);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<TLRPC.Message> messages = new ArrayList<>();
            ArrayList<int[]> positions = new ArrayList<>();
            HashMap<String, JSONObject> metadata = new HashMap<>();
            Exception failure = null;
            boolean pendingLocal = false;
            try {
                SQLiteDatabase db = storage.getDatabase();
                for (long did : new long[]{dialog, merged}) {
                    if (did == 0 || EmergencyPasscode.isHidden(account, did)) continue;
                    pendingLocal |= PlacesStorage.drain(storage, account, did);
                    messages.addAll(PlacesStorage.read(db, account, did, topic));
                }
                if (SharedConfig.extendedPreviews && !DialogObject.isEncryptedDialog(dialog)) {
                    SQLiteCursor c = db.queryFinalized("SELECT url,data FROM places_meta_v1 WHERE time>" + (System.currentTimeMillis() / 1000 - 604800));
                    try { while (c.next()) metadata.put(c.stringValue(0), new JSONObject(c.stringValue(1))); }
                    finally { c.dispose(); }
                }
                if (restore && search.isEmpty()) {
                    for (PlacesHistory stream : snapshot) {
                        SQLiteCursor c = db.queryFinalized("SELECT offset,date,done,head FROM places_state_v1 WHERE uid=" + stream.dialog + " AND topic=" + topic + " AND stream=" + stream.kind);
                        try { positions.add(c.next() ? new int[]{c.intValue(0), c.intValue(1), c.intValue(2), c.intValue(3)} : null); }
                        finally { c.dispose(); }
                    }
                }
            } catch (Exception e) { storage.checkSQLException(e); failure = e; }
            boolean failed = failure != null;
            final boolean localMore = pendingLocal;
            AndroidUtilities.runOnUIThread(() -> {
                if (closed || token != generation) return;
                busy = false;
                if (hidden()) { entries.clear(); state = State.EMPTY; listener.run(); return; }
                if (failed) { state = State.ERROR; listener.run(); return; }
                for (int i = 0; i < positions.size() && i < streams.size(); i++) {
                    int[] p = positions.get(i);
                    if (p != null) {
                        PlacesHistory history = streams.get(i);
                        history.offset = p[0]; history.date = p[1]; history.done |= p[2] != 0; history.head = p[3];
                        if ((history.head > 0 || history.done) && !DialogObject.isEncryptedDialog(history.dialog)) {
                            PlacesHistory refresh = new PlacesHistory(history.dialog, history.kind);
                            refresh.refresh = true;
                            refresh.refreshUntil = history.head;
                            streams.add(refresh);
                        }
                    }
                }
                entries.clear();
                HashSet<String> seen = new HashSet<>();
                for (TLRPC.Message message : messages) {
                    if (EmergencyPasscode.isHidden(account, message.dialog_id)) continue;
                    if (message.dialog_id == UserConfig.getInstance(account).getClientUserId()
                            && EmergencyPasscode.isHidden(account, MessageObject.getSavedDialogId(message.dialog_id, message))) continue;
                    PlaceEntry entry = new PlaceEntry(new MessageObject(account, message, false, true));
                    for (Place place : entry.places) {
                        JSONObject cached = metadata.get(place.originalUrl);
                        if (cached != null && !place.spoiler) PlacesResolver.apply(place, cached);
                    }
                    String searchable = message.message == null ? "" : message.message;
                    for (Place p : entry.places) {
                        searchable += " " + p.displayTitle() + " " + p.providerName();
                        if (p.address != null) searchable += " " + p.address;
                    }
                    if (!entry.places.isEmpty() && seen.add(entry.key()) && (search.isEmpty()
                            || searchMatches.contains(entry.key()) || searchable.toLowerCase(Locale.ROOT).contains(search))) entries.add(entry);
                }
                Collections.sort(entries, NEWEST_FIRST);
                PlacesHistory next = nextStream();
                // Don't stop at a page full of ordinary links. Scan until the merged frontier
                // has passed the desired destination, or every stream reports an empty page.
                boolean enough = entries.size() >= target && (next == null
                        || entries.get(target - 1).message.messageOwner.date > next.date);
                boolean need = next != null && !enough;
                state = next == null ? (entries.isEmpty() ? State.EMPTY : State.END) : State.READY;
                listener.run();
                if (localMore) read(false, continueLoading);
                else if (continueLoading && need) fetch(next);
            });
        });
    }

    private PlacesHistory nextStream() { return PlacesHistory.next(streams); }

    private void fetch(PlacesHistory stream) {
        if (busy || closed || hidden()) return;
        busy = true;
        state = State.LOADING;
        listener.run();
        int token = generation;
        storage.getStorageQueue().postRunnable(() -> {
            try {
                long epoch = PlacesStorage.epoch(storage.getDatabase());
                AndroidUtilities.runOnUIThread(() -> send(stream, token, epoch));
            } catch (Exception e) {
                storage.checkSQLException(e);
                AndroidUtilities.runOnUIThread(() -> fail(token));
            }
        });
    }

    public static TLRPC.TL_messages_search request(int account, long dialog, long topic, int kind, int offset, String query) {
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = MessagesController.getInstance(account).getInputPeer(dialog);
        req.q = query;
        req.limit = PAGE;
        req.offset_id = offset;
        req.filter = kind == 0 ? new TLRPC.TL_inputMessagesFilterUrl() : new TLRPC.TL_inputMessagesFilterGeo();
        long self = UserConfig.getInstance(account).getClientUserId();
        applyScope(req, dialog, topic, self, topic != 0 && dialog == self ? MessagesController.getInstance(account).getInputPeer(topic) : null);
        return req;
    }

    static void applyScope(TLRPC.TL_messages_search req, long dialog, long topic, long self, TLRPC.InputPeer savedPeer) {
        if (topic == 0) return;
        if (dialog == self) { req.flags |= 4; req.saved_peer_id = savedPeer; }
        else { req.flags |= 2; req.top_msg_id = (int) topic; }
    }

    private void send(PlacesHistory stream, int token, long epoch) {
        if (closed || token != generation) return;
        TLRPC.TL_messages_search req = request(account, stream.dialog, topic, stream.kind, stream.offset, query);
        if (hidden() || req.peer == null || req.peer instanceof TLRPC.TL_inputPeerEmpty) { fail(token); return; }
        final boolean persist = query.isEmpty() && !stream.refresh;
        requestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (closed || token != generation) return;
            requestId = 0;
            if (error != null || !(response instanceof TLRPC.messages_Messages)) { fail(token); return; }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            final PlacesHistory next;
            try { next = stream.advance(res.messages); }
            catch (IllegalStateException e) { fail(token); return; }
            for (TLRPC.Message m : res.messages) m.dialog_id = stream.dialog;
            final int nextOffset = next.offset, nextDate = next.date;
            final boolean done = next.done || stream.refresh && nextOffset <= stream.refreshUntil;
            MessagesController.getInstance(account).putUsers(res.users, false);
            MessagesController.getInstance(account).putChats(res.chats, false);
            storage.putUsersAndChats(res.users, res.chats, true, true);
            MessagesController.getInstance(account).removeDeletedMessagesFromPlacesSearch(stream.dialog, res.messages);
            storage.getStorageQueue().postRunnable(() -> {
                boolean accepted = false;
                try {
                    SQLiteDatabase db = storage.getDatabase();
                    if (epoch == PlacesStorage.epoch(db)) {
                        db.beginTransaction();
                        try {
                            for (TLRPC.Message m : res.messages) {
                                long messageTopic = topic != 0 ? topic : MessageObject.getTopicId(account, m, storage.getForumTypeFlags(stream.dialog));
                                PlacesStorage.put(db, m, messageTopic);
                            }
                            if (persist) PlacesStorage.saveCursor(db, stream.dialog, topic, stream.kind, nextOffset, nextDate, done, next.head);
                            else if (stream.refresh && done) db.executeFast("UPDATE places_state_v1 SET head=MAX(head," + next.head + ") WHERE uid=" + stream.dialog + " AND topic=" + topic + " AND stream=" + stream.kind).stepThis().dispose();
                        } finally { db.commitTransaction(); }
                        accepted = true;
                    }
                } catch (Exception e) { storage.checkSQLException(e); }
                final boolean saved = accepted;
                AndroidUtilities.runOnUIThread(() -> {
                    if (closed || token != generation) return;
                    busy = false;
                    if (saved) {
                        stream.offset = nextOffset; stream.date = nextDate; stream.done = done; stream.head = next.head;
                        if (!query.isEmpty()) for (TLRPC.Message m : res.messages) searchMatches.add(m.dialog_id + ":" + m.id);
                        read(false, true);
                    } else {
                        // An intervening edit/delete won. Never resurrect its stale response.
                        fail(token);
                    }
                });
            });
        }));
    }

    private void fail(int token) {
        if (closed || generation != token) return;
        busy = false; state = State.ERROR; listener.run();
    }

    private void cancel() {
        generation++;
        if (requestId != 0) ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
        requestId = 0; busy = false;
    }

    private final Runnable refresh = () -> { if (initialized && !closed) { cancel(); read(false, true); } };
    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.messagesDeleted && args.length > 0 && args[0] instanceof Long
                && (Long) args[0] != dialog && (Long) args[0] != merged) return;
        if ((id == NotificationCenter.didReceiveNewMessages || id == NotificationCenter.replaceMessagesObjects)
                && args.length > 1 && args[1] instanceof ArrayList) {
            ArrayList<?> values = (ArrayList<?>) args[1];
            ArrayList<MessageObject> updates = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof MessageObject) updates.add((MessageObject) value);
            }
            if (!updates.isEmpty()) {
                cancel();
                storage.getStorageQueue().postRunnable(() -> {
                    try {
                        PlacesStorage.indexMessages(storage, this.account, updates);
                    } catch (Exception e) {
                        storage.checkSQLException(e);
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        if (closed) return;
                        if (!query.isEmpty()) {
                            String activeQuery = query;
                            query = "";
                            search(activeQuery);
                        } else {
                            read(false, true);
                        }
                    });
                });
                return;
            }
        }
        AndroidUtilities.cancelRunOnUIThread(refresh);
        AndroidUtilities.runOnUIThread(refresh, 150);
    }
    public void close() {
        closed = true; cancel();
        AndroidUtilities.cancelRunOnUIThread(refresh);
        for (int event : EVENTS) NotificationCenter.getInstance(account).removeObserver(this, event);
    }
}
