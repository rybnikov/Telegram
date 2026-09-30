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
            try (Cursor cursor = db.rawQuery(PlacesStorage.recentSql(), new String[]{"3", "0"})) {
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
            try (Cursor cursor = db.rawQuery(PlacesStorage.recentSql(), new String[]{"120", "120"})) {
                assertEquals(6, cursor.getCount());
                assertTrue(cursor.moveToLast());
                assertEquals(10, cursor.getLong(1));
            }
            db.execSQL("INSERT INTO places_v1 VALUES(30,1,0,100,X'01',0)");
            try (Cursor cursor = db.rawQuery(PlacesStorage.recentSql(), new String[]{"1", "125"})) {
                assertTrue(cursor.moveToFirst());
                assertEquals(30, cursor.getLong(1));
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
            try (Cursor dialogs = db.rawQuery(PlacesStorage.recentDialogsSql(), new String[]{"2", "20"})) {
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
            try (Cursor dialogs = db.rawQuery(PlacesStorage.recentDialogsSql(), new String[]{"2", "20"})) {
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

}
