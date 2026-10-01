package org.telegram.messenger.places;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;

/** All methods run on the owning account's storage queue. No separate database or account singleton. */
public final class PlacesStorage {
    // Broadcast channels are excluded by the reader, which can tell a channel from a supergroup;
    // the stored flag is set for both.
    private static final String RECENT_COLUMNS = "SELECT data, uid, mid, date FROM places_v1 ";
    private static final String RECENT_ORDER = " ORDER BY date DESC, mid DESC, uid DESC LIMIT ?";
    // Keyset, not OFFSET: the drain inserts rows between pages. `date <= ?` keeps the index range.
    private static final String READ_RECENT_SQL = RECENT_COLUMNS + RECENT_ORDER;
    private static final String READ_RECENT_AFTER_SQL = RECENT_COLUMNS
            + "WHERE date <= ? AND (date < ? OR mid < ? OR (mid = ? AND uid < ?))" + RECENT_ORDER;
    /**
     * Epoch row 2 moves only on deletions, history clears and edits, never on ordinary inserts:
     * the account-wide search barrier uses it, since row 1 moves with every message write.
     */
    static final String DELETION_EPOCH_ROW_SQL = "INSERT OR IGNORE INTO places_epoch_v1 VALUES(2, 0)";
    static final String BUMP_BOTH_EPOCHS_SQL = "UPDATE places_epoch_v1 SET version = version + 1 WHERE id IN (1, 2)";
    static final int SEED_WINDOW_SECONDS = 60 * 24 * 60 * 60;
    static final String RECENT_INDEX_SQL = "CREATE INDEX IF NOT EXISTS places_recent_v1 ON places_v1(date DESC, mid DESC, uid DESC)";
    private PlacesStorage() {}

    public interface SchemaExecutor { void execute(String sql) throws SQLiteException; }

    public static void createSchema(SQLiteDatabase db) throws SQLiteException {
        createSchema(sql -> db.executeFast(sql).stepThis().dispose());
    }

    public static void createSchema(SchemaExecutor db) throws SQLiteException {
        db.execute("CREATE TABLE IF NOT EXISTS places_v1(uid INTEGER, mid INTEGER, topic INTEGER, date INTEGER, data BLOB, channel INTEGER, PRIMARY KEY(uid, mid))");
        db.execute("CREATE INDEX IF NOT EXISTS places_date_v1 ON places_v1(uid, topic, date DESC, mid DESC)");
        db.execute(RECENT_INDEX_SQL);
        db.execute("CREATE TABLE IF NOT EXISTS places_pending_v1(uid INTEGER, mid INTEGER, source INTEGER, PRIMARY KEY(uid, mid, source))");
        db.execute("CREATE TABLE IF NOT EXISTS places_local_v1(uid INTEGER, source INTEGER, PRIMARY KEY(uid, source))");
        db.execute("CREATE TABLE IF NOT EXISTS places_state_v1(uid INTEGER, topic INTEGER, stream INTEGER, offset INTEGER, date INTEGER, done INTEGER, head INTEGER, PRIMARY KEY(uid, topic, stream))");
        db.execute("CREATE TABLE IF NOT EXISTS places_meta_v1(url TEXT PRIMARY KEY, data TEXT, time INTEGER)");
        db.execute("CREATE TABLE IF NOT EXISTS places_epoch_v1(id INTEGER PRIMARY KEY, version INTEGER)");
        db.execute("INSERT OR IGNORE INTO places_epoch_v1 VALUES(1, 0)");
        db.execute(DELETION_EPOCH_ROW_SQL);
        // Capture every write path, including edits, live locations, history loads and local sends.
        for (int source = 0; source < 2; source++) {
            String table = source == 0 ? "messages_v2" : "messages_topics";
            for (String event : new String[]{"INSERT", "UPDATE OF data"}) {
                String name = event.startsWith("INSERT") ? "insert" : "update";
                db.execute("CREATE TRIGGER IF NOT EXISTS places_" + source + "_" + name + "_v1 AFTER " + event + " ON " + table + " BEGIN "
                        + "INSERT OR REPLACE INTO places_pending_v1 VALUES(NEW.uid, NEW.mid," + source + "); "
                        + "UPDATE places_epoch_v1 SET version = version + 1 WHERE id = 1; END");
            }
            db.execute("CREATE TRIGGER IF NOT EXISTS places_" + source + "_delete_v1 AFTER DELETE ON " + table + " BEGIN "
                    + "DELETE FROM places_pending_v1 WHERE uid = OLD.uid AND mid = OLD.mid AND source = " + source + "; "
                    + "DELETE FROM places_v1 WHERE uid = OLD.uid AND mid = OLD.mid "
                    + "AND NOT EXISTS(SELECT 1 FROM messages_v2 WHERE uid = OLD.uid AND mid = OLD.mid) "
                    + "AND NOT EXISTS(SELECT 1 FROM messages_topics WHERE uid = OLD.uid AND mid = OLD.mid); "
                    + "UPDATE places_epoch_v1 SET version = version + 1 WHERE id = 1; END");
        }
    }

    private static void seedDialog(SQLiteDatabase db, long dialog) throws Exception {
        for (int source = 0; source < 2; source++) {
            SQLiteCursor cursor = db.queryFinalized("SELECT 1 FROM places_local_v1 WHERE uid=" + dialog + " AND source=" + source);
            boolean seeded;
            try {
                seeded = cursor.next();
            } finally {
                cursor.dispose();
            }
            if (seeded) continue;
            String table = source == 0 ? "messages_v2" : "messages_topics";
            db.beginTransaction();
            try {
                db.executeFast("INSERT OR IGNORE INTO places_pending_v1 SELECT uid, mid," + source
                        + " FROM " + table + " WHERE uid=" + dialog).stepThis().dispose();
                db.executeFast("INSERT OR IGNORE INTO places_local_v1 VALUES(" + dialog + "," + source + ")").stepThis().dispose();
            } finally {
                db.commitTransaction();
            }
        }
    }

    public interface DialogFilter { boolean skip(long dialog); }

    /**
     * Auto seeds a recent cache window without marking the phone's full backfill complete.
     * {@code skip} dialogs (broadcast channels the car never shows) are marked seeded but not
     * queued, so their history is not parsed into the index for nothing.
     */
    public static boolean seedRecentDialogsBatch(SQLiteDatabase db, DialogFilter skip) throws Exception {
        boolean more = false;
        for (int source = 0; source < 2; source++) {
            ArrayList<Long> dialogs = new ArrayList<>();
            // A recent window: dialogs quiet for longer are reached by the server search.
            int since = (int) (System.currentTimeMillis() / 1000) - SEED_WINDOW_SECONDS;
            SQLiteCursor cursor = db.queryFinalized(recentDialogsSql(), since, source + 2, 20);
            try {
                while (cursor.next()) dialogs.add(cursor.longValue(0));
            } finally {
                cursor.dispose();
            }
            db.beginTransaction();
            try {
                for (long dialog : dialogs) {
                    if (!skip.skip(dialog)) {
                        SQLitePreparedStatement seed = db.executeFast(recentSeedSql(source));
                        try {
                            seed.bindLong(1, dialog);
                            seed.bindInteger(2, 100);
                            seed.step();
                        } finally {
                            seed.dispose();
                        }
                    }
                    // 0/1 mean the complete phone backfill; 2/3 mean only Auto's recent window.
                    db.executeFast("INSERT OR IGNORE INTO places_local_v1 VALUES(" + dialog + "," + (source + 2) + ")").stepThis().dispose();
                }
            } finally {
                db.commitTransaction();
            }
            more |= dialogs.size() == 20;
        }
        return more;
    }

    /** Args: oldest dialog date to include (seconds), marker source, batch size. */
    static String recentDialogsSql() {
        return "SELECT did FROM dialogs WHERE date > ? AND NOT EXISTS (SELECT 1 FROM places_local_v1 "
                + "WHERE uid=did AND source=?) ORDER BY date DESC LIMIT ?";
    }

    static String recentSeedSql(int source) {
        String table = source == 0 ? "messages_v2" : "messages_topics";
        return "INSERT OR IGNORE INTO places_pending_v1 SELECT uid, mid," + source
                + " FROM " + table + " WHERE uid=? ORDER BY date DESC, mid DESC LIMIT ?";
    }

    /**
     * Databases migrated to 180 before the account-wide reader existed lack the recent index.
     * Idempotent and cheap once present; callers run it once per session on the storage queue,
     * so no database version bump is needed.
     */
    public static void ensureRecentIndex(SQLiteDatabase db) throws SQLiteException {
        db.executeFast(RECENT_INDEX_SQL).stepThis().dispose();
        db.executeFast(deletionEpochRowSql()).stepThis().dispose();
    }

    /** See {@link #DELETION_EPOCH_ROW_SQL}. */
    public static long deletionEpoch(SQLiteDatabase db) throws Exception {
        SQLiteCursor c = db.queryFinalized("SELECT version FROM places_epoch_v1 WHERE id = 2");
        try { return c.next() ? c.longValue(0) : 0; } finally { c.dispose(); }
    }

    public static long epoch(SQLiteDatabase db) throws Exception {
        SQLiteCursor c = db.queryFinalized("SELECT version FROM places_epoch_v1 WHERE id = 1");
        try { return c.next() ? c.longValue(0) : 0; } finally { c.dispose(); }
    }

    /** Also deletes search-only rows that never existed in messages_v2. Invalidates in-flight requests. */
    public static void invalidate(SQLiteDatabase db, String predicate, String statePredicate) throws SQLiteException {
        db.executeFast(bumpBothEpochsSql()).stepThis().dispose();
        db.executeFast("DELETE FROM places_v1 WHERE " + predicate).stepThis().dispose();
        if (statePredicate != null) {
            db.executeFast("DELETE FROM places_state_v1 WHERE " + statePredicate).stepThis().dispose();
        }
    }

    public static void invalidateDialog(SQLiteDatabase db, long dialog) throws SQLiteException {
        invalidate(db, "uid=" + dialog, "uid=" + dialog);
        // Some clear-history paths retain a last message. Let the next reader
        // discover that remainder instead of treating the dialog as backfilled.
        db.executeFast("DELETE FROM places_local_v1 WHERE uid=" + dialog).stepThis().dispose();
    }

    public static boolean drain(MessagesStorage storage, int account, long dialog) throws Exception {
        SQLiteDatabase db = storage.getDatabase();
        // Existing databases are backfilled only for a dialog that is actually opened.
        // Parsing then stays bounded to 200 source messages per storage-queue pass.
        seedDialog(db, dialog);
        SQLiteCursor c = db.queryFinalized("SELECT CASE WHEN p.source=0 THEN (SELECT data FROM messages_v2 WHERE uid=p.uid AND mid=p.mid) "
                + "ELSE (SELECT data FROM messages_topics WHERE uid=p.uid AND mid=p.mid LIMIT 1) END, p.uid, p.mid, p.source "
                + "FROM places_pending_v1 p WHERE p.uid=" + dialog + " LIMIT 200");
        ArrayList<TLRPC.Message> batch = new ArrayList<>();
        ArrayList<Integer> mids = new ArrayList<>();
        ArrayList<Integer> sources = new ArrayList<>();
        try {
            while (c.next()) {
                batch.add(readMessage(c, account));
                mids.add(c.intValue(2));
                sources.add(c.intValue(3));
            }
        } finally { c.dispose(); }
        db.beginTransaction();
        try {
            for (int i = 0; i < batch.size(); i++) {
                TLRPC.Message message = batch.get(i);
                if (message != null) {
                    long topic = MessageObject.getTopicId(account, message, storage.getForumTypeFlags(dialog));
                    put(db, message, topic);
                }
                db.executeFast("DELETE FROM places_pending_v1 WHERE uid=" + dialog + " AND mid=" + mids.get(i)
                        + " AND source=" + sources.get(i)).stepThis().dispose();
            }
            // A cache eviction may leave a pending key without its source row.
            db.executeFast("DELETE FROM places_pending_v1 WHERE uid=" + dialog
                    + " AND ((source=0 AND NOT EXISTS(SELECT 1 FROM messages_v2 m WHERE m.uid=places_pending_v1.uid AND m.mid=places_pending_v1.mid))"
                    + " OR (source=1 AND NOT EXISTS(SELECT 1 FROM messages_topics m WHERE m.uid=places_pending_v1.uid AND m.mid=places_pending_v1.mid)))").stepThis().dispose();
        } finally { db.commitTransaction(); }
        SQLiteCursor remaining = db.queryFinalized("SELECT 1 FROM places_pending_v1 WHERE uid=" + dialog + " LIMIT 1");
        try {
            return remaining.next();
        } finally {
            remaining.dispose();
        }
    }

    /** Processes a bounded account-wide pending batch without triggering any history seed. */
    public static boolean drainPendingBatch(MessagesStorage storage, int account) throws Exception {
        SQLiteDatabase db = storage.getDatabase();
        SQLiteCursor c = db.queryFinalized(pendingBatchSql());
        ArrayList<TLRPC.Message> batch = new ArrayList<>();
        ArrayList<Long> dialogs = new ArrayList<>();
        ArrayList<Integer> mids = new ArrayList<>();
        ArrayList<Integer> sources = new ArrayList<>();
        try {
            while (c.next()) {
                batch.add(readMessage(c, account));
                dialogs.add(c.longValue(1));
                mids.add(c.intValue(2));
                sources.add(c.intValue(3));
            }
        } finally {
            c.dispose();
        }
        db.beginTransaction();
        try {
            for (int i = 0; i < batch.size(); i++) {
                TLRPC.Message message = batch.get(i);
                if (message != null) {
                    long dialog = dialogs.get(i);
                    long topic = MessageObject.getTopicId(account, message, storage.getForumTypeFlags(dialog));
                    put(db, message, topic);
                }
                db.executeFast("DELETE FROM places_pending_v1 WHERE uid=" + dialogs.get(i)
                        + " AND mid=" + mids.get(i) + " AND source=" + sources.get(i)).stepThis().dispose();
            }
        } finally {
            db.commitTransaction();
        }
        SQLiteCursor remaining = db.queryFinalized("SELECT 1 FROM places_pending_v1 LIMIT 1");
        try {
            boolean more = remaining.next();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("[AutoPlacesDiag] drained batch=" + batch.size() + " more=" + more);
            }
            return more;
        } finally {
            remaining.dispose();
        }
    }

    public static TLRPC.Message readMessage(SQLiteCursor cursor, int account) throws Exception {
        NativeByteBuffer buffer = cursor.byteBufferValue(0);
        if (buffer == null) return null;
        try {
            TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false);
            if (message != null) {
                message.readAttachPath(buffer, UserConfig.getInstance(account).getClientUserId());
                message.dialog_id = cursor.longValue(1);
                message.id = cursor.intValue(2);
            }
            return message;
        } finally { buffer.reuse(); }
    }

    public static void put(SQLiteDatabase db, TLRPC.Message message, long topic) throws Exception {
        // A message being sent has a temporary negative id that the send renames in place (no
        // trigger fires on UPDATE OF mid): indexing it would leave a permanent duplicate. The sent
        // copy is indexed through the insert trigger. Secret chats use their own id space.
        if (isTemporaryId(message)) return;
        if (PlaceExtractor.extract(message).isEmpty()) {
            db.executeFast("DELETE FROM places_v1 WHERE uid=" + message.dialog_id + " AND mid=" + message.id).stepThis().dispose();
            return;
        }
        SQLitePreparedStatement s = db.executeFast("REPLACE INTO places_v1 VALUES(?, ?, ?, ?, ?, ?)");
        NativeByteBuffer data = new NativeByteBuffer(message.getObjectSize());
        try {
            message.serializeToStream(data);
            s.bindLong(1, message.dialog_id);
            s.bindInteger(2, message.id);
            s.bindLong(3, topic);
            s.bindInteger(4, message.date);
            s.bindByteBuffer(5, data);
            s.bindInteger(6, message.peer_id != null && message.peer_id.channel_id != 0 ? 1 : 0);
            s.step();
        } finally { data.reuse(); s.dispose(); }
    }

    /**
     * A server search result the device already stores is authoritative (it may carry a newer
     * edit, and a deletion removes it): it is queued for the local drain instead of being written
     * from the server copy. Returns false when the device has no copy.
     */
    public static boolean queueLocalCopy(SQLiteDatabase db, TLRPC.Message message) throws Exception {
        int source = localSource(db, message);
        if (source < 0) return false;
        db.executeFast("INSERT OR IGNORE INTO places_pending_v1 VALUES(" + message.dialog_id + ","
                + message.id + "," + source + ")").stepThis().dispose();
        return true;
    }

    static boolean hasLocalCopy(SQLiteDatabase db, TLRPC.Message message) throws Exception {
        return localSource(db, message) >= 0;
    }

    /** 0 = messages_v2, 1 = messages_topics, -1 = the device does not store the message. */
    private static int localSource(SQLiteDatabase db, TLRPC.Message message) throws Exception {
        for (int source = 0; source < 2; source++) {
            SQLiteCursor local = db.queryFinalized(localCopySql(source), message.dialog_id, message.id);
            try {
                if (local.next()) return source;
            } finally {
                local.dispose();
            }
        }
        return -1;
    }

    private static boolean isIndexed(SQLiteDatabase db, TLRPC.Message message) throws Exception {
        SQLiteCursor c = db.queryFinalized("SELECT 1 FROM places_v1 WHERE uid=? AND mid=? LIMIT 1", message.dialog_id, message.id);
        try {
            return c.next();
        } finally {
            c.dispose();
        }
    }

    static String bumpBothEpochsSql() {
        return BUMP_BOTH_EPOCHS_SQL;
    }

    static String deletionEpochRowSql() {
        return DELETION_EPOCH_ROW_SQL;
    }

    static String localCopySql(int source) {
        return "SELECT 1 FROM " + (source == 0 ? "messages_v2" : "messages_topics") + " WHERE uid=? AND mid=? LIMIT 1";
    }

    public static void indexMessages(MessagesStorage storage, int account, ArrayList<MessageObject> messages) throws Exception {
        indexMessages(storage, account, messages, true);
    }

    static boolean isTemporaryId(TLRPC.Message message) {
        return message.id <= 0 && !DialogObject.isEncryptedDialog(message.dialog_id);
    }

    /** {@code edits}: the messages may replace indexed rows, so account-wide searches must not win. */
    public static void indexMessages(MessagesStorage storage, int account, ArrayList<MessageObject> messages,
                                     boolean edits) throws Exception {
        SQLiteDatabase db = storage.getDatabase();
        db.beginTransaction();
        try {
            // Row 2 guards search-only rows. An edit of a message the device stores (live locations
            // update every few seconds) cannot be undone by a search page, which queues the local
            // copy instead; only edits of unstored place messages move it.
            boolean guarded = false;
            if (edits) {
                for (MessageObject object : messages) {
                    if (object == null || object.messageOwner == null) continue;
                    object.messageOwner.dialog_id = object.getDialogId();
                    if (!hasLocalCopy(db, object.messageOwner)
                            && (!PlaceExtractor.extract(object.messageOwner).isEmpty() || isIndexed(db, object.messageOwner))) {
                        guarded = true;
                        break;
                    }
                }
            }
            db.executeFast(guarded ? bumpBothEpochsSql() : "UPDATE places_epoch_v1 SET version = version + 1 WHERE id = 1").stepThis().dispose();
            for (MessageObject object : messages) {
                if (object == null || object.messageOwner == null) continue;
                long dialog = object.getDialogId();
                object.messageOwner.dialog_id = dialog;
                long topic = MessageObject.getTopicId(account, object.messageOwner, storage.getForumTypeFlags(dialog));
                put(db, object.messageOwner, topic);
            }
        } finally {
            db.commitTransaction();
        }
    }

    public static ArrayList<TLRPC.Message> read(SQLiteDatabase db, int account, long dialog, long topic) throws Exception {
        SQLiteCursor c = db.queryFinalized("SELECT data, uid, mid FROM places_v1 WHERE uid=" + dialog
                + (topic == 0 ? "" : " AND topic=" + topic) + " ORDER BY date DESC, mid DESC");
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        try {
            while (c.next()) {
                TLRPC.Message message = readMessage(c, account);
                if (message != null) result.add(message);
            }
        } finally { c.dispose(); }
        return result;
    }

    /** One newest-first page of the account-wide index and the keyset position after it. */
    public static final class RecentPage {
        public final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        public int rows;
        int lastDate;
        int lastMid;
        long lastUid;
    }

    public static RecentPage readRecent(SQLiteDatabase db, int account, int limit, RecentPage after) throws Exception {
        int safeLimit = Math.max(0, limit);
        SQLiteCursor c = after == null || after.rows == 0
                ? db.queryFinalized(recentSql(), safeLimit)
                : db.queryFinalized(recentAfterSql(), after.lastDate, after.lastDate,
                after.lastMid, after.lastMid, after.lastUid, safeLimit);
        RecentPage page = new RecentPage();
        try {
            while (c.next()) {
                page.rows++;
                page.lastUid = c.longValue(1);
                page.lastMid = c.intValue(2);
                page.lastDate = c.intValue(3);
                TLRPC.Message message = readMessage(c, account);
                if (message != null) page.messages.add(message);
            }
        } finally {
            c.dispose();
        }
        return page;
    }

    static String pendingBatchSql() {
        return "SELECT CASE WHEN p.source=0 THEN (SELECT data FROM messages_v2 WHERE uid=p.uid AND mid=p.mid) "
                + "ELSE (SELECT data FROM messages_topics WHERE uid=p.uid AND mid=p.mid LIMIT 1) END, p.uid, p.mid, p.source "
                + "FROM places_pending_v1 p LIMIT 200";
    }

    static String recentSql() {
        return READ_RECENT_SQL;
    }

    static String recentAfterSql() {
        return READ_RECENT_AFTER_SQL;
    }

    public static void saveCursor(SQLiteDatabase db, long dialog, long topic, int stream, int offset, int date, boolean done, int head) throws Exception {
        db.executeFast("REPLACE INTO places_state_v1 VALUES(" + dialog + "," + topic + "," + stream + "," + offset + "," + date + "," + (done ? 1 : 0) + "," + head + ")").stepThis().dispose();
    }
}
