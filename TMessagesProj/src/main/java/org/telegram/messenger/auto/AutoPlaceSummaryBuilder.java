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

    static String build(AutoPlaceItem item, PlaceDetails details, long nowSeconds, int maxLength) {
        List<String> sentences = new ArrayList<>();
        boolean noCoreDetails = details == null || TextUtils.isEmpty(details.title)
                && TextUtils.isEmpty(details.category) && TextUtils.isEmpty(details.address)
                && TextUtils.isEmpty(details.openingHours);
        if (noCoreDetails) {
            sentences.add("No details for this place");
        } else {
            if (!TextUtils.isEmpty(details.title)) sentences.add(details.title);
            if (!TextUtils.isEmpty(details.category)) {
                sentences.add(details.category + (details.stars >= 0 ? ", " + details.stars + " stars" : ""));
            }
            if (!TextUtils.isEmpty(details.address)) sentences.add(details.address);
            if (!TextUtils.isEmpty(details.openingHours)) {
                sentences.add("Opening hours: " + details.openingHours.replaceAll(";\\s*", ", "));
            }
        }
        String senderName = !TextUtils.isEmpty(item.senderName) ? item.senderName
                : !TextUtils.isEmpty(item.chatTitle) ? item.chatTitle : "Unknown";
        String sender = "Sent by " + senderName;
        if (item.isGroup && !TextUtils.isEmpty(item.chatTitle) && !item.chatTitle.equals(senderName)) {
            sender += " in " + item.chatTitle;
        }
        sender += " " + relativeTime(item.date, nowSeconds);
        sentences.add(sender);
        if (!TextUtils.isEmpty(item.messageText)) sentences.add("Message: " + item.messageText);
        return cap(join(sentences), Math.max(1, maxLength));
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
