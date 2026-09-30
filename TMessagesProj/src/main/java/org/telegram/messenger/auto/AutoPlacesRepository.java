package org.telegram.messenger.auto;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class AutoPlacesRepository implements NotificationCenter.NotificationCenterDelegate {
    static final String LIST_KEY = "section:places";
    // Local reads also paginate: filtered channel rows must not hide older chat destinations.
    private static final int READ_LIMIT = 120;
    private static final int ITEM_LIMIT = 20;
    private static final int METADATA_LIMIT = ITEM_LIMIT + 3;
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
    private final AutoPlacesHistory[] globalStreams = {new AutoPlacesHistory(0), new AutoPlacesHistory(1)};
    private final Runnable debouncedRefresh = this::reload;
    private Snapshot snapshot = new Snapshot(Collections.emptyList(), true, 1);
    private volatile boolean started;
    private volatile int historyGeneration;
    private int generation, readVersion;
    private boolean seedPending = true, localLoading;

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

    void start() {
        if (started) return;
        started = true;
        NotificationCenter center = NotificationCenter.getInstance(account);
        for (int event : EVENTS) center.addObserver(this, event);
        int token = ++generation;
        resetGlobalSearch();
        readAndDeliver(token);
        drainNext(token, 0);
    }

    /**
     * The local index only holds what this device cached or what the phone tab already fetched.
     * Ask the server for the newest places across every chat, exactly as the chat tab does for one
     * dialog, and feed the answer into the same index.
     */
    private void fetchNextGlobal() {
        if (!started) return;
        if (ConnectionsManager.getInstance(account).getConnectionState()
                == ConnectionsManager.ConnectionStateWaitingForNetwork) {
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
                long epoch = PlacesStorage.epoch(storage.getDatabase());
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
        HashMap<Long, ArrayList<TLRPC.Message>> byDialog = new HashMap<>();
        for (TLRPC.Message message : messages) {
            message.dialog_id = MessageObject.getDialogId(message);
            ArrayList<TLRPC.Message> dialogMessages = byDialog.get(message.dialog_id);
            if (dialogMessages == null) {
                dialogMessages = new ArrayList<>();
                byDialog.put(message.dialog_id, dialogMessages);
            }
            dialogMessages.add(message);
        }
        messages.clear();
        for (Long dialog : byDialog.keySet()) {
            ArrayList<TLRPC.Message> dialogMessages = byDialog.get(dialog);
            controller.removeDeletedMessagesFromPlacesSearch(dialog, dialogMessages);
            messages.addAll(dialogMessages);
        }
        storage.getStorageQueue().postRunnable(() -> {
            boolean accepted = false, failed = false;
            try {
                SQLiteDatabase db = storage.getDatabase();
                if (started && token == historyGeneration && epoch == PlacesStorage.epoch(db)) {
                    db.beginTransaction();
                    try {
                        for (TLRPC.Message message : messages) {
                            long topic = MessageObject.getTopicId(account, message,
                                    storage.getForumTypeFlags(message.dialog_id));
                            PlacesStorage.put(db, message, topic);
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
            boolean saved = accepted, failure = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (!started || token != historyGeneration) return;
                if (failure) { failGlobal(stream, token); return; }
                stream.busy = false;
                if (saved) globalStreams[stream.kind] = next;
                // A concurrent write won: keep the cursor and request this page again.
                readAndDeliver(generation);
            });
        });
    }

    private void failGlobal(AutoPlacesHistory stream, int token) {
        if (!started || token != historyGeneration) return;
        stream.busy = false;
        stream.failed = true;
        readAndDeliver(generation);
    }

    private void resetGlobalSearch() {
        historyGeneration++;
        for (int i = 0; i < globalStreams.length; i++) {
            if (globalStreams[i].requestId != 0) {
                ConnectionsManager.getInstance(account).cancelRequest(globalStreams[i].requestId, true);
            }
            globalStreams[i] = new AutoPlacesHistory(i);
        }
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
        detailsResolver.destroy();
        linkResolver.close();
    }

    Snapshot getSnapshot() { return snapshot; }
    void addListener(Listener listener) { listeners.addIfAbsent(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }

    void prefetchVisible() {
        for (int i = 0; i < snapshot.items.size(); i++) {
            PlaceDetailsResolver.Request request = detailRequests.get(snapshot.items.get(i).key);
            if (request != null) detailsResolver.prefetch(request);
        }
    }

    void awaitDetails(AutoPlaceItem item, long timeoutMs, PlaceDetailsResolver.Callback callback) {
        PlaceDetailsResolver.Request request = detailRequests.get(item.key);
        if (request == null) {
            PlaceDetails base = new PlaceDetails();
            base.title = item.title;
            if (!TextUtils.isEmpty(item.subtitle)) base.address = item.subtitle;
            request = new PlaceDetailsResolver.Request(item.key, item.dialogId,
                    item.latitude, item.longitude, item.title, base);
        }
        detailsResolver.awaitDetails(request, timeoutMs, callback);
    }

    void onListHidden() { detailsResolver.onListHidden(); }

    @Override
    public void didReceivedNotification(int id, int notificationAccount, Object... args) {
        if (!started || notificationAccount != account) return;
        if (id == NotificationCenter.fileLoaded) {
            handleFileLoaded(args);
            return;
        }
        if (id == NotificationCenter.didUpdateConnectionState) {
            if (ConnectionsManager.getInstance(account).getConnectionState() == ConnectionsManager.ConnectionStateConnected) {
                resetGlobalSearch();
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
                if (value instanceof MessageObject && !((MessageObject) value).scheduled) {
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
                int token = invalidatePendingRefresh();
                storage.getStorageQueue().postRunnable(() -> {
                    try {
                        PlacesStorage.indexMessages(storage, account, messages);
                    } catch (Exception e) {
                        storage.checkSQLException(e);
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!started || token != generation) return;
                        readAndDeliver(token);
                        // Indexing bumped the generation, which stopped the pending drain.
                        drainNext(token, 0);
                    });
                });
                return;
            }
        }
        scheduleRefresh();
    }

    private void reload() {
        if (!started) return;
        int token = generation;
        readAndDeliver(token);
        drainNext(token, 0);
    }

    private void scheduleRefresh() {
        invalidatePendingRefresh();
        AndroidUtilities.runOnUIThread(debouncedRefresh, 250);
    }

    private int invalidatePendingRefresh() {
        AndroidUtilities.cancelRunOnUIThread(debouncedRefresh);
        return ++generation;
    }

    private void readAndDeliver(int token) {
        readPage(token, ++readVersion, 0, new ArrayList<>(), new HashMap<>());
    }

    private void readPage(int token, int version, int offset, ArrayList<TLRPC.Message> accumulated,
                          HashMap<String, JSONObject> metadata) {
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<TLRPC.Message> messages = new ArrayList<>();
            boolean failed = false;
            try {
                SQLiteDatabase db = storage.getDatabase();
                messages = PlacesStorage.readRecent(db, account, READ_LIMIT, offset);
                if (offset == 0 && SharedConfig.extendedPreviews) {
                    SQLiteCursor cursor = db.queryFinalized("SELECT url,data FROM places_meta_v1 WHERE time>?",
                            System.currentTimeMillis() / 1000 - 604800);
                    try {
                        while (cursor.next()) metadata.put(cursor.stringValue(0), new JSONObject(cursor.stringValue(1)));
                    } finally {
                        cursor.dispose();
                    }
                }
            } catch (Exception e) {
                storage.checkSQLException(e);
                failed = true;
            }
            ArrayList<TLRPC.Message> resultMessages = messages;
            boolean resultFailed = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (!started || token != generation || version != readVersion) return;
                if (resultFailed) {
                    publish(snapshot.items, false, null, null);
                } else {
                    for (TLRPC.Message message : resultMessages) {
                        if (isEligibleSource(message)) accumulated.add(message);
                    }
                    boolean enough = buildAndPublish(accumulated, metadata);
                    if (!enough && resultMessages.size() == READ_LIMIT) {
                        readPage(token, version, offset + READ_LIMIT, accumulated, metadata);
                    } else fetchNextGlobal();
                }
            });
        });
    }

    private void drainNext(int token, int completedBatches) {
        if (!started || token != generation) return;
        localLoading = true;
        storage.getStorageQueue().postRunnable(() -> {
            boolean more = false;
            boolean failed = false;
            try {
                if (seedPending) seedPending = PlacesStorage.seedRecentDialogsBatch(storage.getDatabase());
                more = PlacesStorage.drainPendingBatch(storage, account);
            } catch (Exception e) {
                storage.checkSQLException(e);
                failed = true;
            }
            boolean remaining = more || seedPending;
            boolean failure = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (!started || token != generation) return;
                int count = completedBatches + 1;
                localLoading = !failure && remaining;
                if (failure) {
                    publish(snapshot.items, false, null, null);
                    return;
                }
                if (!remaining || count % 5 == 0) readAndDeliver(token);
                if (remaining) drainNext(token, count);
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

    private boolean buildAndPublish(ArrayList<TLRPC.Message> messages, HashMap<String, JSONObject> metadata) {
        ArrayList<AutoPlaceItem> items = new ArrayList<>();
        ArrayList<PlaceEntry> resolving = new ArrayList<>();
        HashMap<String, PlaceDetailsResolver.Request> requests = new HashMap<>();
        HashMap<Long, String> avatars = new HashMap<>();
        MessagesController controller = accountInstance.getMessagesController();
        long selfId = UserConfig.getInstance(account).getClientUserId();
        for (int i = 0; i < messages.size() && items.size() < ITEM_LIMIT; i++) {
            TLRPC.Message message = messages.get(i);
            long dialogId = message.dialog_id;
            if (!isEligibleSource(message)) continue;

            MessageObject messageObject = new MessageObject(account, message, false, true);
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
            requests.put(item.key, new PlaceDetailsResolver.Request(item.key, dialogId,
                    item.latitude, item.longitude, item.title, base));
            trackAvatar(controller, avatars, senderId == 0 ? dialogId : senderId);
        }
        linkResolver.setVisible(resolving, this::scheduleRefresh);
        boolean enough = items.size() >= ITEM_LIMIT;
        int oldest = enough ? items.get(ITEM_LIMIT - 1).date : 0;
        boolean loading = localLoading || linkResolver.hasPending();
        if (ConnectionsManager.getInstance(account).getConnectionState() != ConnectionsManager.ConnectionStateWaitingForNetwork) {
            for (AutoPlacesHistory stream : globalStreams) {
                loading |= !stream.done && !stream.failed && (!enough || !stream.covers(oldest));
            }
        }
        publish(items, loading, requests, avatars);
        // Resolve a bounded newest window before walking further back through unresolved links.
        return enough || resolving.size() >= METADATA_LIMIT && linkResolver.hasPending();
    }

    private void publish(List<AutoPlaceItem> items, boolean loading,
                         HashMap<String, PlaceDetailsResolver.Request> requests,
                         HashMap<Long, String> avatars) {
        long signature = signature(items, loading);
        long oldSignature = signature(snapshot.items, snapshot.loading);
        if (requests != null) {
            detailRequests.clear();
            detailRequests.putAll(requests);
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

    /** Read-only broadcast channels are excluded; supergroups carry the same stored flag. */
    private boolean isBroadcastChannel(MessagesController controller, long dialogId) {
        if (!DialogObject.isChatDialog(dialogId)) return false;
        TLRPC.Chat chat = controller.getChat(-dialogId);
        if (chat == null) {
            chat = storage.getChatSync(-dialogId);
            if (chat != null) controller.putChat(chat, true);
        }
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat);
    }

    static void applyCarMetadata(Place place, JSONObject metadata) {
        String previousTitle = place.title;
        String previousAddress = place.address;
        PlacesResolver.apply(place, metadata);
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
