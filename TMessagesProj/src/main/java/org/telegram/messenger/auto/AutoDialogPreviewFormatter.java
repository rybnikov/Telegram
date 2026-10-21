package org.telegram.messenger.auto;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.MessageObject;

final class AutoDialogPreviewFormatter {

    private static final int MAX_COMPACT_CHANNEL_PREVIEW = 120;

    @Nullable
    String format(@NonNull MessageObject messageObject) {
        String timed = formatTimedMedia(messageObject);
        if (timed != null) {
            return timed;
        }
        CharSequence text = messageObject.messageTextShort;
        if (TextUtils.isEmpty(text)) {
            text = messageObject.caption;
        }
        if (TextUtils.isEmpty(text)) {
            text = messageObject.messageText;
        }
        if (TextUtils.isEmpty(text) && messageObject.messageOwner != null) {
            text = messageObject.messageOwner.message;
        }
        if (!TextUtils.isEmpty(text)) {
            return text.toString();
        }
        if (!messageObject.isMediaEmpty()) {
            return "[media]";
        }
        return null;
    }

    /** Voice, round and video messages read as "<type>, m:ss" plus the caption, never "[media]". */
    @Nullable
    private static String formatTimedMedia(@NonNull MessageObject messageObject) {
        String fallback;
        if (messageObject.isVoice()) {
            fallback = "Voice message";
        } else if (messageObject.isRoundVideo()) {
            fallback = "Video message";
        } else if (messageObject.isVideo() && !messageObject.isGif()) {
            fallback = "Video";
        } else {
            return null;
        }
        String caption = messageObject.caption != null ? messageObject.caption.toString().trim() : "";
        CharSequence label = messageObject.messageText;
        String type = !TextUtils.isEmpty(label) && !TextUtils.equals(label, caption) ? label.toString().trim() : fallback;
        return timedLabel(type, (int) Math.round(messageObject.getDuration()), caption);
    }

    @NonNull
    static String timedLabel(@NonNull String type, int durationSeconds, @Nullable String caption) {
        StringBuilder builder = new StringBuilder(type);
        if (durationSeconds > 0) {
            builder.append(", ").append(AutoPlaybackQueue.formatDuration(durationSeconds));
        }
        if (!TextUtils.isEmpty(caption)) {
            builder.append(". ").append(caption);
        }
        return builder.toString();
    }

    @NonNull
    String formatCompactChannelPreview(@NonNull MessageObject messageObject) {
        return compact(format(messageObject));
    }

    @NonNull
    String compact(@Nullable String text) {
        if (TextUtils.isEmpty(text)) {
            return "No messages yet";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        if (normalized.length() <= MAX_COMPACT_CHANNEL_PREVIEW) {
            return normalized;
        }
        return normalized.substring(0, MAX_COMPACT_CHANNEL_PREVIEW - 3) + "...";
    }
}
