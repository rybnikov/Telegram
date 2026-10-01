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
        final ArrayList<String> spoken = new ArrayList<>();
        final ArrayList<Locale> languages = new ArrayList<>();
        Locale language;
        java.util.Set<String> missing = new java.util.HashSet<>();

        @Override public int setLanguage(Locale locale) {
            if (missing.contains(locale.getLanguage())) return TextToSpeech.LANG_MISSING_DATA;
            language = locale;
            languages.add(locale);
            return TextToSpeech.LANG_AVAILABLE;
        }
        @Override public void setProgress(AutoSpeechController.Progress progress) { this.progress = progress; }
        @Override public int speak(String text, String utteranceId, boolean flush) {
            if (flush) spoken.clear();
            spoken.add(language.getLanguage() + ":" + text);
            this.utteranceId = utteranceId;
            return speakResult;
        }
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

    @Test public void failedEngineIsShutDown() {
        FakeEngine failed = new FakeEngine();
        ArrayList<String> toasts = new ArrayList<>();
        AutoSpeechController controller = new AutoSpeechController(
                callback -> callback.onInit(TextToSpeech.ERROR, failed),
                new AutoSpeechController.Focus() {
                    @Override public boolean request() { return true; }
                    @Override public void abandon() { }
                }, () -> false, toasts::add, () -> 1000L);
        controller.speak("a", "Alpha");
        ShadowLooper.idleMainLooper();
        assertEquals(1, failed.shutdownCount);
        assertEquals(AutoSpeechController.State.ERROR, controller.getState());
    }

    @Test public void fragmentsUseTheirOwnVoiceAndFinishOnTheLastOne() {
        Harness h = new Harness();
        h.engine.missing.add("ka");
        java.util.List<AutoSpeechLanguage.Utterance> parts = new ArrayList<>();
        parts.add(new AutoSpeechLanguage.Utterance("Sent by", Locale.ENGLISH));
        parts.add(new AutoSpeechLanguage.Utterance("Мама.", new Locale("ru")));
        parts.add(new AutoSpeechLanguage.Utterance("გამარჯობა.", new Locale("ka")));
        assertTrue(h.controller.prepare("a"));
        h.controller.speakPrepared("a", parts);
        ShadowLooper.runUiThreadTasks();
        assertEquals(java.util.Arrays.asList("en:Sent by", "ru:Мама.", "en:გამარჯობა."), h.engine.spoken);
        String last = h.engine.utteranceId;
        h.engine.progress.onDone(last.substring(0, last.length() - 1) + "0");
        ShadowLooper.runUiThreadTasks();
        assertEquals(AutoSpeechController.State.SPEAKING, h.controller.getState());
        h.engine.progress.onDone(last);
        ShadowLooper.runUiThreadTasks();
        assertEquals(AutoSpeechController.State.IDLE, h.controller.getState());
    }

    @Test public void initFailureWithoutAnEngineFailsCleanly() {
        ArrayList<String> toasts = new ArrayList<>();
        AutoSpeechController controller = new AutoSpeechController(
                callback -> callback.onInit(TextToSpeech.ERROR, null),
                new AutoSpeechController.Focus() {
                    @Override public boolean request() { return true; }
                    @Override public void abandon() { }
                }, () -> false, toasts::add, () -> 1000L);
        controller.speak("a", "Alpha");
        ShadowLooper.idleMainLooper();
        assertEquals(AutoSpeechController.State.ERROR, controller.getState());
        assertEquals("Can't read aloud", toasts.get(0));
    }

    @Test public void cancellingDuringStartUpKeepsTheWorkingEngine() {
        FakeEngine engine = new FakeEngine();
        int[] created = new int[1];
        AutoSpeechController.InitCallback[] pending = new AutoSpeechController.InitCallback[1];
        long[] now = {1000};
        AutoSpeechController controller = new AutoSpeechController(
                callback -> { created[0]++; pending[0] = callback; },
                new AutoSpeechController.Focus() {
                    @Override public boolean request() { return true; }
                    @Override public void abandon() { }
                }, () -> false, text -> { }, () -> now[0]);
        for (int round = 0; round < 3; round++) {
            now[0] += 1000;
            controller.speak("a", "Alpha");
            now[0] += 1000;
            controller.stop();
            if (pending[0] != null) {
                pending[0].onInit(TextToSpeech.SUCCESS, engine);
                pending[0] = null;
                ShadowLooper.idleMainLooper();
            }
        }
        assertEquals("One working engine serves the whole session", 1, created[0]);
        assertEquals(0, engine.shutdownCount);
        now[0] += 1000;
        controller.speak("a", "Alpha");
        ShadowLooper.idleMainLooper();
        assertEquals(AutoSpeechController.State.SPEAKING, controller.getState());
    }

    @Test public void repeatedRequestsDuringStartUpShareOneEngine() {
        FakeEngine engine = new FakeEngine();
        int[] created = new int[1];
        AutoSpeechController.InitCallback[] pending = new AutoSpeechController.InitCallback[1];
        long[] now = {1000};
        AutoSpeechController controller = new AutoSpeechController(
                callback -> { created[0]++; pending[0] = callback; },
                new AutoSpeechController.Focus() {
                    @Override public boolean request() { return true; }
                    @Override public void abandon() { }
                }, () -> false, text -> { }, () -> now[0]);
        controller.speak("a", "Alpha");
        now[0] += 1000;
        controller.stop();
        now[0] += 1000;
        controller.speak("b", "Beta");
        assertEquals("The second request waits for the running start-up", 1, created[0]);
        pending[0].onInit(TextToSpeech.SUCCESS, engine);
        ShadowLooper.idleMainLooper();
        assertEquals(AutoSpeechController.State.SPEAKING, controller.getState());
        assertEquals("b", controller.getActiveKey());
        assertTrue(engine.spoken.toString(), engine.spoken.get(0).endsWith("Beta"));
        controller.destroy();
        assertEquals(1, engine.shutdownCount);
    }
}
