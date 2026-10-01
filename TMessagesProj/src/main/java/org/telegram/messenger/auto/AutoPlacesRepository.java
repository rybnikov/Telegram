package org.telegram.messenger.auto;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.duress.EmergencyPasscode;
import org.telegram.messenger.places.Place;
import org.telegram.messenger.places.PlaceDetails;
import org.telegram.messenger.places.PlaceDetailsResolver;
import org.telegram.messenger.places.PlaceEntry;
import org.telegram.messenger.places.PlaceExtractor;
import org.telegram.messenger.places.PlacesResolver;
import org.telegram.messenger.places.PlacesStorage;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class AutoPlacesRepository implements NotificationCenter.NotificationCenterDelegate {
    static final String LIST_KEY = "section:places";
    // Local reads also paginate: filtered channel rows must not hide older chat destinations.
    private static final int READ_LIMIT = 120;
    // Pages per refresh: an index dominated by channel rows must not be walked end to end.
    private static final int MAX_READ_PAGES = 5;
    private static final int ITEM_LIMIT = 20;
    private static final int METADATA_LIMIT = ITEM_LIMIT + 3;
    private static final int MAX_PAGE_WRITE_ATTEMPTS = 3;
    private static final int[] EVENTS = {
            NotificationCenter.didReceiveNewMessages,
            NotificationCenter.replaceMessagesObjects,
            NotificationCenter.messagesDeleted,
            NotificationCenter.removeAllMessagesFromDialog,
            NotificationCenter.dialogsNeedReload,
            NotificationCenter.didUpdateConnectionState,
            NotificationCenter.fileLoaded
    };

    interface Listener { void onPlacesChanged(long version); }

    static final class Snapshot {
        final List<AutoPlaceItem> items;
        final boolean loading;
        final long version;

        Snapshot(List<AutoPlaceItem> items, boolean loading, long version) {
            this.items = items;
            this.loading = loading;
            this.version = version;
        }
    }

    private final int account;
    private final AccountInstance accountInstance;
    private final MessagesStorage storage;
    private final AutoAvatarProvider avatarProvider;
    private final PlaceDetailsResolver detailsResolver;
    private final PlacesResolver linkResolver;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final HashMap<String, PlaceDetailsResolver.Request> detailRequests = new HashMap<>();
    private final HashMap<Long, String> requestedAvatarPaths = new HashMap<>();
    // Map-link preview image per row, the place card's fallback picture.
    private final HashMap<String, String> previewImages = new HashMap<>();
    // Chats storage does not have; UI thread only.
    private final HashSet<Long> absentChats = new HashSet<>();
    private final AutoPlacesHistory[] globalStreams = newStreams();
    private final Runnable debouncedRefresh = this::reload;
    private final Runnable globalResume = this::resumeGlobal;
    private Snapshot snapshot = new Snapshot(Collections.emptyList(), true, 1);
    private volatile boolean started;
    private volatile int historyGeneration;
    private int generation, readVersion, drainChain;
    private boolean reading, readAgain, refreshPending;
    private long refreshPendingSince;
    private static final long MAX_REFRESH_WAIT_MS = 1_000;
    // networkActive: the Places tab was shown this session (network and the account-wide drain).
    private boolean seedPending = true, localLoading, recentIndexReady, networkActive;
    // Global search pause (stale epoch backoff or FLOOD_WAIT), in elapsedRealtime millis.
    private long globalPausedUntil, lastHeadReset;

    AutoPlacesRepository(int account, @NonNull AccountInstance accountInstance,
                         @NonNull AutoAvatarProvider avatarProvider,
                         @NonNull PlaceDetailsResolver detailsResolver) {
        this.account = account;
        this.accountInstance = accountInstance;
        this.storage = accountInstance.getMessagesStorage();
        this.avatarProvider = avatarProvider;
        this.detailsResolver = detailsResolver;
        linkResolver = new PlacesResolver(account);
    }

    private static AutoPlacesHistory[] newStreams() {
        AutoPlacesHistory[] streams = new AutoPlacesHistory[AutoPlacesHistory.KINDS];
        for (int i = 0; i < streams.length; i++) streams[i] = new AutoPlacesHistory(i);
        return streams;
    }

    void start() {
        if (started) return;
        started = true;
        NotificationCenter center = NotificationCenter.getInstance(account);
        for (int event : EVENTS) center.addObserver(this, event);
        int token = ++generation;
        resetGlobalSearch();
        lastHeadReset = SystemClock.elapsedRealtime();
        readAndDeliver(token);
        drainNext(token, 0);
    }

    /**
     * The local index only holds what this device cached or what the phone tab already fetched.
     * Ask the server for the newest places across every chat, exactly as the chat tab does for one
     * dialog, and feed the answer into the same index.
     */
    private void fetchNextGlobal() {
        if (!started || !networkActive) return;
        if (ConnectionsManager.getInstance(account).getConnectionState()
                == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < globalPausedUntil) {
            AndroidUtilities.cancelRunOnUIThread(globalResume);
            AndroidUtilities.runOnUIThread(globalResume, globalPausedUntil - now);
            return;
        }
        boolean enough = snapshot.items.size() >= ITEM_LIMIT;
        int oldest = enough ? snapshot.items.get(ITEM_LIMIT - 1).date : 0;
        AutoPlacesHistory stream = AutoPlacesHistory.next(globalStreams, oldest, enough,
                !enough && linkResolver.hasPending());
        if (stream == null) return;
        stream.busy = true;
        int token = historyGeneration;
        // Every response uses the same edit/delete barrier as the phone Places tab.
        storage.getStorageQueue().postRunnable(() -> {
            try {
                long epoch = PlacesStorage.deletionEpoch(storage.getDatabase());
                AndroidUtilities.runOnUIThread(() -> sendGlobal(stream, token, epoch));
            } catch (Exception e) {
                storage.checkSQLException(e);
                AndroidUtilities.runOnUIThread(() -> failGlobal(stream, token));
            }
        });
    }

    private void sendGlobal(AutoPlacesHistory stream, int token, long epoch) {
        if (!started || token != historyGeneration) return;
        TLRPC.InputPeer peer = stream.offsetDialog == 0 ? null
                : accountInstance.getMessagesController().getInputPeer(stream.offsetDialog);
        if (stream.offsetDialog != 0 && (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty)) {
            failGlobal(stream, token);
            return;
        }
        stream.requestId = ConnectionsManager.getInstance(account).sendRequest(stream.request(peer), (response, error) ->
                AndroidUtilities.runOnUIThread(() -> onGlobalResponse(stream, token, epoch, response, error)));
    }

    private void onGlobalResponse(AutoPlacesHistory stream, int token, long epoch,
                                  TLObject response, TLRPC.TL_error error) {
        if (!started || token != historyGeneration) return;
        stream.requestId = 0;
        if (error != null || !(response instanceof TLRPC.messages_Messages)) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("[AutoPlacesDiag] global kind=" + stream.kind + " failed "
                        + (error != null ? error.text : "no response"));
            }
            int floodSeconds = AutoPlacesHistory.floodWaitSeconds(error != null ? error.text : null);
            if (floodSeconds > 0) {
                // Keep the cursor; the whole global search waits out the server limit.
                stream.busy = false;
                pauseGlobal(floodSeconds * 1000L);
                return;
            }
            failGlobal(stream, token);
            return;
        }
        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
        final AutoPlacesHistory next;
        try {
            next = stream.advance(res);
        } catch (IllegalStateException e) {
            FileLog.e(e);
            failGlobal(stream, token);
            return;
        }
        MessagesController controller = accountInstance.getMessagesController();
        controller.putUsers(res.users, false);
        controller.putChats(res.chats, false);
        storage.putUsersAndChats(res.users, res.chats, true, true);
        ArrayList<TLRPC.Message> messages = new ArrayList<>(res.messages);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("[AutoPlacesDiag] global kind=" + stream.kind + " messages=" + messages.size());
        }
        for (TLRPC.Message message : messages) message.dialog_id = MessageObject.getDialogId(message);
        writeGlobalPage(stream, token, next, withoutDeleted(messages), epoch);
    }

    /** UI thread: drops messages the account deleted meanwhile (upstream's deletion registry). */
    private ArrayList<TLRPC.Message> withoutDeleted(ArrayList<TLRPC.Message> messages) {
        MessagesController controller = accountInstance.getMessagesController();
        HashMap<Long, ArrayList<TLRPC.Message>> byDialog = new HashMap<>();
        for (TLRPC.Message message : messages) {
            ArrayList<TLRPC.Message> dialogMessages = byDialog.get(message.dialog_id);
            if (dialogMessages == null) {
                dialogMessages = new ArrayList<>();
                byDialog.put(message.dialog_id, dialogMessages);
            }
            dialogMessages.add(message);
        }
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        for (Long dialog : byDialog.keySet()) {
            ArrayList<TLRPC.Message> dialogMessages = byDialog.get(dialog);
            controller.removeDeletedMessagesFromPlacesSearch(dialog, dialogMessages);
            result.addAll(dialogMessages);
        }
        return result;
    }

    /**
     * The epoch moves on every message write in the account, and the deletion registry only knows
     * cleared histories, so a page that loses the epoch check may hold a message deleted in flight.
     * It is requested again after 1, 2 and 4 s. After that the cursor advances but only messages
     * the device stores are indexed (from their local copy); search-only rows of that page are
     * dropped rather than risk resurrecting a deleted one.
     */
    private void writeGlobalPage(AutoPlacesHistory stream, int token, AutoPlacesHistory next,
                                 ArrayList<TLRPC.Message> messages, long epoch) {
        boolean lastChance = stream.staleAttempts >= MAX_PAGE_WRITE_ATTEMPTS;
        storage.getStorageQueue().postRunnable(() -> {
            boolean accepted = false, failed = false, queuedLocal = false;
            try {
                SQLiteDatabase db = storage.getDatabase();
                boolean current = epoch == PlacesStorage.deletionEpoch(db);
                if (started && token == historyGeneration && (current || lastChance)) {
                    db.beginTransaction();
                    try {
                        for (TLRPC.Message message : messages) {
                            if (PlacesStorage.queueLocalCopy(db, message)) {
                                queuedLocal = true;
                            } else if (current) {
                                long topic = MessageObject.getTopicId(account, message,
                                        storage.getForumTypeFlags(message.dialog_id));
                                PlacesStorage.put(db, message, topic);
                            }
                        }
                    } finally {
                        db.commitTransaction();
                    }
                    accepted = true;
                }
            } catch (Exception e) {
                storage.checkSQLException(e);
                failed = true;
            }
            boolean saved = accepted, failure = failed, drain = queuedLocal;
            AndroidUtilities.runOnUIThread(() -> {
                if (!started || token != historyGeneration) return;
                if (failure) { failGlobal(stream, token); return; }
                stream.busy = false;
                if (!saved) {
                    stream.staleAttempts++;
                    pauseGlobal(1000L << (stream.staleAttempts - 1));
                    return;
                }
                globalStreams[stream.kind] = next;
                readAndDeliver(generation);
                // Locally stored results were queued for the drain; index them now.
                if (drain) drainNext(generation, 0);
            });
        });
    }

    private void failGlobal(AutoPlacesHistory stream, int token) {
        if (!started || token != historyGeneration) return;
        stream.busy = false;
        stream.failed = true;
        readAndDeliver(generation);
    }

    private void resumeGlobal() {
        if (started) readAndDeliver(generation);
    }

    private void pauseGlobal(long delayMs) {
        globalPausedUntil = Math.max(globalPausedUntil, SystemClock.elapsedRealtime() + delayMs);
        readAndDeliver(generation);
    }

    private void resetGlobalSearch() {
        historyGeneration++;
        AndroidUtilities.cancelRunOnUIThread(globalResume);
        for (int i = 0; i < globalStreams.length; i++) {
            if (globalStreams[i].requestId != 0) {
                ConnectionsManager.getInstance(account).cancelRequest(globalStreams[i].requestId, true);
            }
            globalStreams[i] = new AutoPlacesHistory(i);
        }
    }

    /**
     * Re-selecting the open tab (the host also re-reports it when a card pops): restart only
     * failed server searches. Link results stay, and an unchanged list is not republished.
     */
    void retrySearch() {
        boolean failed = false;
        for (AutoPlacesHistory stream : globalStreams) {
            if (!stream.failed) continue;
            stream.failed = false;
            failed = true;
        }
        if (failed) scheduleRefresh();
    }

    void retry() {
        for (AutoPlacesHistory stream : globalStreams) stream.failed = false;
        linkResolver.retryFailed();
        scheduleRefresh();
    }

    void destroy() {
        if (!started) return;
        started = false;
        generation++;
        AndroidUtilities.cancelRunOnUIThread(debouncedRefresh);
        resetGlobalSearch();
        NotificationCenter center = NotificationCenter.getInstance(account);
        for (int event : EVENTS) center.removeObserver(this, event);
        listeners.clear();
        detailRequests.clear();
        requestedAvatarPaths.clear();
        absentChats.clear();
        detailsResolver.destroy();
        linkResolver.close();
    }

    Snapshot getSnapshot() { return snapshot; }
    void addListener(Listener listener) { listeners.addIfAbsent(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }

    /**
     * The Places tab was shown. Until then the repository only reads the local index: no link
     * previews and no server search run for a user who never opens the tab.
     */
    void activate() {
        if (!started || networkActive) return;
        networkActive = true;
        scheduleRefresh();
    }

    void awaitDetails(AutoPlaceItem item, String language, long timeoutMs, PlaceDetailsResolver.Callback callback) {
        PlaceDetailsResolver.Request request = detailRequests.get(item.key);
        if (request == null) {
            // item.title may be the caption and item.subtitle coordinates: neither is a place fact.
            PlaceDetails base = new PlaceDetails();
            base.title = item.placeName;
            request = new PlaceDetailsResolver.Request(item.key, item.dialogId,
                    item.latitude, item.longitude, item.placeName, base, item.isLocalOnly());
        }
        // ML Kit "und" or romanized tags mean unknown: Nominatim then answers in the device language.
        String normalized = AutoSpeechLanguage.normalize(language);
        if (normalized != null) request = request.withLanguage(normalized);
        detailsResolver.awaitDetails(request, timeoutMs, callback);
    }


    /** The map-link preview picture, only for places whose link metadata was already fetched. */
    String previewImage(AutoPlaceItem item) { return previewImages.get(item.key); }

    @Override
    public void didReceivedNotification(int id, int notificationAccount, Object... args) {
        if (!started || notificationAccount != account) return;
        if (id == NotificationCenter.fileLoaded) {
            handleFileLoaded(args);
            return;
        }
        if (id == NotificationCenter.didUpdateConnectionState) {
            if (ConnectionsManager.getInstance(account).getConnectionState() == ConnectionsManager.ConnectionStateConnected) {
                long now = SystemClock.elapsedRealtime();
                if (AutoPlacesHistory.shouldResetHead(now, lastHeadReset)) {
                    lastHeadReset = now;
                    resetGlobalSearch();
                } else {
                    for (AutoPlacesHistory stream : globalStreams) stream.failed = false;
                }
                linkResolver.retryFailed();
            }
            scheduleRefresh();
            return;
        }
        if ((id == NotificationCenter.didReceiveNewMessages || id == NotificationCenter.replaceMessagesObjects)
                && args.length > 1 && args[1] instanceof ArrayList) {
            if (id == NotificationCenter.didReceiveNewMessages && args.length > 2 && Boolean.TRUE.equals(args[2])) return;
            ArrayList<?> values = (ArrayList<?>) args[1];
            ArrayList<MessageObject> messages = new ArrayList<>();
            for (Object value : values) {
                // A message still being sent has a temporary negative id that is renamed in place
                // on success (no trigger fires), so indexing it would leave a duplicate row.
                if (value instanceof MessageObject && !((MessageObject) value).scheduled
                        && ((MessageObject) value).getId() > 0 && !((MessageObject) value).isSending()) {
                    MessageObject message = (MessageObject) value;
                    message.messageOwner.dialog_id = message.getDialogId();
                    messages.add(message);
                }
            }
            if (!messages.isEmpty()) {
                MessageObject newest = messages.get(0);
                for (MessageObject message : messages) {
                    if (message.messageOwner.date > newest.messageOwner.date) newest = message;
                }
                if (isEligibleSource(newest.messageOwner)) linkResolver.prioritize(new PlaceEntry(newest));
                boolean edits = id == NotificationCenter.replaceMessagesObjects;
                storage.getStorageQueue().postRunnable(() -> {
                    try {
                        PlacesStorage.indexMessages(storage, account, messages, edits);
                    } catch (Exception e) {
                        storage.checkSQLException(e);
                    }
                    // Debounced like every other refresh; nothing is read before Places is shown.
                    AndroidUtilities.runOnUIThread(() -> {
                        if (started && networkActive) scheduleRefresh();
                    });
                });
                return;
            }
        }
        scheduleRefresh();
    }

    private void reload() {
        refreshPending = false;
        if (!started || !networkActive) return;
        int token = generation;
        readAndDeliver(token);
        drainNext(token, 0);
    }

    /**
     * Trailing 250 ms debounce with a 1 s maximum wait, so a steady stream of events cannot
     * postpone the refresh for the whole burst.
     */
    private void scheduleRefresh() {
        long now = SystemClock.elapsedRealtime();
        if (refreshPending && now - refreshPendingSince >= MAX_REFRESH_WAIT_MS) return;
        if (!refreshPending) {
            refreshPending = true;
            refreshPendingSince = now;
        }
        invalidatePendingRefresh();
        AndroidUtilities.runOnUIThread(debouncedRefresh, 250);
    }

    private int invalidatePendingRefresh() {
        AndroidUtilities.cancelRunOnUIThread(debouncedRefresh);
        return ++generation;
    }

    /**
     * One read at a time. A request during a read (drain progress, a finished link, a refresh)
     * marks it dirty instead of discarding it: a multi-page read that keeps being superseded
     * would never publish. The dirty read runs once the current one has published.
     */
    private void readAndDeliver(int token) {
        // Nothing is read before the Places tab is shown; activate() starts the first read.
        if (!networkActive) return;
        if (reading) {
            readAgain = true;
            return;
        }
        reading = true;
        readPage(token, ++readVersion, null, 0, new ArrayList<>(), new HashMap<>());
    }

    private void finishRead() {
        reading = false;
        if (readAgain && started) {
            readAgain = false;
            readAndDeliver(generation);
        }
    }

    private void readPage(int token, int version, PlacesStorage.RecentPage after, int pageIndex, ArrayList<TLRPC.Message> accumulated,
                          HashMap<String, JSONObject> metadata) {
        storage.getStorageQueue().postRunnable(() -> {
            PlacesStorage.RecentPage page = null;
            ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            ArrayList<Long> missingChats = new ArrayList<>();
            boolean failed = false;
            try {
                SQLiteDatabase db = storage.getDatabase();
                // Legacy 180 databases lack the recent index; build it before the first ordered read.
                if (!recentIndexReady) {
                    PlacesStorage.ensureRecentIndex(db);
                    recentIndexReady = true;
                }
                page = PlacesStorage.readRecent(db, account, READ_LIMIT, after);
                loadMissingChats(page.messages, chats, missingChats);
                // Link previews for this page's URLs only, not the whole week-long cache.
                if (SharedConfig.extendedPreviews) loadPreviews(db, page.messages, metadata);
            } catch (Exception e) {
                storage.checkSQLException(e);
                failed = true;
            }
            PlacesStorage.RecentPage resultPage = page;
            boolean resultFailed = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (!started || version != readVersion) return;
                if (resultFailed || resultPage == null) {
                    publish(snapshot.items, false, null, null, null);
                    finishRead();
                } else {
                    MessagesController controller = accountInstance.getMessagesController();
                    for (TLRPC.Chat chat : chats) controller.putChat(chat, true);
                    absentChats.addAll(missingChats);
                    for (TLRPC.Message message : resultPage.messages) {
                        if (isEligibleSource(message)) accumulated.add(message);
                    }
                    boolean lastPage = resultPage.rows < READ_LIMIT || pageIndex + 1 >= MAX_READ_PAGES;
                    // Fewer candidate rows than list items cannot fill the list: skip the build.
                    boolean enough = (lastPage || accumulated.size() >= ITEM_LIMIT)
                            && buildAndPublish(accumulated, metadata, lastPage);
                    if (!enough && !lastPage) {
                        readPage(token, version, resultPage, pageIndex + 1, accumulated, metadata);
                    } else {
                        finishRead();
                        fetchNextGlobal();
                    }
                }
            });
        });
    }

    /** Starts the drain; a newer start supersedes a running chain, so chains never pile up. */
    private void drainNext(int token, int completedBatches) {
        drainStep(token, completedBatches == 0 ? ++drainChain : drainChain, completedBatches);
    }

    private void drainStep(int token, int chain, int completedBatches) {
        // The account-wide seed and drain load the shared storage queue: only once Places is shown.
        // Chains supersede each other through drainChain only; refreshes no longer abort a drain.
        if (!started || !networkActive || chain != drainChain) return;
        // localLoading is set from a finished batch only: flagging it before work is known made
        // every refresh publish a spinner first (an empty tab flipped to "loading" and back).
        storage.getStorageQueue().postRunnable(() -> {
            boolean more = false;
            boolean failed = false;
            try {
                if (!recentIndexReady) {
                    PlacesStorage.ensureRecentIndex(storage.getDatabase());
                    recentIndexReady = true;
                }
                if (seedPending) seedPending = PlacesStorage.seedRecentDialogsBatch(storage.getDatabase(), this::isStoredBroadcastChannel);
                more = PlacesStorage.drainPendingBatch(storage, account);
            } catch (Exception e) {
                storage.checkSQLException(e);
                failed = true;
            }
            boolean remaining = more || seedPending;
            boolean failure = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (!started || chain != drainChain) return;
                int count = completedBatches + 1;
                localLoading = !failure && remaining;
                if (failure) {
                    publish(snapshot.items, false, null, null, null);
                    return;
                }
                if (!remaining || count % 5 == 0) readAndDeliver(token);
                if (remaining) drainStep(token, chain, count);
            });
        });
    }

    private boolean isEligibleSource(TLRPC.Message message) {
        if (message == null) return false;
        long dialog = message.dialog_id;
        if (EmergencyPasscode.isHidden(account, dialog)) return false;
        long self = UserConfig.getInstance(account).getClientUserId();
        if (dialog == self && (message.saved_peer_id != null || message.from_id != null)
                && EmergencyPasscode.isHidden(account,
                MessageObject.getSavedDialogId(self, message))) return false;
        MessagesController controller = accountInstance.getMessagesController();
        if (isBroadcastChannel(controller, dialog)) return false;
        TLRPC.User user = DialogObject.isUserDialog(dialog) ? controller.getUser(dialog) : null;
        return user == null || !UserObject.isDeleted(user);
    }

    /**
     * Publishes only a final list (enough rows, or the last page read), so a refresh never shows
     * the first page's few rows before the rest arrive.
     */
    private boolean buildAndPublish(ArrayList<TLRPC.Message> messages, HashMap<String, JSONObject> metadata,
                                    boolean lastPage) {
        ArrayList<AutoPlaceItem> items = new ArrayList<>();
        ArrayList<PlaceEntry> resolving = new ArrayList<>();
        HashMap<String, PlaceDetailsResolver.Request> requests = new HashMap<>();
        HashMap<String, String> images = new HashMap<>();
        HashMap<Long, String> avatars = new HashMap<>();
        MessagesController controller = accountInstance.getMessagesController();
        long selfId = UserConfig.getInstance(account).getClientUserId();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < messages.size() && items.size() < ITEM_LIMIT; i++) {
            TLRPC.Message message = messages.get(i);
            long dialogId = message.dialog_id;
            if (!isEligibleSource(message) || !seen.add(dialogId + ":" + message.id)) continue;

            // No media-existence checks: this runs on the UI thread for every candidate row.
            MessageObject messageObject = new MessageObject(account, message, false, false);
            PlaceEntry entry = new PlaceEntry(messageObject);
            for (int j = 0; j < entry.places.size(); j++) {
                Place place = entry.places.get(j);
                if (SharedConfig.extendedPreviews && !DialogObject.isEncryptedDialog(dialogId)
                        && !place.spoiler && place.originalUrl != null) {
                    JSONObject cached = metadata.get(place.originalUrl);
                    if (cached != null) applyCarMetadata(place, cached);
                }
            }
            linkResolver.apply(entry, AutoPlacesRepository::applyCarMetadata);
            if (resolving.size() < METADATA_LIMIT && linkResolver.needsMetadata(entry)) resolving.add(entry);
            Place chosen = AutoPlaceItem.firstNavigable(entry.places);
            if (chosen == null) continue;

            boolean group = DialogObject.isChatDialog(dialogId);
            String chatTitle = group || message.out ? avatarProvider.resolveDialogName(dialogId) : null;
            long senderId = message.out ? selfId : MessageObject.getFromChatId(message);
            String senderName = message.out ? "You" : resolveSenderName(controller, senderId);
            if (senderId == 0) senderName = avatarProvider.resolveDialogName(dialogId);
            if (TextUtils.isEmpty(senderName)) senderName = avatarProvider.resolveDialogName(senderId);
            AutoPlaceItem item = AutoPlaceItem.fromEntry(entry, senderId, senderName, chatTitle, group);
            if (item == null || item.buildNavigationUri() == null) continue;
            items.add(item);

            PlaceDetails base = new PlaceDetails();
            base.title = chosen.title;
            base.address = chosen.address;
            if (SharedConfig.extendedPreviews && !DialogObject.isEncryptedDialog(dialogId) && chosen.originalUrl != null) {
                base.merge(PlaceDetailsResolver.parseOpenGraph(metadata.get(chosen.originalUrl)));
            }
            // placeName, not the display title: a caption must never reach Overpass.
            if (chosen.imageUrl != null && chosen.imageUrl.startsWith("https://") && mayFetchExternal(item)) {
                images.put(item.key, chosen.imageUrl);
            }
            requests.put(item.key, new PlaceDetailsResolver.Request(item.key, dialogId,
                    item.latitude, item.longitude, item.placeName, base,
                    item.isLocalOnly()));
            trackAvatar(controller, avatars, senderId == 0 ? dialogId : senderId);
        }
        // Link previews are network requests: none before the Places tab is opened.
        linkResolver.setVisible(networkActive ? resolving : new ArrayList<>(), this::scheduleRefresh);
        boolean enough = items.size() >= ITEM_LIMIT;
        int oldest = enough ? items.get(ITEM_LIMIT - 1).date : 0;
        boolean loading = localLoading || linkResolver.hasPending();
        if (networkActive && ConnectionsManager.getInstance(account).getConnectionState() != ConnectionsManager.ConnectionStateWaitingForNetwork) {
            for (AutoPlacesHistory stream : globalStreams) {
                loading |= !stream.done && !stream.failed && (!enough || !stream.covers(oldest));
            }
        }
        // Resolve a bounded newest window before walking further back through unresolved links.
        boolean stop = enough || resolving.size() >= METADATA_LIMIT && linkResolver.hasPending();
        if (stop || lastPage) publish(items, loading, requests, avatars, images);
        return stop;
    }

    private void publish(List<AutoPlaceItem> items, boolean loading,
                         HashMap<String, PlaceDetailsResolver.Request> requests,
                         HashMap<Long, String> avatars, HashMap<String, String> images) {
        long signature = signature(items, loading);
        long oldSignature = signature(snapshot.items, snapshot.loading);
        if (requests != null) {
            detailRequests.clear();
            detailRequests.putAll(requests);
            previewImages.clear();
            if (images != null) previewImages.putAll(images);
        }
        if (avatars != null) {
            requestedAvatarPaths.clear();
            requestedAvatarPaths.putAll(avatars);
        }
        if (signature == oldSignature) return;
        snapshot = new Snapshot(Collections.unmodifiableList(new ArrayList<>(items)), loading, snapshot.version + 1);
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).onPlacesChanged(snapshot.version);
    }

    private void handleFileLoaded(Object[] args) {
        String loaded = null;
        for (Object arg : args) {
            if (arg instanceof File) loaded = ((File) arg).getAbsolutePath();
            else if (arg instanceof String && ((String) arg).contains(File.separator)) loaded = (String) arg;
        }
        if (loaded == null || !requestedAvatarPaths.containsValue(loaded)) return;
        Long loadedPeer = null;
        for (Long peerId : requestedAvatarPaths.keySet()) {
            if (loaded.equals(requestedAvatarPaths.get(peerId))) {
                loadedPeer = peerId;
                break;
            }
        }
        if (loadedPeer != null) requestedAvatarPaths.remove(loadedPeer);
        avatarProvider.clearAvatars();
        snapshot = new Snapshot(snapshot.items, snapshot.loading, snapshot.version + 1);
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).onPlacesChanged(snapshot.version);
    }

    /**
     * Read-only broadcast channels are excluded; supergroups carry the same stored flag.
     * Runs on the UI thread and never blocks on storage: readPage preloads chats. A chat not yet
     * looked up is skipped for this pass; one that storage does not have either stays eligible,
     * as before (it cannot be proven to be a broadcast channel).
     */
    private boolean isBroadcastChannel(MessagesController controller, long dialogId) {
        if (!DialogObject.isChatDialog(dialogId)) return false;
        TLRPC.Chat chat = controller.getChat(-dialogId);
        if (chat == null) return !absentChats.contains(-dialogId);
        return ChatObject.isChannelAndNotMegaGroup(chat);
    }

    /** Same gates as the details resolver: explicit opens only, never for local-only places. */
    boolean mayFetchExternal(AutoPlaceItem item) {
        return !item.isLocalOnly() && SharedConfig.extendedPreviews
                && !DialogObject.isEncryptedDialog(item.dialogId)
                && !EmergencyPasscode.isHidden(account, item.dialogId)
                && ConnectionsManager.getInstance(account).getConnectionState()
                != ConnectionsManager.ConnectionStateWaitingForNetwork;
    }

    /** Storage queue only. */
    private static void loadPreviews(SQLiteDatabase db, ArrayList<TLRPC.Message> messages,
                                     HashMap<String, JSONObject> out) throws Exception {
        ArrayList<String> urls = new ArrayList<>();
        for (TLRPC.Message message : messages) {
            for (Place place : PlaceExtractor.extract(message)) {
                if (place.originalUrl != null && !out.containsKey(place.originalUrl) && !urls.contains(place.originalUrl)) {
                    urls.add(place.originalUrl);
                }
            }
        }
        long since = System.currentTimeMillis() / 1000 - 604800;
        for (int start = 0; start < urls.size(); start += 100) {
            List<String> chunk = urls.subList(start, Math.min(urls.size(), start + 100));
            StringBuilder sql = new StringBuilder("SELECT url,data FROM places_meta_v1 WHERE time>? AND url IN (");
            Object[] args = new Object[chunk.size() + 1];
            args[0] = since;
            for (int i = 0; i < chunk.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
                args[i + 1] = chunk.get(i);
            }
            SQLiteCursor cursor = db.queryFinalized(sql.append(')').toString(), args);
            try {
                while (cursor.next()) {
                    try {
                        out.put(cursor.stringValue(0), new JSONObject(cursor.stringValue(1)));
                    } catch (org.json.JSONException ignore) {
                        // A corrupt preview only loses its enrichment.
                    }
                }
            } finally {
                cursor.dispose();
            }
        }
    }

    /** Storage queue only: the seed skips channels the reader would discard anyway. */
    private boolean isStoredBroadcastChannel(long dialog) {
        if (!DialogObject.isChatDialog(dialog)) return false;
        TLRPC.Chat chat = accountInstance.getMessagesController().getChat(-dialog);
        if (chat == null) chat = storage.getChat(-dialog);
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat);
    }

    /** Storage queue only. */
    private void loadMissingChats(ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.Chat> out,
                                  ArrayList<Long> absent) {
        MessagesController controller = accountInstance.getMessagesController();
        HashSet<Long> requested = new HashSet<>();
        for (TLRPC.Message message : messages) {
            long dialogId = message.dialog_id;
            if (!DialogObject.isChatDialog(dialogId) || !requested.add(-dialogId)) continue;
            if (controller.getChat(-dialogId) != null) continue;
            TLRPC.Chat chat = storage.getChat(-dialogId);
            if (chat != null) out.add(chat);
            else absent.add(-dialogId);
        }
    }

    static void applyCarMetadata(Place place, JSONObject metadata) {
        String previousTitle = place.title;
        String previousAddress = place.address;
        PlacesResolver.apply(place, metadata);
        if (place.provider == Place.Provider.GOOGLE && place.latitude == null && !TextUtils.isEmpty(place.title)) {
            // A query/search link resolves to Google's generic card, but its preview map is
            // centered on the found place: the only key-free point for these links.
            String center = googleStaticMapCenter(place.imageUrl);
            if (center != null) place.setCoordinates(center, false, Place.Confidence.EXPLICIT_DESTINATION);
        }
        String rawTitle = metadata.optString("title", null);
        if (place.provider == Place.Provider.GOOGLE && "Google Maps".equalsIgnoreCase(rawTitle)) {
            Place destination = PlaceExtractor.parse(metadata.optString("resolved", null));
            place.title = destination != null && destination.provider == place.provider && !TextUtils.isEmpty(destination.title)
                    ? destination.title : previousTitle;
            place.address = previousAddress;
            return;
        }
        PlaceDetails parsed = PlaceDetailsResolver.parseOpenGraph(metadata);
        if (!TextUtils.isEmpty(parsed.title)) place.title = parsed.title;
        if (!TextUtils.isEmpty(parsed.address)) {
            place.address = parsed.address;
        } else if (!TextUtils.isEmpty(parsed.category) || parsed.stars >= 0) {
            place.address = previousAddress;
        }
    }

    /** "lat,lng" of a Google static map preview zoomed to a place (zoom 15+), else null. */
    static String googleStaticMapCenter(String imageUrl) {
        if (TextUtils.isEmpty(imageUrl)) return null;
        try {
            android.net.Uri uri = android.net.Uri.parse(imageUrl);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null || !"https".equals(uri.getScheme())
                    || !(host.equals("maps.google.com") || host.endsWith(".google.com"))
                    || !path.contains("/staticmap")) return null;
            String zoom = uri.getQueryParameter("zoom");
            // The Google Maps home card is a wide region around the requester, not a destination.
            if (zoom == null || Integer.parseInt(zoom.trim()) < 15) return null;
            String center = uri.getQueryParameter("center");
            return center != null && center.matches("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?") ? center : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String resolveSenderName(MessagesController controller, long senderId) {
        if (senderId > 0) {
            TLRPC.User user = controller.getUser(senderId);
            return user == null || UserObject.isDeleted(user) ? null : UserObject.getUserName(user);
        }
        if (senderId < 0) {
            TLRPC.Chat chat = controller.getChat(-senderId);
            return chat != null ? chat.title : null;
        }
        return null;
    }

    private void trackAvatar(MessagesController controller, HashMap<Long, String> paths, long peerId) {
        TLRPC.FileLocation location = null;
        if (peerId > 0) {
            TLRPC.User user = controller.getUser(peerId);
            if (user != null && user.photo != null) location = user.photo.photo_small;
        } else if (peerId < 0) {
            TLRPC.Chat chat = controller.getChat(-peerId);
            if (chat != null && chat.photo != null) location = chat.photo.photo_small;
        }
        if (location != null) {
            File path = FileLoader.getInstance(account).getPathToAttach(location, true);
            if (path != null) paths.put(peerId, path.getAbsolutePath());
        }
    }

    static long signature(List<AutoPlaceItem> items, boolean loading) {
        long result = loading ? 1 : 0;
        for (int i = 0; i < items.size(); i++) {
            AutoPlaceItem item = items.get(i);
            result = result * 31 + item.key.hashCode();
            result = result * 31 + item.displaySenderTitle().hashCode();
            result = result * 31 + item.title.hashCode();
            result = result * 31 + item.subtitle.hashCode();
            result = result * 31 + (item.latitude == null ? 0 : item.latitude.hashCode());
            result = result * 31 + (item.longitude == null ? 0 : item.longitude.hashCode());
            result = result * 31 + (item.query == null ? 0 : item.query.hashCode());
            result = result * 31 + (item.messageText == null ? 0 : item.messageText.hashCode());
            result = result * 31 + item.date;
            result = result * 31 + item.provider.ordinal();
            result = result * 31 + (item.isLive ? 1 : 0);
        }
        return result;
    }
}
