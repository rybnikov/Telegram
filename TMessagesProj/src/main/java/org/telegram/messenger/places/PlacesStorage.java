package org.telegram.messenger.places;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;

/** All methods run on the owning account's storage queue. No separate database or account singleton. */
public final class PlacesStorage {
    private PlacesStorage() {}

    public interface SchemaExecutor { void execute(String sql) throws SQLiteException; }

    public static void createSchema(SQLiteDatabase db) throws SQLiteException {
        createSchema(sql -> db.executeFast(sql).stepThis().dispose());
    }

    public static void createSchema(SchemaExecutor db) throws SQLiteException {
        db.execute("CREATE TABLE IF NOT EXISTS places_v1(uid INTEGER, mid INTEGER, topic INTEGER, date INTEGER, data BLOB, channel INTEGER, PRIMARY KEY(uid, mid))");
        db.execute("CREATE INDEX IF NOT EXISTS places_date_v1 ON places_v1(uid, topic, date DESC, mid DESC)");
        db.execute("CREATE TABLE IF NOT EXISTS places_pending_v1(uid INTEGER, mid INTEGER, source INTEGER, PRIMARY KEY(uid, mid, source))");
        db.execute("CREATE TABLE IF NOT EXISTS places_local_v1(uid INTEGER, source INTEGER, PRIMARY KEY(uid, source))");
        db.execute("CREATE TABLE IF NOT EXISTS places_state_v1(uid INTEGER, topic INTEGER, stream INTEGER, offset INTEGER, date INTEGER, done INTEGER, head INTEGER, PRIMARY KEY(uid, topic, stream))");
        db.execute("CREATE TABLE IF NOT EXISTS places_meta_v1(url TEXT PRIMARY KEY, data TEXT, time INTEGER)");
        db.execute("CREATE TABLE IF NOT EXISTS places_epoch_v1(id INTEGER PRIMARY KEY, version INTEGER)");
        db.execute("INSERT OR IGNORE INTO places_epoch_v1 VALUES(1, 0)");
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

    public static long epoch(SQLiteDatabase db) throws Exception {
        SQLiteCursor c = db.queryFinalized("SELECT version FROM places_epoch_v1 WHERE id = 1");
        try { return c.next() ? c.longValue(0) : 0; } finally { c.dispose(); }
    }

    /** Also deletes search-only rows that never existed in messages_v2. Invalidates in-flight requests. */
    public static void invalidate(SQLiteDatabase db, String predicate, String statePredicate) throws SQLiteException {
        db.executeFast("UPDATE places_epoch_v1 SET version = version + 1 WHERE id = 1").stepThis().dispose();
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

    public static void indexMessages(MessagesStorage storage, int account, ArrayList<MessageObject> messages) throws Exception {
        SQLiteDatabase db = storage.getDatabase();
        db.beginTransaction();
        try {
            db.executeFast("UPDATE places_epoch_v1 SET version = version + 1 WHERE id = 1").stepThis().dispose();
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

    public static void saveCursor(SQLiteDatabase db, long dialog, long topic, int stream, int offset, int date, boolean done, int head) throws Exception {
        db.executeFast("REPLACE INTO places_state_v1 VALUES(" + dialog + "," + topic + "," + stream + "," + offset + "," + date + "," + (done ? 1 : 0) + "," + head + ")").stepThis().dispose();
    }
}
