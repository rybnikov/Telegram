package org.telegram.messenger.auto;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;

/** Message text as the car may show or speak it: no links and nothing hidden behind a spoiler. */
final class AutoPlaceText {
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:\\b[a-z][a-z0-9+.-]*://|\\bwww\\.|\\bgeo:)\\S*|\\b[\\w-]+(?:\\.[\\w-]+)+/\\S*");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[\\s\\p{Punct}–—·•]+|[\\s\\p{Punct}–—·•]+$");
    private static final int MIN_LETTERS = 3;

    private AutoPlaceText() {
    }

    /** The caption without links and spoilers, or null when nothing worth saying remains. */
    @Nullable
    static String note(@Nullable TLRPC.Message message) {
        if (message == null || TextUtils.isEmpty(message.message)) return null;
        return clean(message.message, message.entities);
    }

    @Nullable
    static String clean(@Nullable String text, @Nullable ArrayList<TLRPC.MessageEntity> entities) {
        if (TextUtils.isEmpty(text)) return null;
        StringBuilder result = new StringBuilder(text);
        ArrayList<int[]> removed = new ArrayList<>();
        if (entities != null) {
            for (TLRPC.MessageEntity entity : entities) {
                // A text link keeps its visible words; only its target is dropped.
                if (!(entity instanceof TLRPC.TL_messageEntitySpoiler) && !(entity instanceof TLRPC.TL_messageEntityUrl)) continue;
                int start = Math.max(0, Math.min(text.length(), entity.offset));
                int end = Math.max(start, Math.min(text.length(), entity.offset + entity.length));
                if (end > start) removed.add(new int[]{start, end});
            }
        }
        // Entity offsets are UTF-16 indices into the original text. Nested or overlapping ranges
        // (a link inside a spoiler) are merged first, then cut from the end.
        Collections.sort(removed, (a, b) -> Integer.compare(a[0], b[0]));
        ArrayList<int[]> merged = new ArrayList<>();
        for (int[] range : removed) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && range[0] <= last[1]) last[1] = Math.max(last[1], range[1]);
            else merged.add(new int[]{range[0], range[1]});
        }
        for (int i = merged.size() - 1; i >= 0; i--) {
            result.replace(merged.get(i)[0], merged.get(i)[1], " ");
        }
        String value = URL.matcher(result).replaceAll(" ");
        value = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replaceAll("\\s+", " ");
        value = EDGE_PUNCTUATION.matcher(value).replaceAll("");
        return countLetters(value) >= MIN_LETTERS ? value : null;
    }

    private static int countLetters(String value) {
        int letters = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) letters++;
        }
        return letters;
    }
}
