package org.telegram.messenger.auto;

import android.speech.tts.TextToSpeech;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import org.junit.Before;
import org.telegram.messenger.ApplicationLoader;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Locale;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AutoSpeechControllerTest {
    @Before public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationLoaderInstance = new ApplicationLoader();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }
    private static final class FakeEngine implements AutoSpeechController.Engine {
        AutoSpeechController.Progress progress;
        int speakResult = TextToSpeech.SUCCESS;
        int stopCount;
        int shutdownCount;
        String utteranceId;

        @Override public int setLanguage(Locale locale) { return TextToSpeech.LANG_AVAILABLE; }
        @Override public void setProgress(AutoSpeechController.Progress progress) { this.progress = progress; }
        @Override public int speak(String text, String utteranceId) { this.utteranceId = utteranceId; return speakResult; }
        @Override public void stop() { stopCount++; }
        @Override public void shutdown() { shutdownCount++; }
    }

    private static final class Harness {
        final FakeEngine engine = new FakeEngine();
        final ArrayList<String> toasts = new ArrayList<>();
        long now = 1000;
        boolean focus = true;
        boolean recording;
        final AutoSpeechController controller = new AutoSpeechController(
                callback -> callback.onInit(TextToSpeech.SUCCESS, engine),
                new AutoSpeechController.Focus() {
                    @Override public boolean request() { return focus; }
                    @Override public void abandon() { }
                }, () -> recording, toasts::add, () -> now);
    }

    @Test public void speaksTogglesAfterDebounceAndIgnoresStaleCallback() {
        Harness h = new Harness();
        h.controller.speak("a", "Alpha");
        ShadowLooper.runUiThreadTasks();
        assertEquals(AutoSpeechController.State.SPEAKING, h.controller.getState());
        assertEquals("a", h.controller.getActiveKey());
        String first = h.engine.utteranceId;
        h.controller.speak("a", "Alpha");
        assertEquals(AutoSpeechController.State.SPEAKING, h.controller.getState());
        h.now += 301;
        h.controller.speak("a", "Alpha");
        assertEquals(AutoSpeechController.State.IDLE, h.controller.getState());
        h.now += 301;
        h.controller.speak("b", "Beta");
        ShadowLooper.runUiThreadTasks();
        h.engine.progress.onDone(first);
        ShadowLooper.runUiThreadTasks();
        assertEquals(AutoSpeechController.State.SPEAKING, h.controller.getState());
        h.engine.progress.onDone(h.engine.utteranceId);
        ShadowLooper.runUiThreadTasks();
        assertEquals(AutoSpeechController.State.IDLE, h.controller.getState());
    }

    @Test public void focusAndRecordingFailuresDoNotSpeak() {
        Harness h = new Harness();
        h.recording = true;
        h.controller.speak("a", "Alpha");
        ShadowLooper.runUiThreadTasks();
        assertEquals("Finish recording first", h.toasts.get(0));
        h.recording = false;
        h.now += 301;
        h.focus = false;
        h.controller.speak("a", "Alpha");
        ShadowLooper.runUiThreadTasks();
        assertEquals(AutoSpeechController.State.ERROR, h.controller.getState());
        assertEquals("Can't read aloud now", h.toasts.get(1));
    }

    @Test public void focusLossAndDestroyStopSafely() {
        Harness h = new Harness();
        h.controller.speak("a", "Alpha");
        ShadowLooper.runUiThreadTasks();
        h.controller.onAudioFocusLost();
        ShadowLooper.runUiThreadTasks();
        assertEquals(AutoSpeechController.State.IDLE, h.controller.getState());
        h.controller.destroy();
        assertEquals(1, h.engine.shutdownCount);
    }
}
