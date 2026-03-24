package org.telegram.messenger;

import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

class ShareTargetRanker extends BaseController {

    private static final String TABLE_NAME = "share_hints_v1";
    private static final String BOOTSTRAP_PREF_KEY = "share_ranker_bootstrap_v1";

    private static final double SHARE_EVENT_WEIGHT = 1.0;
    private static final double SEND_EVENT_WEIGHT = 0.30;
    private static final int SHARE_HALF_LIFE = 7 * 24 * 60 * 60;
    private static final int SEND_HALF_LIFE = 14 * 24 * 60 * 60;
    private static final int SESSION_WINDOW = 30 * 60;
    private static final int OPEN_DECAY_WINDOW = 10 * 60;
    private static final int SESSION_GATE_WINDOW = 10 * 60;
    private static final int SESSION_GATE_HALF_LIFE = 30 * 60;
    private static final int PENDING_SHARE_TTL = 2 * 60;
    private static final int REMOTE_HINT_WEIGHTED_LIMIT = 20;
    private static final int BOOTSTRAP_LIMIT = 64;
    private static final int BOOTSTRAP_LOOKBACK = 30 * 24 * 60 * 60;

    private final Object sync = new Object();
    private final LongSparseArray<Entry> entries = new LongSparseArray<>();
    private final LongSparseArray<Long> pendingShare = new LongSparseArray<>();

    private boolean loaded;
    private boolean loading;

    ShareTargetRanker(int currentAccount) {
        super(currentAccount);
    }

    public void markPendingShare(long dialogId) {
        if (!isEligibleDialogId(dialogId)) {
            return;
        }
        synchronized (sync) {
            pendingShare.put(dialogId, (long) now());
        }
    }

    public void markPendingShare(LongSparseArray<TLRPC.Dialog> dialogs) {
        if (dialogs == null) {
            return;
        }
        for (int i = 0; i < dialogs.size(); i++) {
            markPendingShare(dialogs.keyAt(i));
        }
    }

    public void recordDialogOpened(long dialogId) {
        if (!isEligibleDialogId(dialogId)) {
            return;
        }
        ensureLoaded();
        Entry snapshot;
        int now = now();
        synchronized (sync) {
            Entry entry = obtainEntryLocked(dialogId);
            resetSessionIfStale(entry, now);
            entry.lastOpenDate = now;
            entry.sessionOpenCount = Math.min(entry.sessionOpenCount + 1, 12);
            entry.lastSessionActivityDate = now;
            snapshot = entry.copy();
        }
        persist(snapshot);
    }

    public boolean recordSuccessfulSend(long dialogId) {
        if (!isEligibleDialogId(dialogId)) {
            return false;
        }
        ensureLoaded();
        Entry snapshot;
        boolean shareEvent;
        int now = now();
        synchronized (sync) {
            Entry entry = obtainEntryLocked(dialogId);
            resetSessionIfStale(entry, now);
            shareEvent = consumePendingShareLocked(dialogId, now);
            if (shareEvent) {
                entry.shareScore = decay(entry.shareScore, entry.lastShareDate, SHARE_HALF_LIFE, now) + SHARE_EVENT_WEIGHT;
                entry.lastShareDate = now;
            } else {
                entry.sendScore = decay(entry.sendScore, entry.lastSendDate, SEND_HALF_LIFE, now) + SEND_EVENT_WEIGHT;
            }
            entry.lastSendDate = now;
            entry.sessionSendCount = Math.min(entry.sessionSendCount + 1, 12);
            entry.lastSessionActivityDate = now;
            snapshot = entry.copy();
        }
        persist(snapshot);
        return shareEvent;
    }

    public ArrayList<TLRPC.TL_topPeer> getTopHints(int limit, List<TLRPC.TL_topPeer> remoteHints) {
        ensureLoaded();
        if (limit <= 0) {
            return new ArrayList<>();
        }
        int now = now();
        HashMap<Long, Double> remoteScores = buildRemoteScores(remoteHints);
        HashSet<Long> candidates = new HashSet<>();
        synchronized (sync) {
            pruneExpiredPendingSharesLocked(now);
            for (int i = 0; i < entries.size(); i++) {
                candidates.add(entries.keyAt(i));
            }
        }
        candidates.addAll(remoteScores.keySet());

        ArrayList<ScoredDialog> scoredDialogs = new ArrayList<>();
        for (Long did : candidates) {
            if (!isShareableDialog(did)) {
                continue;
            }
            Entry entry;
            synchronized (sync) {
                entry = entries.get(did);
                if (entry != null) {
                    entry = entry.copy();
                }
            }
            double longTerm = computeLongTerm(entry, now);
            double shortTerm = computeShortTerm(entry, now);
            double remote = remoteScores.getOrDefault(did, 0.0);
            double alphaSession = computeSessionAlpha(entry, now);
            double alphaRemote = computeRemoteAlpha(entry, remote);
            double score = (1.0 - alphaSession) * ((1.0 - alphaRemote) * longTerm + alphaRemote * remote) + alphaSession * shortTerm;
            if (score <= 0) {
                continue;
            }
            int lastShare = entry != null ? entry.lastShareDate : 0;
            int lastSend = entry != null ? entry.lastSendDate : 0;
            scoredDialogs.add(new ScoredDialog(did, score, lastShare, lastSend));
        }

        if (scoredDialogs.isEmpty() && remoteHints != null) {
            ArrayList<TLRPC.TL_topPeer> fallback = new ArrayList<>();
            for (TLRPC.TL_topPeer remoteHint : remoteHints) {
                long did = MessageObject.getPeerId(remoteHint.peer);
                if (!isShareableDialog(did)) {
                    continue;
                }
                fallback.add(remoteHint);
                if (fallback.size() == limit) {
                    break;
                }
            }
            return fallback;
        }

        Collections.sort(scoredDialogs, (left, right) -> {
            int result = Double.compare(right.score, left.score);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(right.lastShare, left.lastShare);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(right.lastSend, left.lastSend);
            if (result != 0) {
                return result;
            }
            return Long.compare(left.dialogId, right.dialogId);
        });

        ArrayList<TLRPC.TL_topPeer> result = new ArrayList<>();
        for (int i = 0; i < scoredDialogs.size() && result.size() < limit; i++) {
            long did = scoredDialogs.get(i).dialogId;
            TLRPC.TL_topPeer peer = new TLRPC.TL_topPeer();
            peer.rating = scoredDialogs.get(i).score;
            if (DialogObject.isUserDialog(did)) {
                peer.peer = new TLRPC.TL_peerUser();
                peer.peer.user_id = did;
            } else {
                TLRPC.Chat chat = getMessagesController().getChat(-did);
                if (ChatObject.isChannel(chat)) {
                    peer.peer = new TLRPC.TL_peerChannel();
                    peer.peer.channel_id = -did;
                } else {
                    peer.peer = new TLRPC.TL_peerChat();
                    peer.peer.chat_id = -did;
                }
            }
            result.add(peer);
        }
        return result;
    }

    private void ensureLoaded() {
        synchronized (sync) {
            if (loaded || loading) {
                return;
            }
            loading = true;
        }
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            ArrayList<Entry> loadedEntries = new ArrayList<>();
            try {
                ensureTable();
                SQLiteCursor cursor = getMessagesStorage().getDatabase().queryFinalized("SELECT did, share_score, send_score, last_share_date, last_send_date, last_open_date, session_open_count, session_send_count, last_session_activity_date FROM " + TABLE_NAME);
                while (cursor.next()) {
                    Entry entry = new Entry(cursor.longValue(0));
                    entry.shareScore = cursor.doubleValue(1);
                    entry.sendScore = cursor.doubleValue(2);
                    entry.lastShareDate = cursor.intValue(3);
                    entry.lastSendDate = cursor.intValue(4);
                    entry.lastOpenDate = cursor.intValue(5);
                    entry.sessionOpenCount = cursor.intValue(6);
                    entry.sessionSendCount = cursor.intValue(7);
                    entry.lastSessionActivityDate = cursor.intValue(8);
                    loadedEntries.add(entry);
                }
                cursor.dispose();
                if (loadedEntries.isEmpty() && !getMainPrefs().getBoolean(BOOTSTRAP_PREF_KEY, false)) {
                    loadedEntries = bootstrapEntries();
                    if (!loadedEntries.isEmpty()) {
                        for (Entry entry : loadedEntries) {
                            persistInternal(entry);
                        }
                    }
                    getMainPrefs().edit().putBoolean(BOOTSTRAP_PREF_KEY, true).apply();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            ArrayList<Entry> finalLoadedEntries = loadedEntries;
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (sync) {
                    for (int i = 0; i < finalLoadedEntries.size(); i++) {
                        Entry loadedEntry = finalLoadedEntries.get(i);
                        Entry existing = entries.get(loadedEntry.dialogId);
                        if (existing == null) {
                            entries.put(loadedEntry.dialogId, loadedEntry);
                        } else {
                            existing.mergeFrom(loadedEntry);
                        }
                    }
                    loaded = true;
                    loading = false;
                }
            });
        });
    }

    private ArrayList<Entry> bootstrapEntries() {
        ArrayList<Entry> result = new ArrayList<>();
        int cutoff = now() - BOOTSTRAP_LOOKBACK;
        try {
            SQLiteCursor cursor = getMessagesStorage().getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT uid, COUNT(mid), MAX(date) FROM messages_v2 WHERE out = 1 AND date >= %d GROUP BY uid ORDER BY COUNT(mid) DESC LIMIT %d",
                    cutoff, BOOTSTRAP_LIMIT));
            while (cursor.next()) {
                long dialogId = cursor.longValue(0);
                if (!isEligibleDialogId(dialogId)) {
                    continue;
                }
                Entry entry = new Entry(dialogId);
                int count = cursor.intValue(1);
                entry.sendScore = Math.min(12, count) * SEND_EVENT_WEIGHT;
                entry.lastSendDate = cursor.intValue(2);
                entry.lastSessionActivityDate = entry.lastSendDate;
                result.add(entry);
            }
            cursor.dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    private void ensureTable() {
        try {
            getMessagesStorage().getDatabase().executeFast(
                    "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + "(" +
                            "did INTEGER PRIMARY KEY, " +
                            "share_score REAL, " +
                            "send_score REAL, " +
                            "last_share_date INTEGER, " +
                            "last_send_date INTEGER, " +
                            "last_open_date INTEGER, " +
                            "session_open_count INTEGER, " +
                            "session_send_count INTEGER, " +
                            "last_session_activity_date INTEGER)").stepThis().dispose();
            getMessagesStorage().getDatabase().executeFast(
                    "CREATE INDEX IF NOT EXISTS " + TABLE_NAME + "_last_activity_idx ON " + TABLE_NAME + "(last_session_activity_date)").stepThis().dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void persist(Entry entry) {
        getMessagesStorage().getStorageQueue().postRunnable(() -> persistInternal(entry));
    }

    private void persistInternal(Entry entry) {
        try {
            ensureTable();
            SQLitePreparedStatement state = getMessagesStorage().getDatabase().executeFast(
                    "REPLACE INTO " + TABLE_NAME + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)");
            state.requery();
            state.bindLong(1, entry.dialogId);
            state.bindDouble(2, entry.shareScore);
            state.bindDouble(3, entry.sendScore);
            state.bindInteger(4, entry.lastShareDate);
            state.bindInteger(5, entry.lastSendDate);
            state.bindInteger(6, entry.lastOpenDate);
            state.bindInteger(7, entry.sessionOpenCount);
            state.bindInteger(8, entry.sessionSendCount);
            state.bindInteger(9, entry.lastSessionActivityDate);
            state.step();
            state.dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private HashMap<Long, Double> buildRemoteScores(List<TLRPC.TL_topPeer> remoteHints) {
        HashMap<Long, Double> remoteScores = new HashMap<>();
        if (remoteHints == null) {
            return remoteScores;
        }
        int limit = Math.min(remoteHints.size(), REMOTE_HINT_WEIGHTED_LIMIT);
        for (int i = 0; i < limit; i++) {
            long dialogId = MessageObject.getPeerId(remoteHints.get(i).peer);
            if (!isEligibleDialogId(dialogId)) {
                continue;
            }
            double score = Math.log(2.0) / Math.log(i + 2.0);
            remoteScores.put(dialogId, score);
        }
        return remoteScores;
    }

    private double computeLongTerm(Entry entry, int now) {
        if (entry == null) {
            return 0;
        }
        double shareScore = decay(entry.shareScore, entry.lastShareDate, SHARE_HALF_LIFE, now);
        double sendScore = decay(entry.sendScore, entry.lastSendDate, SEND_HALF_LIFE, now);
        return Math.log1p(shareScore) + 0.45 * Math.log1p(sendScore);
    }

    private double computeShortTerm(Entry entry, int now) {
        if (entry == null || entry.lastSessionActivityDate == 0 || now - entry.lastSessionActivityDate > SESSION_WINDOW) {
            return 0;
        }
        double recentOpen = entry.lastOpenDate == 0 ? 0 : Math.exp(-(double) (now - entry.lastOpenDate) / OPEN_DECAY_WINDOW);
        double sendComponent = 0.25 * Math.min(1.0, entry.sessionSendCount / 2.0);
        double openComponent = 0.15 * Math.min(1.0, entry.sessionOpenCount / 3.0);
        return 0.60 * recentOpen + sendComponent + openComponent;
    }

    private double computeSessionAlpha(Entry entry, int now) {
        if (entry == null || entry.lastSessionActivityDate == 0 || now - entry.lastSessionActivityDate > SESSION_WINDOW) {
            return 0;
        }
        double gate = 0.15;
        if (entry.lastOpenDate != 0 && now - entry.lastOpenDate <= SESSION_GATE_WINDOW) {
            gate += 0.20;
        }
        if (entry.sessionSendCount >= 2) {
            gate += 0.20;
        }
        double decay = Math.exp(-(double) (now - entry.lastSessionActivityDate) / SESSION_GATE_HALF_LIFE);
        return Math.min(0.60, gate) * decay;
    }

    private double computeRemoteAlpha(Entry entry, double remoteScore) {
        if (remoteScore <= 0) {
            return 0;
        }
        if (entry == null) {
            return 0.35;
        }
        double localConfidence = Math.min(1.0, Math.log1p(entry.shareScore + entry.sendScore) / Math.log(4.0));
        return 0.10 + 0.25 * (1.0 - localConfidence);
    }

    private void resetSessionIfStale(Entry entry, int now) {
        if (entry.lastSessionActivityDate == 0 || now - entry.lastSessionActivityDate <= SESSION_WINDOW) {
            return;
        }
        entry.sessionOpenCount = 0;
        entry.sessionSendCount = 0;
    }

    private double decay(double value, int lastTimestamp, int halfLifeSeconds, int now) {
        if (value <= 0 || lastTimestamp <= 0 || halfLifeSeconds <= 0) {
            return value;
        }
        int delta = Math.max(0, now - lastTimestamp);
        return value * Math.exp(-(double) delta / halfLifeSeconds);
    }

    private Entry obtainEntryLocked(long dialogId) {
        Entry entry = entries.get(dialogId);
        if (entry == null) {
            entry = new Entry(dialogId);
            entries.put(dialogId, entry);
        }
        return entry;
    }

    private boolean consumePendingShareLocked(long dialogId, int now) {
        Long timestamp = pendingShare.get(dialogId);
        if (timestamp == null) {
            return false;
        }
        pendingShare.remove(dialogId);
        return now - timestamp <= PENDING_SHARE_TTL;
    }

    private void pruneExpiredPendingSharesLocked(int now) {
        for (int i = pendingShare.size() - 1; i >= 0; i--) {
            long timestamp = pendingShare.valueAt(i);
            if (now - timestamp > PENDING_SHARE_TTL) {
                pendingShare.removeAt(i);
            }
        }
    }

    private boolean isEligibleDialogId(long dialogId) {
        return dialogId != 0 && !DialogObject.isEncryptedDialog(dialogId);
    }

    private boolean isShareableDialog(long dialogId) {
        if (!isEligibleDialogId(dialogId)) {
            return false;
        }
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            return user != null
                    && !UserObject.isDeleted(user)
                    && !UserObject.isReplyUser(user)
                    && !UserObject.isService(user.id);
        }
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        if (chat == null || ChatObject.isNotInChat(chat)) {
            return false;
        }
        if (ChatObject.isChannel(chat) && !chat.megagroup) {
            return ChatObject.canPost(chat);
        }
        return ChatObject.canSendMessages(chat);
    }

    private int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    private android.content.SharedPreferences getMainPrefs() {
        return getMessagesController().getMainSettings();
    }

    private static class Entry {
        final long dialogId;
        double shareScore;
        double sendScore;
        int lastShareDate;
        int lastSendDate;
        int lastOpenDate;
        int sessionOpenCount;
        int sessionSendCount;
        int lastSessionActivityDate;

        Entry(long dialogId) {
            this.dialogId = dialogId;
        }

        Entry copy() {
            Entry entry = new Entry(dialogId);
            entry.shareScore = shareScore;
            entry.sendScore = sendScore;
            entry.lastShareDate = lastShareDate;
            entry.lastSendDate = lastSendDate;
            entry.lastOpenDate = lastOpenDate;
            entry.sessionOpenCount = sessionOpenCount;
            entry.sessionSendCount = sessionSendCount;
            entry.lastSessionActivityDate = lastSessionActivityDate;
            return entry;
        }

        void mergeFrom(Entry other) {
            shareScore = Math.max(shareScore, other.shareScore);
            sendScore = Math.max(sendScore, other.sendScore);
            lastShareDate = Math.max(lastShareDate, other.lastShareDate);
            lastSendDate = Math.max(lastSendDate, other.lastSendDate);
            lastOpenDate = Math.max(lastOpenDate, other.lastOpenDate);
            sessionOpenCount = Math.max(sessionOpenCount, other.sessionOpenCount);
            sessionSendCount = Math.max(sessionSendCount, other.sessionSendCount);
            lastSessionActivityDate = Math.max(lastSessionActivityDate, other.lastSessionActivityDate);
        }
    }

    private static class ScoredDialog {
        final long dialogId;
        final double score;
        final int lastShare;
        final int lastSend;

        ScoredDialog(long dialogId, double score, int lastShare, int lastSend) {
            this.dialogId = dialogId;
            this.score = score;
            this.lastShare = lastShare;
            this.lastSend = lastSend;
        }
    }
}
