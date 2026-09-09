package org.telegram.messenger.places;

import android.app.Application;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
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
}
