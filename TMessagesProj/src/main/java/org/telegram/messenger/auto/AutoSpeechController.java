package org.telegram.messenger.auto;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

final class AutoSpeechController {
    enum State { IDLE, PREPARING, SPEAKING, ERROR }

    interface Listener { void onStateChanged(); }
    interface InitCallback { void onInit(int status, @Nullable Engine engine); }
    interface EngineFactory { void create(@NonNull InitCallback callback); }
    interface Engine {
        int setLanguage(Locale locale);
        void setProgress(Progress progress);
        /** {@code flush} starts a new summary; later fragments queue behind it. */
        int speak(String text, String utteranceId, boolean flush);
        /**
         * Media routes speech through the car's media channel, the one voice messages play on; a
         * switch to the guidance channel right after a voice message can drop the first words.
         */
        void setMediaUsage(boolean media);
        void stop();
        void shutdown();
    }
    interface Progress { void onDone(String utteranceId); void onError(String utteranceId); }
    interface Focus { boolean request(); void abandon(); }
    interface RecordingGuard { boolean isRecording(); }
    interface ToastSink { void show(String text); }
    interface Clock { long now(); }
    enum Outcome { DONE, FAILED, CANCELLED }
    /** Reported once per {@link #say}, on the UI thread, after the state change. */
    interface Completion { void onFinished(Outcome outcome); }

    private final EngineFactory engineFactory;
    private final Focus focus;
    private final RecordingGuard recordingGuard;
    private final ToastSink toast;
    private final Clock clock;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private Engine engine;
    private State state = State.IDLE;
    private String activeKey;
    private String activeUtteranceId;
    private String activeBatchPrefix;
    private Locale engineLocale;
    private boolean initializing;
    private String pendingKey;
    private List<AutoSpeechLanguage.Utterance> pendingText;
    private long lastClickAt = Long.MIN_VALUE;
    private int utteranceCounter;
    private int initAttempts;
    private boolean destroyed;
    private Completion completion;
    /**
     * A {@link #say} step: the caller holds audio focus for its whole sequence and speech goes to
     * the media channel. Taking and dropping focus per step makes the car reopen its audio stream
     * every time and drop what was buffered.
     */
    private boolean sequenceStep;
    private boolean holdsFocus;

    AutoSpeechController(@NonNull CarContext carContext,
                         @NonNull AutoVoiceRecorderController recorderController) {
        Context context = carContext.getApplicationContext();
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        final AudioManager.OnAudioFocusChangeListener[] focusListener = new AudioManager.OnAudioFocusChangeListener[1];
        focusListener[0] = change -> {
            // A duck request only asks for lower volume; stopping on it would cut the summary
            // every time navigation speaks.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                onAudioFocusLost();
            }
        };
        this.engineFactory = callback -> {
            final TextToSpeech[] holder = new TextToSpeech[1];
            // TextToSpeech can report a failure from inside its constructor, before holder[0] is set;
            // posting defers the hand-over until it is. A failed engine is still handed over so its
            // service binding gets shut down.
            holder[0] = new TextToSpeech(context, status -> AndroidUtilities.runOnUIThread(() ->
                    callback.onInit(status, holder[0] == null ? null : new AndroidEngine(holder[0]))));
        };
        this.focus = new Focus() {
            @Override public boolean request() {
                if (audioManager == null) return false;
                try {
                    return audioManager.requestAudioFocus(focusListener[0], AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                } catch (RuntimeException e) {
                    FileLog.e(e);
                    return false;
                }
            }

            @Override public void abandon() {
                if (audioManager == null) return;
                try {
                    audioManager.abandonAudioFocus(focusListener[0]);
                } catch (RuntimeException e) {
                    FileLog.e(e);
                }
            }
        };
        this.recordingGuard = recorderController::isRecording;
        this.toast = text -> CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show();
        this.clock = System::currentTimeMillis;
    }

    AutoSpeechController(EngineFactory engineFactory, Focus focus, RecordingGuard recordingGuard,
                         ToastSink toast, Clock clock) {
        this.engineFactory = engineFactory;
        this.focus = focus;
        this.recordingGuard = recordingGuard;
        this.toast = toast;
        this.clock = clock;
    }

    void addListener(Listener listener) { listeners.addIfAbsent(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }
    State getState() { return state; }
    String getActiveKey() { return activeKey; }
    boolean isActive(String key) {
        return key != null && key.equals(activeKey) && (state == State.PREPARING || state == State.SPEAKING);
    }
    long getSignature() { return state.ordinal() * 31L + (activeKey == null ? 0 : activeKey.hashCode()); }

    boolean prepare(String key) {
        if (!acceptClick()) return false;
        if (recordingGuard.isRecording()) {
            toast.show("Finish recording first");
            return false;
        }
        if (isActive(key)) {
            stop();
            return false;
        }
        stopInternal(false);
        activeKey = key;
        sequenceStep = false;
        update(State.PREPARING);
        return true;
    }

    void speak(String key, String text) {
        if (!prepare(key)) return;
        speakPrepared(key, text);
    }

    /**
     * Speaks one step of a longer sequence: no click debounce, no toggle, and the caller learns
     * whether the text was read to the end, failed, or was cut off by Stop, focus loss or another
     * read-aloud request.
     */
    void say(String key, List<AutoSpeechLanguage.Utterance> text, Completion onFinished) {
        if (destroyed || key == null) {
            report(onFinished, Outcome.CANCELLED);
            return;
        }
        stopInternal(false);
        activeKey = key;
        completion = onFinished;
        sequenceStep = true;
        update(State.PREPARING);
        speakPrepared(key, text);
    }

    void speakPrepared(String key, String text) {
        ArrayList<AutoSpeechLanguage.Utterance> single = new ArrayList<>();
        single.add(new AutoSpeechLanguage.Utterance(text, Locale.getDefault()));
        speakPrepared(key, single);
    }

    void speakPrepared(String key, List<AutoSpeechLanguage.Utterance> text) {
        if (destroyed || key == null || !key.equals(activeKey) || state != State.PREPARING) return;
        if (!sequenceStep && !holdsFocus) {
            if (!focus.request()) {
                fail("Can't read aloud now");
                return;
            }
            holdsFocus = true;
        }
        if (engine != null) {
            speakNow(text);
            return;
        }
        // Whatever the latest request is, it is spoken once the single start-up finishes; a second
        // engine would never be stopped or shut down.
        pendingKey = key;
        pendingText = text;
        if (initializing) return;
        if (initAttempts >= 2) {
            fail("Can't read aloud");
            return;
        }
        initAttempts++;
        initializing = true;
        engineFactory.create((status, initializedEngine) -> AndroidUtilities.runOnUIThread(() -> {
            initializing = false;
            String waitingKey = pendingKey;
            List<AutoSpeechLanguage.Utterance> waitingText = pendingText;
            pendingKey = null;
            pendingText = null;
            boolean wanted = !destroyed && waitingKey != null && waitingKey.equals(activeKey) && state == State.PREPARING;
            if (status != TextToSpeech.SUCCESS || initializedEngine == null) {
                if (initializedEngine != null) initializedEngine.shutdown();
                if (wanted) fail("Can't read aloud");
                return;
            }
            if (destroyed) {
                initializedEngine.shutdown();
                return;
            }
            // A working engine is kept even when the request was cancelled meanwhile, so Stop or
            // Back during start-up does not use up the init attempts for the session.
            engine = initializedEngine;
            initAttempts = 0;
            engineLocale = null;
            engine.setProgress(new Progress() {
                @Override public void onDone(String utteranceId) { postFinished(utteranceId, false); }
                @Override public void onError(String utteranceId) { postFinished(utteranceId, true); }
            });
            if (wanted) speakNow(waitingText);
        }));
    }

    void stop() { stopInternal(true); }

    void onAudioFocusLost() {
        AndroidUtilities.runOnUIThread(() -> {
            if (!destroyed && state != State.IDLE) stop();
        });
    }

    void destroy() {
        if (destroyed) return;
        stopInternal(false);
        destroyed = true;
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
        listeners.clear();
    }

    private boolean acceptClick() {
        if (destroyed) return false;
        long now = clock.now();
        if (lastClickAt != Long.MIN_VALUE && now - lastClickAt < 300) return false;
        lastClickAt = now;
        return true;
    }

    private void speakNow(List<AutoSpeechLanguage.Utterance> utterances) {
        if (engine == null) return;
        if (utterances.isEmpty()) {
            stopInternal(true);
            return;
        }
        engine.setMediaUsage(sequenceStep);
        String prefix = "auto_place_" + (++utteranceCounter) + "_";
        activeBatchPrefix = prefix;
        for (int i = 0; i < utterances.size(); i++) {
            AutoSpeechLanguage.Utterance utterance = utterances.get(i);
            applyLanguage(utterance.locale);
            String id = prefix + i;
            if (i == utterances.size() - 1) activeUtteranceId = id;
            if (engine.speak(utterance.text, id, i == 0) == TextToSpeech.ERROR) {
                fail("Can't read aloud");
                return;
            }
        }
        update(State.SPEAKING);
    }

    /** A voice that is not installed falls back to English rather than failing the summary. */
    private void applyLanguage(Locale locale) {
        if (locale.equals(engineLocale)) return;
        int result = engine.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.ENGLISH);
        }
        engineLocale = locale;
    }

    private void postFinished(String utteranceId, boolean error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || activeBatchPrefix == null || utteranceId == null
                    || !utteranceId.startsWith(activeBatchPrefix)) return;
            if (error) fail("Can't read aloud");
            else if (utteranceId.equals(activeUtteranceId)) {
                Completion done = completion;
                completion = null;
                stopInternal(true);
                report(done, Outcome.DONE);
            }
        });
    }

    private void fail(String text) {
        Completion failed = completion;
        completion = null;
        if (engine != null) engine.stop();
        activeUtteranceId = null;
        activeBatchPrefix = null;
        releaseFocus();
        update(State.ERROR);
        toast.show(text);
        report(failed, Outcome.FAILED);
    }

    private void stopInternal(boolean notify) {
        Completion cancelled = completion;
        completion = null;
        if (engine != null) engine.stop();
        releaseFocus();
        activeUtteranceId = null;
        activeBatchPrefix = null;
        activeKey = null;
        if (notify || state != State.IDLE) update(State.IDLE);
        report(cancelled, Outcome.CANCELLED);
    }

    private void releaseFocus() {
        if (!holdsFocus) return;
        holdsFocus = false;
        focus.abandon();
    }

    private static void report(Completion target, Outcome outcome) {
        if (target != null) AndroidUtilities.runOnUIThread(() -> target.onFinished(outcome));
    }

    private void update(State next) {
        state = next;
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).onStateChanged();
    }

    private static final class AndroidEngine implements Engine {
        private final TextToSpeech tts;

        private Boolean media;

        AndroidEngine(TextToSpeech tts) {
            this.tts = tts;
            setMediaUsage(false);
        }

        @Override public void setMediaUsage(boolean useMedia) {
            if (media != null && media == useMedia) return;
            media = useMedia;
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(useMedia ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        }

        @Override public int setLanguage(Locale locale) { return tts.setLanguage(locale); }
        @Override public void setProgress(Progress progress) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) { progress.onDone(utteranceId); }
                @Override public void onError(String utteranceId) { progress.onError(utteranceId); }
            });
        }
        @Override public int speak(String text, String utteranceId, boolean flush) {
            return tts.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, utteranceId);
        }
        @Override public void stop() { tts.stop(); }
        @Override public void shutdown() { tts.shutdown(); }
    }
}
