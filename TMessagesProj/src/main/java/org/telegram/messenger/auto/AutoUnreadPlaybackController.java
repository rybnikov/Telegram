package org.telegram.messenger.auto;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.media3.common.Player;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Listens through one chat's unread messages in order: text through the shared speech
 * controller, voice, round and video sound through Telegram's VideoPlayer. Session scoped; only
 * one chat plays at a time.
 */
final class AutoUnreadPlaybackController implements NotificationCenter.NotificationCenterDelegate {

    interface Listener { void onStateChanged(); }

    private static final long READY_TIMEOUT_MS = 10_000L;
    private static final long DOWNLOAD_TIMEOUT_MS = 20_000L;
    private static final String KEY_PREFIX = "auto_unread_";
    private static final String TAG = "AutoPlayback";

    static final AutoPlaybackQueue.Labels LABELS = new AutoPlaybackQueue.Labels() {
        @NonNull @Override public String video(@NonNull String duration) { return "Video, " + duration; }
        @NonNull @Override public String videoNoSound() { return "Video without sound"; }
        @NonNull @Override public String longVideoSkipped(int minutes) {
            return "Long video, " + minutes + (minutes == 1 ? " minute" : " minutes") + ", skipped";
        }
        @NonNull @Override public String viewOnce(@NonNull String mediaLabel) {
            return "View once " + mediaLabel.toLowerCase(Locale.ENGLISH) + ", open it on the phone";
        }
        @NonNull @Override public String voiceLabel() { return "Voice message"; }
        @NonNull @Override public String roundLabel() { return "Video message"; }
    };

    private final CarContext carContext;
    private final AccountInstance accountInstance;
    private final AutoSpeechController speechController;
    private final AutoVoiceRecorderController recorderController;
    private final AudioManager audioManager;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        // Our own read-aloud steps and navigation prompts ask for a ducking focus; only a real
        // loss (call, another player) ends the listening.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            AndroidUtilities.runOnUIThread(this::stop);
        }
    };
    private final AutoSpeechController.Listener speechListener = this::onSpeechChanged;
    private final AutoVoiceRecorderController.Listener recorderListener = this::onRecorderChanged;
    private final Runnable readyTimeout = () -> onPlaybackFailed("ready timeout");
    private final Runnable downloadTimeout = () -> onPlaybackFailed("download timeout");

    private long activeDialogId;
    private int topMessageId;
    private List<AutoPlaybackQueue.Item> queue = new ArrayList<>();
    private int nextIndex;
    private AutoPlaybackQueue.Item currentItem;
    private int generation;
    private VideoPlayer player;
    private boolean playerReady;
    private String waitingDownload;
    private boolean observing;
    private boolean completedAll;
    private boolean unreadQueue;
    private boolean destroyed;

    AutoUnreadPlaybackController(@NonNull CarContext carContext,
                                 @NonNull AccountInstance accountInstance,
                                 @NonNull AutoSpeechController speechController,
                                 @NonNull AutoVoiceRecorderController recorderController) {
        this.carContext = carContext;
        this.accountInstance = accountInstance;
        this.speechController = speechController;
        this.recorderController = recorderController;
        this.audioManager = (AudioManager) carContext.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        speechController.addListener(speechListener);
        recorderController.addListener(recorderListener);
    }

    void addListener(@NonNull Listener listener) { listeners.addIfAbsent(listener); }
    void removeListener(@NonNull Listener listener) { listeners.remove(listener); }

    boolean isPlaying(long dialogId) {
        return activeDialogId != 0 && activeDialogId == dialogId;
    }

    long getSignature() {
        return activeDialogId * 31L + (activeDialogId != 0 ? 1 : 0);
    }

    /** Starts listening to {@code dialogId}, or stops it when it is the chat already playing. */
    void toggle(long dialogId, int topMessageId, boolean groupChat, @NonNull List<AutoPlaybackQueue.Entry> entries) {
        if (destroyed) return;
        if (isPlaying(dialogId)) {
            stop();
            return;
        }
        if (recorderController.isRecording()) {
            toast("Finish recording first");
            return;
        }
        stop();
        AutoPlaybackQueue.Result result = AutoPlaybackQueue.build(entries, groupChat, LABELS);
        List<AutoPlaybackQueue.Item> items = result.items;
        if (items.isEmpty()) {
            toast("Nothing to play");
            return;
        }
        if (!requestFocus()) {
            toast("Can't play now");
            return;
        }
        this.activeDialogId = dialogId;
        this.topMessageId = topMessageId;
        this.queue = items;
        this.nextIndex = 0;
        this.completedAll = false;
        this.unreadQueue = result.unread;
        Log.d(TAG, "start dialog=" + dialogId + " items=" + items.size() + " unread=" + result.unread);
        notifyListeners();
        playNext(generation);
    }

    void stop() {
        if (activeDialogId == 0 && player == null && !observing) return;
        generation++;
        boolean markRead = completedAll && unreadQueue;
        boolean finished = completedAll;
        long dialogId = activeDialogId;
        int readUpTo = topMessageId;
        AndroidUtilities.cancelRunOnUIThread(readyTimeout);
        AndroidUtilities.cancelRunOnUIThread(downloadTimeout);
        stopObservingDownloads();
        releasePlayer();
        String speechKey = speechController.getActiveKey();
        if (speechKey != null && speechKey.startsWith(KEY_PREFIX)) {
            speechController.stop();
        }
        abandonFocus();
        activeDialogId = 0;
        topMessageId = 0;
        queue = new ArrayList<>();
        nextIndex = 0;
        currentItem = null;
        completedAll = false;
        unreadQueue = false;
        if (dialogId != 0) {
            Log.d(TAG, "stop dialog=" + dialogId + " completed=" + finished + " markRead=" + markRead);
        }
        if (markRead && dialogId != 0) {
            accountInstance.getMessagesController().markDialogAsRead(
                    dialogId, readUpTo, readUpTo, 0, false, 0, 0, true, 0);
        }
        notifyListeners();
    }

    void destroy() {
        if (destroyed) return;
        stop();
        destroyed = true;
        speechController.removeListener(speechListener);
        recorderController.removeListener(recorderListener);
        listeners.clear();
    }

    private void playNext(int expectedGeneration) {
        if (expectedGeneration != generation || destroyed) return;
        if (nextIndex >= queue.size()) {
            completedAll = true;
            stop();
            return;
        }
        currentItem = queue.get(nextIndex++);
        AutoPlaybackQueue.Item item = currentItem;
        Log.d(TAG, "item " + nextIndex + "/" + queue.size() + " msg=" + item.messageId
                + " play=" + item.play + " spokenParts=" + item.spoken.size());
        if (item.spoken.isEmpty()) {
            afterSpoken(expectedGeneration, item);
            return;
        }
        speak(expectedGeneration, item.spoken, () -> afterSpoken(expectedGeneration, item));
    }

    private void afterSpoken(int expectedGeneration, @NonNull AutoPlaybackQueue.Item item) {
        if (expectedGeneration != generation) return;
        if (!item.play || !(item.payload instanceof MessageObject)) {
            playNext(expectedGeneration);
            return;
        }
        MessageObject messageObject = (MessageObject) item.payload;
        AutoMediaSource.Source source = AutoMediaSource.resolve(messageObject);
        if (source == null) {
            onPlaybackFailed("no document");
            return;
        }
        Log.d(TAG, "source msg=" + item.messageId + " uri=" + (source.uri != null ? source.uri.getScheme() : null)
                + " download=" + source.needsDownload());
        if (source.needsDownload()) {
            waitingDownload = source.downloadName;
            startObservingDownloads();
            AndroidUtilities.runOnUIThread(downloadTimeout, DOWNLOAD_TIMEOUT_MS);
            AutoMediaSource.download(messageObject);
            return;
        }
        startPlayer(expectedGeneration, source.uri);
    }

    private void startPlayer(int expectedGeneration, @Nullable Uri uri) {
        if (expectedGeneration != generation) return;
        if (uri == null) {
            onPlaybackFailed("no uri");
            return;
        }
        releasePlayer();
        playerReady = false;
        VideoPlayer created = new VideoPlayer(false, false);
        player = created;
        created.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
                if (expectedGeneration != generation || player != created) return;
                Log.d(TAG, "player state=" + playbackState + " playWhenReady=" + playWhenReady);
                if (playbackState == Player.STATE_READY) {
                    playerReady = true;
                    AndroidUtilities.cancelRunOnUIThread(readyTimeout);
                } else if (playbackState == Player.STATE_ENDED) {
                    onPlaybackEnded(expectedGeneration);
                }
            }

            @Override
            public void onError(VideoPlayer videoPlayer, Exception e) {
                if (expectedGeneration != generation || player != created) return;
                FileLog.e(e);
                onPlaybackFailed("player error");
            }

            @Override public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) { }
            @Override public void onRenderedFirstFrame() { }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surfaceTexture) { }
            @Override public boolean onSurfaceDestroyed(android.graphics.SurfaceTexture surfaceTexture) { return false; }
        });
        created.preparePlayer(uri, "other");
        created.setStreamType(AudioManager.STREAM_MUSIC);
        created.play();
        AndroidUtilities.runOnUIThread(readyTimeout, READY_TIMEOUT_MS);
    }

    private void onPlaybackEnded(int expectedGeneration) {
        AndroidUtilities.cancelRunOnUIThread(readyTimeout);
        releasePlayer();
        AutoPlaybackQueue.Item item = currentItem;
        if (item != null && item.markContentRead && item.payload instanceof MessageObject) {
            MessageObject messageObject = (MessageObject) item.payload;
            if (messageObject.isContentUnread()) {
                accountInstance.getMessagesController().markMessageContentAsRead(messageObject);
                messageObject.setContentIsRead();
            }
        }
        playNext(expectedGeneration);
    }

    private void onPlaybackFailed(String reason) {
        if (activeDialogId == 0) return;
        Log.d(TAG, "failed dialog=" + activeDialogId + " reason=" + reason);
        int expectedGeneration = generation;
        AndroidUtilities.cancelRunOnUIThread(readyTimeout);
        AndroidUtilities.cancelRunOnUIThread(downloadTimeout);
        stopObservingDownloads();
        releasePlayer();
        ArrayList<AutoPlaybackQueue.Part> spoken = new ArrayList<>();
        spoken.add(new AutoPlaybackQueue.Part("Couldn't play it", false));
        speak(expectedGeneration, spoken, () -> playNext(expectedGeneration));
    }

    private void speak(int expectedGeneration, @NonNull List<AutoPlaybackQueue.Part> parts, @NonNull Runnable next) {
        String key = KEY_PREFIX + activeDialogId + "_" + expectedGeneration + "_" + nextIndex;
        speechController.say(key, toUtterances(parts), outcome -> {
            if (expectedGeneration != generation) return;
            if (outcome == AutoSpeechController.Outcome.CANCELLED) {
                stop();
            } else {
                next.run();
            }
        });
    }

    @NonNull
    static List<AutoSpeechLanguage.Utterance> toUtterances(@NonNull List<AutoPlaybackQueue.Part> parts) {
        Locale device = Locale.getDefault();
        ArrayList<AutoSpeechLanguage.Utterance> result = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            AutoPlaybackQueue.Part part = parts.get(i);
            String text = part.text == null ? "" : part.text.trim();
            if (text.isEmpty()) continue;
            Locale locale = part.content ? AutoSpeechLanguage.contentLocale(text, null, device) : Locale.ENGLISH;
            result.add(new AutoSpeechLanguage.Utterance(text, locale));
        }
        return result;
    }

    private void onSpeechChanged() {
        if (activeDialogId == 0) return;
        String key = speechController.getActiveKey();
        // Another read-aloud (a place card) took the speaker over.
        if (key != null && !key.startsWith(KEY_PREFIX)) {
            stop();
        }
    }

    private void onRecorderChanged() {
        if (activeDialogId != 0 && recorderController.isRecording()) {
            stop();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (waitingDownload == null || args == null || args.length == 0) return;
        if (!waitingDownload.equals(args[0])) return;
        int expectedGeneration = generation;
        if (id == NotificationCenter.fileLoaded) {
            AndroidUtilities.cancelRunOnUIThread(downloadTimeout);
            stopObservingDownloads();
            File file = args.length > 1 && args[1] instanceof File ? (File) args[1] : null;
            if (file == null && currentItem != null && currentItem.payload instanceof MessageObject) {
                file = AutoMediaSource.localFile((MessageObject) currentItem.payload);
            }
            startPlayer(expectedGeneration, file != null ? Uri.fromFile(file) : null);
        } else if (id == NotificationCenter.fileLoadFailed) {
            onPlaybackFailed("download failed");
        }
    }

    private void startObservingDownloads() {
        if (observing) return;
        observing = true;
        NotificationCenter center = accountInstance.getNotificationCenter();
        center.addObserver(this, NotificationCenter.fileLoaded);
        center.addObserver(this, NotificationCenter.fileLoadFailed);
    }

    private void stopObservingDownloads() {
        waitingDownload = null;
        if (!observing) return;
        observing = false;
        NotificationCenter center = accountInstance.getNotificationCenter();
        center.removeObserver(this, NotificationCenter.fileLoaded);
        center.removeObserver(this, NotificationCenter.fileLoadFailed);
    }

    private void releasePlayer() {
        if (player == null) return;
        VideoPlayer old = player;
        player = null;
        playerReady = false;
        try {
            old.setDelegate(null);
            old.releasePlayer(true);
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
    }

    private boolean requestFocus() {
        if (audioManager == null) return false;
        try {
            return audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (RuntimeException e) {
            FileLog.e(e);
            return false;
        }
    }

    private void abandonFocus() {
        if (audioManager == null) return;
        try {
            audioManager.abandonAudioFocus(focusListener);
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
    }

    private void toast(String text) {
        CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show();
    }

    private void notifyListeners() {
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).onStateChanged();
    }

    /** Builds the queue input for one message; {@code unread} follows the chat card's rule. */
    @NonNull
    static AutoPlaybackQueue.Entry entryOf(@NonNull MessageObject messageObject, boolean unread, @Nullable String senderName) {
        TLRPC.Message owner = messageObject.messageOwner;
        AutoPlaybackQueue.Media media;
        boolean noSound = false;
        if (messageObject.isVoice()) {
            media = AutoPlaybackQueue.Media.VOICE;
        } else if (messageObject.isRoundVideo()) {
            media = AutoPlaybackQueue.Media.ROUND;
        } else if (messageObject.isGif()) {
            media = AutoPlaybackQueue.Media.GIF;
        } else if (messageObject.isVideo()) {
            media = AutoPlaybackQueue.Media.VIDEO;
            noSound = hasNoSound(messageObject.getDocument());
        } else if (messageObject.isMediaEmpty()) {
            media = AutoPlaybackQueue.Media.NONE;
        } else {
            media = AutoPlaybackQueue.Media.OTHER;
        }
        String caption = messageObject.caption != null ? messageObject.caption.toString() : null;
        String text;
        String mediaLabel = null;
        if (media == AutoPlaybackQueue.Media.NONE) {
            text = owner != null && !TextUtils.isEmpty(owner.message) ? owner.message
                    : messageObject.messageText != null ? messageObject.messageText.toString() : null;
        } else {
            text = !TextUtils.isEmpty(caption) ? caption : owner != null ? owner.message : null;
            if (media == AutoPlaybackQueue.Media.OTHER || media == AutoPlaybackQueue.Media.VIDEO) {
                String label = messageObject.messageText != null ? messageObject.messageText.toString() : null;
                mediaLabel = TextUtils.equals(label, text) ? null : label;
            }
        }
        boolean viewOnce = owner != null && owner.media != null && owner.media.ttl_seconds != 0;
        return new AutoPlaybackQueue.Entry(
                messageObject.getId(),
                owner != null ? owner.date : 0,
                messageObject.isOut(),
                unread,
                messageObject.getSenderId(),
                senderName,
                text,
                media,
                mediaLabel,
                (int) Math.round(messageObject.getDuration()),
                noSound,
                viewOnce,
                messageObject);
    }

    private static boolean hasNoSound(@Nullable TLRPC.Document document) {
        if (document == null || document.attributes == null) return false;
        for (int i = 0; i < document.attributes.size(); i++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(i);
            if (attribute instanceof TLRPC.TL_documentAttributeVideo && ((TLRPC.TL_documentAttributeVideo) attribute).nosound) {
                return true;
            }
        }
        return false;
    }
}
