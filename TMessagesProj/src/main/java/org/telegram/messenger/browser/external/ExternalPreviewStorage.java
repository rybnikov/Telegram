package org.telegram.messenger.browser.external;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

public final class ExternalPreviewStorage {

    public static final String TABLE_NAME = "external_previews_v1";

    private static final int RETENTION_SECONDS = 30 * 24 * 60 * 60;
    private static final int TOUCH_INTERVAL_SECONDS = 6 * 60 * 60;
    private static final int MAX_ROWS = 1000;
    private static final String FORMAT_VERSION_KEY = "externalPreviewFormatVersion";

    private ExternalPreviewStorage() {
    }

    public static void createTables(SQLiteDatabase database) throws SQLiteException {
        database.executeFast("CREATE TABLE IF NOT EXISTS external_previews_v1(id INTEGER PRIMARY KEY, canonical_url TEXT NOT NULL UNIQUE, platform TEXT NOT NULL, webpage BLOB NOT NULL, preview_kind INTEGER NOT NULL, media_url TEXT, poster_url TEXT, width INTEGER, height INTEGER, title TEXT, description TEXT, updated_at INTEGER NOT NULL, extra TEXT);").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS external_previews_v1_updated_at_idx ON external_previews_v1(updated_at);").stepThis().dispose();
    }

    public static void put(MessagesStorage storage, MessagesStorage.ExternalPreviewRecord preview) {
        if (preview == null || TextUtils.isEmpty(preview.canonicalUrl) || preview.webPage == null) {
            return;
        }
        storage.getStorageQueue().postRunnable(() -> {
            SQLitePreparedStatement state = null;
            SQLiteDatabase database = storage.getDatabase();
            try {
                log("put start id=" + preview.webPageId + " kind=" + preview.previewKind + " url=" + preview.canonicalUrl);
                NativeByteBuffer data = new NativeByteBuffer(preview.webPage.getObjectSize());
                preview.webPage.serializeToStream(data);

                state = database.executeFast("REPLACE INTO external_previews_v1 VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                state.bindLong(1, preview.webPageId);
                state.bindString(2, preview.canonicalUrl);
                state.bindString(3, preview.platform);
                state.bindByteBuffer(4, data);
                state.bindInteger(5, preview.previewKind);
                bindStringOrNull(state, 6, preview.mediaUrl);
                bindStringOrNull(state, 7, preview.posterUrl);
                state.bindInteger(8, preview.width);
                state.bindInteger(9, preview.height);
                bindStringOrNull(state, 10, preview.title);
                bindStringOrNull(state, 11, preview.description);
                state.bindLong(12, System.currentTimeMillis() / 1000L);
                bindStringOrNull(state, 13, preview.extra);
                state.step();
                data.reuse();
                state.dispose();
                state = null;

                log("put success id=" + preview.webPageId + " kind=" + preview.previewKind + " url=" + preview.canonicalUrl);
                pruneLocked(storage);
            } catch (Exception e) {
                log("put failed url=" + preview.canonicalUrl + " error=" + e.getClass().getSimpleName() + ":" + e.getMessage());
                storage.checkSQLException(e);
            } finally {
                if (state != null) {
                    state.dispose();
                }
            }
        });
    }

    public static void get(MessagesStorage storage, String canonicalUrl, Utilities.Callback<MessagesStorage.ExternalPreviewRecord> onComplete) {
        if (TextUtils.isEmpty(canonicalUrl)) {
            if (onComplete != null) {
                AndroidUtilities.runOnUIThread(() -> onComplete.run(null));
            }
            return;
        }
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            SQLitePreparedStatement touchState = null;
            MessagesStorage.ExternalPreviewRecord result = null;
            SQLiteDatabase database = storage.getDatabase();
            try {
                log("get start url=" + canonicalUrl);
                cursor = database.queryFinalized("SELECT id, platform, webpage, preview_kind, media_url, poster_url, width, height, title, description, updated_at, extra FROM external_previews_v1 WHERE canonical_url = ?", canonicalUrl);
                long now = System.currentTimeMillis() / 1000L;
                if (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(2);
                    if (data != null) {
                        TLRPC.WebPage webPage = TLRPC.WebPage.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                        if (webPage != null) {
                            result = new MessagesStorage.ExternalPreviewRecord(
                                cursor.longValue(0),
                                canonicalUrl,
                                cursor.stringValue(1),
                                webPage,
                                cursor.intValue(3),
                                cursor.stringValue(4),
                                cursor.stringValue(5),
                                cursor.intValue(6),
                                cursor.intValue(7),
                                cursor.stringValue(8),
                                cursor.stringValue(9),
                                cursor.stringValue(11)
                            );
                            long updatedAt = cursor.longValue(10);
                            if (now - updatedAt >= TOUCH_INTERVAL_SECONDS) {
                                touchState = database.executeFast("UPDATE external_previews_v1 SET updated_at = ? WHERE id = ?");
                                touchState.bindLong(1, now);
                                touchState.bindLong(2, result.webPageId);
                                touchState.step();
                                touchState.dispose();
                                touchState = null;
                            }
                        }
                    }
                }
                if (cursor != null) {
                    cursor.dispose();
                    cursor = null;
                }
                if (result != null) {
                    log("get hit id=" + result.webPageId + " kind=" + result.previewKind + " url=" + canonicalUrl);
                } else {
                    log("get miss url=" + canonicalUrl);
                }
            } catch (Exception e) {
                log("get failed url=" + canonicalUrl + " error=" + e.getClass().getSimpleName() + ":" + e.getMessage());
                storage.checkSQLException(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
                if (touchState != null) {
                    touchState.dispose();
                }
            }
            MessagesStorage.ExternalPreviewRecord callbackResult = result;
            if (onComplete != null) {
                AndroidUtilities.runOnUIThread(() -> onComplete.run(callbackResult));
            }
        });
    }

    public static int clearCacheLocked(MessagesStorage storage) {
        SQLiteCursor cursor = null;
        SQLiteDatabase database = storage.getDatabase();
        try {
            cursor = database.queryFinalized("SELECT COUNT(*) FROM external_previews_v1");
            int count = cursor.next() ? cursor.intValue(0) : 0;
            cursor.dispose();
            cursor = null;
            if (count > 0) {
                database.executeFast("DELETE FROM external_previews_v1").stepThis().dispose();
            }
            return count;
        } catch (Exception e) {
            storage.checkSQLException(e);
            return 0;
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    public static void purgeForFormatUpgrade(MessagesStorage storage, int currentAccount) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(currentAccount == 0 ? "mainconfig" : "mainconfig" + currentAccount, Context.MODE_PRIVATE);
        int storedVersion = preferences.getInt(FORMAT_VERSION_KEY, 0);
        int currentVersion = ExternalPreviewManager.EXTERNAL_PREVIEW_FORMAT_VERSION;
        if (storedVersion >= currentVersion) {
            return;
        }
        int rows = clearCacheLocked(storage);
        ExternalPreviewManager.clearDebugState();
        preferences.edit().putInt(FORMAT_VERSION_KEY, currentVersion).apply();
        String line = "external preview format upgrade from=" + storedVersion + " to=" + currentVersion + " purged rows=" + rows;
        Log.d("tmessages", line);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d(line);
        }
    }

    private static void pruneLocked(MessagesStorage storage) {
        long now = System.currentTimeMillis() / 1000L;
        long cutoff = now - RETENTION_SECONDS;
        SQLiteDatabase database = storage.getDatabase();
        try {
            database.executeFast("DELETE FROM external_previews_v1 WHERE updated_at < " + cutoff).stepThis().dispose();
            database.executeFast("DELETE FROM external_previews_v1 WHERE id IN (SELECT id FROM external_previews_v1 ORDER BY updated_at DESC LIMIT -1 OFFSET " + MAX_ROWS + ")").stepThis().dispose();
        } catch (Exception e) {
            storage.checkSQLException(e);
        }
    }

    private static void bindStringOrNull(SQLitePreparedStatement state, int index, String value) throws Exception {
        if (value != null) {
            state.bindString(index, value);
        } else {
            state.bindNull(index);
        }
    }

    private static void log(String message) {
        if (!BuildVars.DEBUG_PRIVATE_VERSION && !BuildVars.LOGS_ENABLED) {
            return;
        }
        String line = "ExternalPreviewStorage: " + message;
        Log.d("tmessages", line);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d(line);
        }
    }
}
