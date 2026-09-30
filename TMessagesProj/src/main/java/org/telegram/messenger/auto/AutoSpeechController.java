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
        int speak(String text, String utteranceId);
        void stop();
        void shutdown();
    }
    interface Progress { void onDone(String utteranceId); void onError(String utteranceId); }
    interface Focus { boolean request(); void abandon(); }
    interface RecordingGuard { boolean isRecording(); }
    interface ToastSink { void show(String text); }
    interface Clock { long now(); }

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
    private long lastClickAt = Long.MIN_VALUE;
    private int utteranceCounter;
    private int initAttempts;
    private boolean destroyed;

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
            holder[0] = new TextToSpeech(context, status -> callback.onInit(status,
                    status == TextToSpeech.SUCCESS ? new AndroidEngine(holder[0]) : null));
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
        update(State.PREPARING);
        return true;
    }

    void speak(String key, String text) {
        if (!prepare(key)) return;
        speakPrepared(key, text);
    }

    void speakPrepared(String key, String text) {
        if (destroyed || key == null || !key.equals(activeKey) || state != State.PREPARING) return;
        if (!focus.request()) {
            fail("Can't read aloud now");
            return;
        }
        if (engine != null) {
            speakNow(text);
            return;
        }
        if (initAttempts >= 2) {
            fail("Can't read aloud");
            return;
        }
        initAttempts++;
        engineFactory.create((status, initializedEngine) -> AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || key == null || !key.equals(activeKey) || state != State.PREPARING) {
                if (initializedEngine != null) initializedEngine.shutdown();
                return;
            }
            if (status != TextToSpeech.SUCCESS || initializedEngine == null) {
                fail("Can't read aloud");
                return;
            }
            engine = initializedEngine;
            int language = engine.setLanguage(Locale.getDefault());
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.ENGLISH);
            }
            engine.setProgress(new Progress() {
                @Override public void onDone(String utteranceId) { postFinished(utteranceId, false); }
                @Override public void onError(String utteranceId) { postFinished(utteranceId, true); }
            });
            speakNow(text);
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

    private void speakNow(String text) {
        if (engine == null) return;
        activeUtteranceId = "auto_place_" + (++utteranceCounter);
        int result = engine.speak(text, activeUtteranceId);
        if (result == TextToSpeech.ERROR) {
            fail("Can't read aloud");
        } else {
            update(State.SPEAKING);
        }
    }

    private void postFinished(String utteranceId, boolean error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || activeUtteranceId == null || !activeUtteranceId.equals(utteranceId)) return;
            if (error) fail("Can't read aloud");
            else stopInternal(true);
        });
    }

    private void fail(String text) {
        activeUtteranceId = null;
        focus.abandon();
        update(State.ERROR);
        toast.show(text);
    }

    private void stopInternal(boolean notify) {
        if (engine != null) engine.stop();
        focus.abandon();
        activeUtteranceId = null;
        activeKey = null;
        if (notify || state != State.IDLE) update(State.IDLE);
    }

    private void update(State next) {
        state = next;
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).onStateChanged();
    }

    private static final class AndroidEngine implements Engine {
        private final TextToSpeech tts;

        AndroidEngine(TextToSpeech tts) {
            this.tts = tts;
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
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
        @Override public int speak(String text, String utteranceId) {
            return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        }
        @Override public void stop() { tts.stop(); }
        @Override public void shutdown() { tts.shutdown(); }
    }
}
