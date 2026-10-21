package org.telegram.messenger.auto;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Debug-menu switches for trying how the Android Auto host handles attached voice and round
 * messages without rebuilding. Read on every list build, so a change shows up on the next
 * refresh of the car screen.
 */
public final class AutoExperiments {

    public static final String[] ATTACH_MODES = {"off", "all", "newest per chat", "newest per sender run"};
    public static final int ATTACH_OFF = 0;
    public static final int ATTACH_ALL = 1;
    public static final int ATTACH_NEWEST_PER_CHAT = 2;
    public static final int ATTACH_NEWEST_PER_SENDER_RUN = 3;
    public static final String[] VOICE_MIMES = {"audio/ogg", "audio/opus", "audio/mpeg"};
    public static final String[] ROUND_MIMES = {"audio/mp4", "video/mp4", "audio/aac"};
    public static final String[] MEDIA_BODIES = {"label", "empty"};
    public static final String[] CARD_BUTTONS = {"listen", "voice"};
    public static final String[] MESSAGE_LIMITS = {"all", "5"};
    public static final String[] LAYOUTS = {"conversation", "item per message"};

    private static final String PREFS = "auto_experiments";

    private AutoExperiments() {
    }

    public static int attachMode() { return get("attach", ATTACH_ALL, ATTACH_MODES.length); }
    public static String voiceMime() { return VOICE_MIMES[get("voiceMime", 0, VOICE_MIMES.length)]; }
    public static String roundMime() { return ROUND_MIMES[get("roundMime", 0, ROUND_MIMES.length)]; }
    public static boolean emptyMediaBody() { return get("mediaBody", 0, MEDIA_BODIES.length) == 1; }
    public static boolean downloadMissing() { return get("download", 1, 2) == 1; }
    public static boolean voiceButton() { return get("cardButton", 0, CARD_BUTTONS.length) == 1; }
    public static boolean fiveMessages() { return get("msgLimit", 1, MESSAGE_LIMITS.length) == 1; }
    public static boolean itemPerMessage() { return get("layout", 0, LAYOUTS.length) == 1; }

    /** Menu rows: label with the current value. */
    public static CharSequence[] menuItems() {
        return new CharSequence[]{
                "Attach media: " + ATTACH_MODES[attachMode()],
                "Voice mime: " + voiceMime(),
                "Round mime: " + roundMime(),
                "Media body: " + MEDIA_BODIES[get("mediaBody", 0, MEDIA_BODIES.length)],
                "Download missing media: " + (downloadMissing() ? "on" : "off"),
                "Card button: " + CARD_BUTTONS[get("cardButton", 0, CARD_BUTTONS.length)],
                "Messages per chat: " + MESSAGE_LIMITS[get("msgLimit", 1, MESSAGE_LIMITS.length)],
                "Layout: " + LAYOUTS[get("layout", 0, LAYOUTS.length)],
        };
    }

    /** Advances the setting behind menu row {@code index} to its next value. */
    public static void cycle(int index) {
        switch (index) {
            case 0: next("attach", ATTACH_ALL, ATTACH_MODES.length); break;
            case 1: next("voiceMime", 0, VOICE_MIMES.length); break;
            case 2: next("roundMime", 0, ROUND_MIMES.length); break;
            case 3: next("mediaBody", 0, MEDIA_BODIES.length); break;
            case 4: next("download", 1, 2); break;
            case 5: next("cardButton", 0, CARD_BUTTONS.length); break;
            case 6: next("msgLimit", 1, MESSAGE_LIMITS.length); break;
            case 7: next("layout", 0, LAYOUTS.length); break;
            default: break;
        }
    }

    private static int get(String key, int fallback, int size) {
        int value = prefs().getInt(key, fallback);
        return value >= 0 && value < size ? value : fallback;
    }

    private static void next(String key, int fallback, int size) {
        prefs().edit().putInt(key, (get(key, fallback, size) + 1) % size).apply();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
