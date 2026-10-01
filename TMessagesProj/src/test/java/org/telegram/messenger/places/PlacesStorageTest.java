package org.telegram.messenger.places;

import android.app.Application;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class PlacesStorageTest {
    private SQLiteDatabase database() throws Exception {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE messages_v2(uid INTEGER, mid INTEGER, data BLOB, PRIMARY KEY(uid,mid))");
        db.execSQL("CREATE TABLE messages_topics(uid INTEGER, mid INTEGER, topic_id INTEGER, data BLOB, PRIMARY KEY(uid,mid,topic_id))");
        db.execSQL("PRAGMA user_version=179");
        PlacesStorage.createSchema(db::execSQL);
        PlacesStorage.createSchema(db::execSQL); // Upgrading/recovering is idempotent.
        return db;
    }
    private long count(SQLiteDatabase db, String table) { return DatabaseUtils.longForQuery(db, "SELECT count(*) FROM " + table, null); }
    @Test public void schemaUpgradeDoesNotEagerlyCopyTheWholeMessageDatabase() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE messages_v2(uid INTEGER, mid INTEGER, data BLOB, PRIMARY KEY(uid,mid))");
            db.execSQL("CREATE TABLE messages_topics(uid INTEGER, mid INTEGER, topic_id INTEGER, data BLOB, PRIMARY KEY(uid,mid,topic_id))");
            db.execSQL("INSERT INTO messages_v2 VALUES(10,1,X'01')");
            PlacesStorage.createSchema(db::execSQL);
            assertEquals(0, count(db, "places_pending_v1"));
            assertEquals(0, count(db, "places_local_v1"));
        }
    }
    @Test public void insertsEditsAndLiveLocationWritesAreTrackedAndInvalidateRequests() throws Exception {
        try (SQLiteDatabase db = database()) {
            long epoch = DatabaseUtils.longForQuery(db, "SELECT version FROM places_epoch_v1", null);
            db.execSQL("INSERT INTO messages_v2 VALUES(10,1,X'01')");
            db.execSQL("REPLACE INTO messages_v2 VALUES(10,1,X'02')");
            db.execSQL("UPDATE messages_v2 SET data=X'03' WHERE mid=1");
            assertEquals(1, count(db, "places_pending_v1"));
            assertTrue(DatabaseUtils.longForQuery(db, "SELECT version FROM places_epoch_v1", null) > epoch);
            db.execSQL("INSERT INTO places_v1 VALUES(10,1,0,123,X'03',0)");
            db.execSQL("DELETE FROM messages_v2 WHERE uid=10");
            assertEquals(0, count(db, "places_v1")); assertEquals(0, count(db, "places_pending_v1"));
        }
    }
    @Test public void topicWritesAreCapturedAndAccountDatabasesAreIndependent() throws Exception {
        try (SQLiteDatabase a = database(); SQLiteDatabase b = database()) {
            a.execSQL("INSERT INTO messages_topics VALUES(-10,1,5,X'01')");
            assertEquals(1, count(a, "places_pending_v1"));
            assertEquals(0, count(b, "places_pending_v1"));
            assertEquals(1, DatabaseUtils.longForQuery(a, "SELECT source FROM places_pending_v1", null));
            a.execSQL("INSERT INTO places_v1 VALUES(-10,1,5,123,X'01',1)");
            a.execSQL("DELETE FROM messages_topics WHERE topic_id=5");
            assertEquals(0, count(a, "places_v1"));
        }
    }

    @Test public void deletingTopicCopyKeepsIndexWhileMainMessageStillExists() throws Exception {
        try (SQLiteDatabase db = database()) {
            db.execSQL("INSERT INTO messages_v2 VALUES(-10,1,X'01')");
            db.execSQL("INSERT INTO messages_topics VALUES(-10,1,5,X'01')");
            db.execSQL("INSERT INTO places_v1 VALUES(-10,1,5,123,X'01',1)");
            db.execSQL("DELETE FROM messages_topics WHERE topic_id=5");
            assertEquals(1, count(db, "places_v1"));
            assertEquals(1, count(db, "places_pending_v1"));
            db.execSQL("DELETE FROM messages_v2 WHERE uid=-10 AND mid=1");
            assertEquals(0, count(db, "places_v1"));
        }
    }

    @Test public void recentQueryKeepsGroupsAndOrdersNewestFirst() throws Exception {
        try (SQLiteDatabase db = database()) {
            db.execSQL("INSERT INTO places_v1 VALUES(10,1,0,100,X'01',0)");
            db.execSQL("INSERT INTO places_v1 VALUES(-20,2,0,200,X'02',1)");
            db.execSQL("INSERT INTO places_v1 VALUES(30,3,0,100,X'03',0)");
            db.execSQL("INSERT INTO places_v1 VALUES(40,4,0,90,X'04',0)");
            try (Cursor cursor = db.rawQuery(PlacesStorage.recentSql(), new String[]{"3"})) {
                assertTrue(cursor.moveToNext()); assertEquals(2, cursor.getInt(2));
                assertTrue(cursor.moveToNext()); assertEquals(3, cursor.getInt(2));
                assertTrue(cursor.moveToNext()); assertEquals(1, cursor.getInt(2));
                assertFalse(cursor.moveToNext());
            }
        }
    }

    @Test public void recentReaderContinuesBeyondFilteredFirstPageWithStableTies() throws Exception {
        try (SQLiteDatabase db = database()) {
            for (int id = 1; id <= 125; id++) {
                db.execSQL("INSERT INTO places_v1 VALUES(-20," + id + ",0,200,X'01',1)");
            }
            db.execSQL("INSERT INTO places_v1 VALUES(10,1,0,100,X'01',0)");
            try (Cursor cursor = db.rawQuery(PlacesStorage.recentSql(), new String[]{"120"})) {
                assertEquals(120, cursor.getCount());
                assertTrue(cursor.moveToLast());
                assertEquals(6, cursor.getInt(2));
            }
            // Next page after (date 200, mid 6, uid -20).
            try (Cursor cursor = db.rawQuery(PlacesStorage.recentAfterSql(),
                    new String[]{"200", "200", "6", "6", "-20", "120"})) {
                assertEquals(6, cursor.getCount());
                assertTrue(cursor.moveToLast());
                assertEquals(10, cursor.getLong(1));
            }
            // A row that sorts before the cursor belongs to an earlier page, never a repeat.
            db.execSQL("INSERT INTO places_v1 VALUES(30,1,0,100,X'01',0)");
            try (Cursor cursor = db.rawQuery(PlacesStorage.recentAfterSql(),
                    new String[]{"100", "100", "1", "1", "10", "10"})) {
                assertEquals(0, cursor.getCount());
            }
        }
    }

    @Test public void recentAutoSeedSpansUnopenedDialogsAndLeavesFullPhoneBackfillAvailable() throws Exception {
        try (SQLiteDatabase db = database()) {
            db.execSQL("CREATE TABLE dialogs(did INTEGER PRIMARY KEY,date INTEGER)");
            db.execSQL("ALTER TABLE messages_v2 ADD COLUMN date INTEGER");
            db.execSQL("ALTER TABLE messages_topics ADD COLUMN date INTEGER");
            for (long dialog : new long[]{10, 20}) {
                db.execSQL("INSERT INTO dialogs VALUES(" + dialog + ",100)");
                for (int id = 1; id <= 105; id++) {
                    db.execSQL("INSERT INTO messages_v2 VALUES(" + dialog + "," + id + ",X'01'," + id + ")");
                }
            }
            db.execSQL("INSERT INTO messages_topics VALUES(20,200,7,X'01',200)");
            db.execSQL("DELETE FROM places_pending_v1"); // Existing data predates the feature.
            try (Cursor dialogs = db.rawQuery(PlacesStorage.recentDialogsSql(), new String[]{"50", "2", "20"})) {
                assertEquals(2, dialogs.getCount());
                while (dialogs.moveToNext()) {
                    long dialog = dialogs.getLong(0);
                    db.execSQL(PlacesStorage.recentSeedSql(0), new Object[]{dialog, 100});
                    db.execSQL("INSERT INTO places_local_v1 VALUES(?,2)", new Object[]{dialog});
                }
            }
            db.execSQL(PlacesStorage.recentSeedSql(1), new Object[]{20, 100});
            assertEquals(201, count(db, "places_pending_v1"));
            assertEquals(0, DatabaseUtils.longForQuery(db, "SELECT count(*) FROM places_local_v1 WHERE source IN(0,1)", null));
            assertEquals(0, DatabaseUtils.longForQuery(db, "SELECT count(*) FROM places_pending_v1 WHERE mid <= 5", null));
            try (Cursor dialogs = db.rawQuery(PlacesStorage.recentDialogsSql(), new String[]{"50", "2", "20"})) {
                assertEquals(0, dialogs.getCount());
            }
            db.execSQL("INSERT INTO messages_v2 VALUES(10,106,X'02',106)");
            assertEquals(202, count(db, "places_pending_v1"));
        }
    }

    @Test public void globalBatchSpansDialogsAndReportsOrphans() throws Exception {
        try (SQLiteDatabase db = database()) {
            db.execSQL("INSERT INTO messages_v2 VALUES(10,1,X'01')");
            db.execSQL("INSERT INTO messages_v2 VALUES(20,2,X'02')");
            db.execSQL("INSERT INTO messages_topics VALUES(30,3,7,X'03')");
            db.execSQL("INSERT INTO places_pending_v1 VALUES(40,4,0)");
            java.util.HashSet<Long> dialogs = new java.util.HashSet<>();
            int orphans = 0;
            try (Cursor cursor = db.rawQuery(PlacesStorage.pendingBatchSql(), null)) {
                while (cursor.moveToNext()) {
                    dialogs.add(cursor.getLong(1));
                    if (cursor.isNull(0)) orphans++;
                }
            }
            assertTrue(dialogs.contains(10L)); assertTrue(dialogs.contains(20L));
            assertTrue(dialogs.contains(30L)); assertTrue(dialogs.contains(40L));
            assertEquals(1, orphans);
        }
    }


    private static java.util.ArrayList<String> keys(Cursor cursor) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        try {
            while (cursor.moveToNext()) result.add(cursor.getLong(1) + ":" + cursor.getInt(2));
        } finally {
            cursor.close();
        }
        return result;
    }

    @Test public void recentKeysetPagesNeitherRepeatNorSkipWhenRowsArriveBetweenPages() throws Exception {
        try (SQLiteDatabase db = database()) {
            db.execSQL("INSERT INTO places_v1 VALUES(1,10,0,500,X'01',0)");
            db.execSQL("INSERT INTO places_v1 VALUES(2,10,0,500,X'01',0)");
            db.execSQL("INSERT INTO places_v1 VALUES(1,9,0,400,X'01',0)");
            db.execSQL("INSERT INTO places_v1 VALUES(3,5,0,300,X'01',0)");
            java.util.ArrayList<String> first = keys(db.rawQuery(PlacesStorage.recentSql(), new String[]{"2"}));
            assertEquals(java.util.Arrays.asList("2:10", "1:10"), first);
            // The drain indexes a newer row and an older one between the two page reads.
            db.execSQL("INSERT INTO places_v1 VALUES(4,99,0,900,X'01',0)");
            db.execSQL("INSERT INTO places_v1 VALUES(5,1,0,450,X'01',0)");
            java.util.ArrayList<String> second = keys(db.rawQuery(PlacesStorage.recentAfterSql(),
                    new String[]{"500", "500", "10", "10", "1", "10"}));
            assertEquals(java.util.Arrays.asList("5:1", "1:9", "3:5"), second);
        }
    }

    @Test public void recentReadsUseTheAccountWideIndex() throws Exception {
        try (SQLiteDatabase db = database()) {
            for (String sql : new String[]{PlacesStorage.recentSql(), PlacesStorage.recentAfterSql()}) {
                StringBuilder plan = new StringBuilder();
                int args = sql.length() - sql.replace("?", "").length();
                String[] values = new String[args];
                java.util.Arrays.fill(values, "1");
                try (Cursor cursor = db.rawQuery("EXPLAIN QUERY PLAN " + sql, values)) {
                    while (cursor.moveToNext()) plan.append(cursor.getString(cursor.getColumnCount() - 1)).append('\n');
                }
                assertTrue(plan.toString(), plan.toString().contains("places_recent_v1"));
                assertFalse(plan.toString(), plan.toString().contains("TEMP B-TREE"));
            }
        }
    }

    @Test public void deletionEpochIgnoresInsertsButFollowsInvalidation() throws Exception {
        try (SQLiteDatabase db = database()) {
            long deletions = DatabaseUtils.longForQuery(db, "SELECT version FROM places_epoch_v1 WHERE id = 2", null);
            long writes = DatabaseUtils.longForQuery(db, "SELECT version FROM places_epoch_v1 WHERE id = 1", null);
            db.execSQL("INSERT INTO messages_v2 VALUES(10,1,X'01')");
            db.execSQL("UPDATE messages_v2 SET data=X'02' WHERE mid=1");
            assertTrue(DatabaseUtils.longForQuery(db, "SELECT version FROM places_epoch_v1 WHERE id = 1", null) > writes);
            assertEquals(deletions, DatabaseUtils.longForQuery(db, "SELECT version FROM places_epoch_v1 WHERE id = 2", null));
            db.execSQL(PlacesStorage.bumpBothEpochsSql());
            assertEquals(deletions + 1, DatabaseUtils.longForQuery(db, "SELECT version FROM places_epoch_v1 WHERE id = 2", null));
            db.execSQL(PlacesStorage.deletionEpochRowSql()); // Idempotent for legacy databases.
            assertEquals(deletions + 1, DatabaseUtils.longForQuery(db, "SELECT version FROM places_epoch_v1 WHERE id = 2", null));
        }
    }

    @Test public void seedCoversOnlyRecentlyActiveDialogs() throws Exception {
        try (SQLiteDatabase db = database()) {
            db.execSQL("CREATE TABLE dialogs(did INTEGER PRIMARY KEY,date INTEGER)");
            db.execSQL("INSERT INTO dialogs VALUES(10, 1000)");
            db.execSQL("INSERT INTO dialogs VALUES(20, 10)");
            try (Cursor dialogs = db.rawQuery(PlacesStorage.recentDialogsSql(), new String[]{"500", "2", "20"})) {
                assertEquals(1, dialogs.getCount());
                assertTrue(dialogs.moveToFirst());
                assertEquals(10, dialogs.getLong(0));
            }
            assertEquals(60 * 24 * 60 * 60, PlacesStorage.SEED_WINDOW_SECONDS);
        }
    }

    @Test public void temporaryIdsOfMessagesBeingSentAreNeverIndexed() {
        org.telegram.tgnet.TLRPC.TL_message sending = new org.telegram.tgnet.TLRPC.TL_message();
        sending.id = -210000;
        sending.dialog_id = 42;
        assertTrue(PlacesStorage.isTemporaryId(sending));
        sending.id = 7;
        assertFalse(PlacesStorage.isTemporaryId(sending));
        org.telegram.tgnet.TLRPC.TL_message secret = new org.telegram.tgnet.TLRPC.TL_message();
        secret.id = -5;
        secret.dialog_id = org.telegram.messenger.DialogObject.makeEncryptedDialogId(3);
        assertFalse("Secret chats use their own id space", PlacesStorage.isTemporaryId(secret));
    }
}
