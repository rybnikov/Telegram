package org.telegram.messenger.auto;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LanguageDetector;

/** On-device ML Kit language id with a hard deadline, so About never waits on it. */
final class AutoLanguageDetection {
    interface Callback { void onLanguage(@Nullable String tag); }

    private AutoLanguageDetection() {
    }

    /** Exactly one UI-thread callback: the ML Kit tag, or null when empty, unsure or late. */
    static void detect(@Nullable String text, long timeoutMs, Callback callback) {
        if (TextUtils.isEmpty(text) || text.trim().length() < 2) {
            callback.onLanguage(null);
            return;
        }
        boolean[] done = new boolean[1];
        Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            callback.onLanguage(null);
        };
        AndroidUtilities.runOnUIThread(timeout, timeoutMs);
        LanguageDetector.detectLanguage(text,
                tag -> AndroidUtilities.runOnUIThread(() -> finish(done, timeout, callback, tag)),
                error -> AndroidUtilities.runOnUIThread(() -> finish(done, timeout, callback, null)));
    }

    private static void finish(boolean[] done, Runnable timeout, Callback callback, String tag) {
        if (done[0]) return;
        done[0] = true;
        AndroidUtilities.cancelRunOnUIThread(timeout);
        callback.onLanguage(tag);
    }
}
