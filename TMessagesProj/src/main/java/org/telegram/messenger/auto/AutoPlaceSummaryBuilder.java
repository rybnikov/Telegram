package org.telegram.messenger.auto;

import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.places.PlaceDetails;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class AutoPlaceSummaryBuilder {
    private AutoPlaceSummaryBuilder() {
    }

    private static final int NOTE_LIMIT = 120;

    /**
     * One spoken fragment. Fixed wording is English; {@code content} marks names, addresses and the
     * note, which are spoken in their own language (see {@link AutoSpeechLanguage}).
     */
    static final class Part {
        final String text;
        final boolean content;

        Part(String text, boolean content) {
            this.text = text;
            this.content = content;
        }
    }

    static String build(AutoPlaceItem item, PlaceDetails details, long nowSeconds, int maxLength) {
        return cap(toText(sentences(item, details, nowSeconds)), Math.max(1, maxLength));
    }

    /** Sentences that fit in {@code maxLength} characters when joined, for the speech engine. */
    static List<List<Part>> buildSentences(AutoPlaceItem item, PlaceDetails details, long nowSeconds, int maxLength) {
        List<List<Part>> all = sentences(item, details, nowSeconds);
        List<List<Part>> kept = new ArrayList<>();
        for (List<Part> sentence : all) {
            kept.add(sentence);
            if (toText(kept).length() >= Math.max(1, maxLength)) {
                kept.remove(kept.size() - 1);
                break;
            }
        }
        return kept;
    }

    /**
     * What the place is, then who sent it. Links, coordinates and the raw message are never read;
     * a cleaned caption is only a trailing note.
     */
    private static List<List<Part>> sentences(AutoPlaceItem item, PlaceDetails details, long nowSeconds) {
        List<List<Part>> sentences = new ArrayList<>();
        String hours = details == null ? null : AutoOpeningHours.speak(details.openingHours);
        boolean noCoreDetails = details == null || TextUtils.isEmpty(details.title)
                && TextUtils.isEmpty(details.category) && TextUtils.isEmpty(details.address)
                && TextUtils.isEmpty(hours) && TextUtils.isEmpty(details.description);
        String spokenName;
        if (noCoreDetails) {
            // item.title may be the caption itself; the caption is read once, as the note.
            if (!TextUtils.isEmpty(item.placeName)) {
                spokenName = item.placeName;
                sentences.add(parts(content(spokenName), fixed(", no more details")));
            } else {
                spokenName = item.isLive ? "Live location" : "Shared location";
                sentences.add(parts(fixed(spokenName + ", no more details")));
            }
        } else {
            spokenName = details.title;
            if (!TextUtils.isEmpty(details.title)) sentences.add(parts(content(details.title)));
            if (!TextUtils.isEmpty(details.category)) {
                sentences.add(parts(fixed(details.category + (details.stars >= 0 ? ", " + details.stars + " stars" : ""))));
            }
            if (!TextUtils.isEmpty(details.address)) sentences.add(parts(content(details.address)));
            if (!TextUtils.isEmpty(details.description)) sentences.add(parts(content(details.description)));
            if (!TextUtils.isEmpty(hours)) sentences.add(parts(fixed("Opening hours: " + hours)));
        }
        List<Part> sender = new ArrayList<>();
        sender.add(fixed("Sent by"));
        if (!TextUtils.isEmpty(item.senderName)) {
            sender.add(content(item.senderName));
        } else if (!TextUtils.isEmpty(item.chatTitle)) {
            sender.add(content(item.chatTitle));
        } else {
            sender.add(fixed("Unknown"));
        }
        String senderName = sender.get(1).text;
        if (item.isGroup && !TextUtils.isEmpty(item.chatTitle) && !item.chatTitle.equals(senderName)) {
            sender.add(fixed("in"));
            sender.add(content(item.chatTitle));
        }
        String age = relativeTime(item.date, nowSeconds);
        // Older dates come from the app's localized formatter.
        sender.add(new Part(age, !age.endsWith("ago") && !age.equals("just now") && !age.equals("yesterday")));
        sentences.add(sender);
        if (!TextUtils.isEmpty(item.messageText) && !item.messageText.equalsIgnoreCase(spokenName)) {
            sentences.add(parts(fixed("Note:"), content(AutoPlaceItem.compact(item.messageText, NOTE_LIMIT))));
        }
        return sentences;
    }

    private static Part fixed(String text) {
        return new Part(text, false);
    }

    private static Part content(String text) {
        return new Part(text, true);
    }

    private static List<Part> parts(Part... parts) {
        List<Part> result = new ArrayList<>();
        for (Part part : parts) result.add(part);
        return result;
    }

    static String sentenceText(List<Part> sentence) {
        StringBuilder text = new StringBuilder();
        for (Part part : sentence) {
            String value = part.text.trim();
            if (value.isEmpty()) continue;
            if (text.length() > 0 && !value.startsWith(",")) text.append(' ');
            text.append(value);
        }
        return text.toString();
    }

    private static String toText(List<List<Part>> sentences) {
        List<String> texts = new ArrayList<>();
        for (List<Part> sentence : sentences) texts.add(sentenceText(sentence));
        return join(texts);
    }

    static String relativeTime(long dateSeconds, long nowSeconds) {
        long delta = Math.max(0, nowSeconds - dateSeconds);
        if (delta < 60) return "just now";
        if (delta < 3600) return delta / 60 + " minutes ago";
        if (delta < 86400) return delta / 3600 + " hours ago";
        if (delta < 172800) return "yesterday";
        if (delta < 7 * 86400) return delta / 86400 + " days ago";
        return LocaleController.getInstance().getFormatterDayMonth().format(new Date(dateSeconds * 1000));
    }

    private static String join(List<String> parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (TextUtils.isEmpty(part)) continue;
            if (result.length() > 0) result.append(". ");
            result.append(part.trim());
        }
        return result.toString();
    }

    private static String cap(String text, int maxLength) {
        if (text.length() < maxLength) return text;
        int boundary = text.lastIndexOf(". ", maxLength - 1);
        if (boundary > 0) return text.substring(0, boundary);
        int end = Math.max(0, maxLength - 1);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).trim();
    }
}
