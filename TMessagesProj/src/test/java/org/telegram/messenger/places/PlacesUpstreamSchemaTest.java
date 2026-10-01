package org.telegram.messenger.places;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Merge canary for raw Places SQL: it runs against upstream's own table definitions, read from
 * MessagesStorage.createTables, so an upstream column rename fails here instead of leaving the
 * Places tab silently empty (runtime SQL errors are swallowed by checkSQLException).
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class PlacesUpstreamSchemaTest {
    private static final String STORAGE = "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java";

    @Test public void placesSqlCompilesAgainstUpstreamTables() throws Exception {
        String source = new String(Files.readAllBytes(repoRoot().resolve(STORAGE)), StandardCharsets.UTF_8);
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            for (String table : new String[]{"messages_v2", "messages_topics", "dialogs"}) {
                db.execSQL(upstreamCreate(source, table));
            }
            PlacesStorage.createSchema(db::execSQL);
            String[] queries = {
                    PlacesStorage.recentDialogsSql(),
                    PlacesStorage.recentSeedSql(0),
                    PlacesStorage.recentSeedSql(1),
                    PlacesStorage.pendingBatchSql(),
                    PlacesStorage.recentSql(),
                    PlacesStorage.recentAfterSql(),
                    PlacesStorage.localCopySql(0),
                    PlacesStorage.localCopySql(1),
            };
            for (String sql : queries) {
                // EXPLAIN prepares the statement, resolving every table and column.
                try (Cursor ignored = db.rawQuery("EXPLAIN " + sql, placeholders(sql))) {
                    assertNotNull(sql, ignored);
                }
            }
            db.execSQL("INSERT INTO messages_v2(mid, uid, date, data) VALUES(1, 10, 5, X'01')");
            db.execSQL("INSERT INTO messages_topics(mid, uid, topic_id, date, data) VALUES(2, -10, 3, 5, X'01')");
            assertEquals(2, count(db, "places_pending_v1"));
        }
    }

    private static String upstreamCreate(String source, String table) {
        Matcher matcher = Pattern.compile("\"(CREATE TABLE " + table + "\\(.*?\\))\"").matcher(source);
        assertTrue("Upstream no longer creates " + table + " in MessagesStorage; review Places SQL", matcher.find());
        return matcher.group(1);
    }

    private static String[] placeholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) if (sql.charAt(i) == '?') count++;
        String[] args = new String[count];
        for (int i = 0; i < count; i++) args[i] = "0";
        return args;
    }

    private static long count(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery("SELECT count(*) FROM " + table, null)) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    private static Path repoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(STORAGE))) return current;
            current = current.getParent();
        }
        throw new AssertionError("Repository root with MessagesStorage.java not found");
    }
}
