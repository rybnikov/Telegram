package org.telegram.messenger.auto;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.places.PlaceDetails;

import java.util.ArrayList;
import java.util.List;

/** What the place card shows: pure, so row order and trimming are testable without a car host. */
final class AutoPlaceCard {
    enum Kind { TYPE, ADDRESS, HOURS, DESCRIPTION, SENDER }

    static final class Line {
        final Kind kind;
        final String title;
        @Nullable final String text;

        Line(Kind kind, String title, @Nullable String text) {
            this.kind = kind;
            this.title = title;
            this.text = text;
        }
    }

    // Trimmed first when the host allows fewer rows; the sender row always stays.
    private static final Kind[] DROP_ORDER = {Kind.DESCRIPTION, Kind.HOURS, Kind.TYPE, Kind.ADDRESS};

    private AutoPlaceCard() {
    }

    /**
     * The row's title, fixed for the card's lifetime: a template title that changes after loading
     * can count as a new step against the host's task budget instead of a refresh.
     */
    @NonNull
    static String title(@NonNull AutoPlaceItem item) {
        return item.title;
    }

    /** dayIndex: 0 = Monday. */
    static List<Line> lines(@NonNull AutoPlaceItem item, @Nullable PlaceDetails details,
                            long nowSeconds, int dayIndex, int rowLimit) {
        ArrayList<Line> lines = new ArrayList<>();
        PlaceDetails d = details != null ? details : new PlaceDetails();
        // A looked-up name that differs from the fixed title leads the first row instead.
        String name = !TextUtils.isEmpty(d.title) && !d.title.equalsIgnoreCase(item.title)
                ? AutoPlaceItem.compact(d.title, 80) : null;
        String type = join(" · ", name, capitalize(d.category), d.stars >= 0 ? "★ " + d.stars + "/5" : null);
        if (type != null) lines.add(new Line(Kind.TYPE, type, null));
        if (!TextUtils.isEmpty(d.address)) lines.add(new Line(Kind.ADDRESS, AutoPlaceItem.compact(d.address, 120), null));
        String hours = AutoOpeningHours.today(d.openingHours, dayIndex);
        if (hours != null) lines.add(new Line(Kind.HOURS, hours, null));
        if (!TextUtils.isEmpty(d.description)) {
            lines.add(new Line(Kind.DESCRIPTION, AutoPlaceItem.compact(d.description, 240), null));
        }
        lines.add(senderLine(item, nowSeconds));
        int limit = Math.max(1, rowLimit);
        for (Kind kind : DROP_ORDER) {
            if (lines.size() <= limit) break;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).kind == kind) {
                    lines.remove(i);
                    break;
                }
            }
        }
        return lines;
    }

    private static Line senderLine(AutoPlaceItem item, long nowSeconds) {
        String sender = !TextUtils.isEmpty(item.senderName) ? item.senderName
                : !TextUtils.isEmpty(item.chatTitle) ? item.chatTitle : "Unknown";
        String title = "Sent by " + sender;
        if (item.isGroup && !TextUtils.isEmpty(item.chatTitle) && !item.chatTitle.equals(sender)) {
            title += " · " + item.chatTitle;
        }
        String text = AutoPlaceSummaryBuilder.relativeTime(item.date, nowSeconds);
        if (!TextUtils.isEmpty(item.messageText)) text += " · " + AutoPlaceItem.compact(item.messageText, 120);
        return new Line(Kind.SENDER, title, text);
    }

    @Nullable
    private static String capitalize(@Nullable String value) {
        if (TextUtils.isEmpty(value)) return null;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    @Nullable
    private static String join(String separator, String... parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (TextUtils.isEmpty(part)) continue;
            if (result.length() > 0) result.append(separator);
            result.append(part);
        }
        return result.length() > 0 ? result.toString() : null;
    }
}
