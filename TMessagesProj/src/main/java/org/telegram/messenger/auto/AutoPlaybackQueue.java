package org.telegram.messenger.auto;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Turns a chat into an oldest-first listening queue: its unread incoming messages, or the last few
 * messages when nothing is unread. Text is read aloud, voice and video messages are played,
 * ordinary videos are announced and their sound is played. Pure logic so the order and wording
 * rules stay testable without a player.
 */
final class AutoPlaybackQueue {
    static final int MAX_ITEMS = 20;
    static final int RECENT_ITEMS = 5;
    static final int MAX_VIDEO_AUDIO_SECONDS = 5 * 60;

    enum Media { NONE, VOICE, ROUND, VIDEO, GIF, OTHER }

    interface Labels {
        @NonNull String video(@NonNull String duration);
        @NonNull String videoNoSound();
        @NonNull String longVideoSkipped(int minutes);
        @NonNull String viewOnce(@NonNull String mediaLabel);
        @NonNull String voiceLabel();
        @NonNull String roundLabel();
    }

    /** One message as seen by the queue; {@code payload} is handed back untouched for playback. */
    static final class Entry {
        final int id;
        final int date;
        final boolean outgoing;
        final boolean unread;
        final long senderId;
        final String senderName;
        final String text;
        final Media media;
        final String mediaLabel;
        final int durationSeconds;
        final boolean noSound;
        final boolean viewOnce;
        final Object payload;

        Entry(int id, int date, boolean outgoing, boolean unread, long senderId, @Nullable String senderName,
              @Nullable String text, @NonNull Media media, @Nullable String mediaLabel, int durationSeconds,
              boolean noSound, boolean viewOnce, @Nullable Object payload) {
            this.id = id;
            this.date = date;
            this.outgoing = outgoing;
            this.unread = unread;
            this.senderId = senderId;
            this.senderName = senderName;
            this.text = text == null ? "" : text.trim();
            this.media = media;
            this.mediaLabel = mediaLabel;
            this.durationSeconds = Math.max(0, durationSeconds);
            this.noSound = noSound;
            this.viewOnce = viewOnce;
            this.payload = payload;
        }
    }

    /** A spoken fragment; content comes from the message and is voiced in its own language. */
    static final class Part {
        final String text;
        final boolean content;

        Part(String text, boolean content) {
            this.text = text;
            this.content = content;
        }
    }

    static final class Result {
        final List<Item> items;
        /** True when the queue is the chat's unread messages; only then is the chat marked read. */
        final boolean unread;

        Result(List<Item> items, boolean unread) {
            this.items = items;
            this.unread = unread;
        }
    }

    /** Speak {@code spoken} (may be empty), then play {@code payload} when {@code play} is set. */
    static final class Item {
        final List<Part> spoken;
        final boolean play;
        final boolean markContentRead;
        final Object payload;
        final int messageId;

        Item(List<Part> spoken, boolean play, boolean markContentRead, Object payload, int messageId) {
            this.spoken = spoken;
            this.play = play;
            this.markContentRead = markContentRead;
            this.payload = payload;
            this.messageId = messageId;
        }
    }

    private AutoPlaybackQueue() {
    }

    /**
     * Unread incoming messages when there are any (sender named in groups only); otherwise the
     * last {@link #RECENT_ITEMS} messages in either direction, each sender named when it changes.
     */
    @NonNull
    static Result build(@NonNull List<Entry> source, boolean groupChat, @NonNull Labels labels) {
        ArrayList<Entry> all = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) != null) all.add(source.get(i));
        }
        Collections.sort(all, (a, b) -> a.date != b.date ? Integer.compare(a.date, b.date) : Integer.compare(a.id, b.id));
        // The same message can arrive twice (preview cache plus dialog cache).
        for (int i = all.size() - 1; i > 0; i--) {
            if (all.get(i).id == all.get(i - 1).id) {
                all.remove(i);
            }
        }
        ArrayList<Entry> unread = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Entry entry = all.get(i);
            if (entry.unread && !entry.outgoing) unread.add(entry);
        }
        boolean unreadMode = !unread.isEmpty();
        List<Entry> chosen = unreadMode ? newest(unread, MAX_ITEMS) : newest(all, RECENT_ITEMS);
        boolean nameEverySender = !unreadMode || groupChat;

        ArrayList<Item> items = new ArrayList<>(chosen.size());
        long previousSender = 0;
        boolean first = true;
        for (int i = 0; i < chosen.size(); i++) {
            Entry entry = chosen.get(i);
            ArrayList<Part> spoken = new ArrayList<>();
            if (nameEverySender && (first || entry.senderId != previousSender) && !TextUtils.isEmpty(entry.senderName)) {
                spoken.add(new Part(entry.senderName + ":", !entry.outgoing));
            }
            previousSender = entry.senderId;
            first = false;
            Item item = buildItem(entry, spoken, labels);
            if (item != null) {
                items.add(item);
            }
        }
        return new Result(items, unreadMode);
    }

    @NonNull
    private static List<Entry> newest(@NonNull List<Entry> sorted, int limit) {
        return sorted.size() > limit ? new ArrayList<>(sorted.subList(sorted.size() - limit, sorted.size())) : sorted;
    }

    @Nullable
    private static Item buildItem(@NonNull Entry entry, @NonNull ArrayList<Part> spoken, @NonNull Labels labels) {
        switch (entry.media) {
            case VOICE:
            case ROUND: {
                if (entry.viewOnce) {
                    String label = entry.media == Media.VOICE ? labels.voiceLabel() : labels.roundLabel();
                    spoken.add(new Part(labels.viewOnce(label), false));
                    return speakOnly(entry, spoken);
                }
                addContent(spoken, entry.text);
                // An outgoing voice message is "unread" until the other side listens; never touch it.
                return new Item(spoken, true, !entry.outgoing, entry.payload, entry.id);
            }
            case VIDEO: {
                if (entry.viewOnce) {
                    spoken.add(new Part(labels.viewOnce(nonEmpty(entry.mediaLabel, "Video")), false));
                    return speakOnly(entry, spoken);
                }
                if (entry.noSound) {
                    spoken.add(new Part(labels.videoNoSound(), false));
                    addContent(spoken, entry.text);
                    return speakOnly(entry, spoken);
                }
                if (entry.durationSeconds > MAX_VIDEO_AUDIO_SECONDS) {
                    spoken.add(new Part(labels.longVideoSkipped((entry.durationSeconds + 59) / 60), false));
                    addContent(spoken, entry.text);
                    return speakOnly(entry, spoken);
                }
                spoken.add(new Part(labels.video(formatDuration(entry.durationSeconds)), false));
                addContent(spoken, entry.text);
                return new Item(spoken, true, false, entry.payload, entry.id);
            }
            case GIF: {
                spoken.add(new Part(labels.videoNoSound(), false));
                addContent(spoken, entry.text);
                return speakOnly(entry, spoken);
            }
            case OTHER: {
                if (TextUtils.isEmpty(entry.mediaLabel) && TextUtils.isEmpty(entry.text)) {
                    return null;
                }
                if (!TextUtils.isEmpty(entry.mediaLabel)) {
                    spoken.add(new Part(entry.mediaLabel, false));
                }
                addContent(spoken, entry.text);
                return speakOnly(entry, spoken);
            }
            case NONE:
            default: {
                if (TextUtils.isEmpty(entry.text)) {
                    return null;
                }
                spoken.add(new Part(entry.text, true));
                return speakOnly(entry, spoken);
            }
        }
    }

    @NonNull
    private static Item speakOnly(@NonNull Entry entry, @NonNull List<Part> spoken) {
        return new Item(spoken, false, false, entry.payload, entry.id);
    }

    private static void addContent(@NonNull List<Part> spoken, @NonNull String text) {
        if (!TextUtils.isEmpty(text)) {
            spoken.add(new Part(text, true));
        }
    }

    @NonNull
    private static String nonEmpty(@Nullable String value, @NonNull String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    @NonNull
    static String formatDuration(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format(Locale.ROOT, "%d:%02d", safe / 60, safe % 60);
    }
}
