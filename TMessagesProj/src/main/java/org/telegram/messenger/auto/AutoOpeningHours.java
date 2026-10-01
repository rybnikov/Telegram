package org.telegram.messenger.auto;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Speaks the common subset of OpenStreetMap opening_hours ("Mo-Fr 09:00-18:00; Sa off").
 * Anything outside it (holidays, sunrise, months, comments) returns null so TTS never reads syntax.
 */
final class AutoOpeningHours {
    private static final String[] CODES = {"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};
    private static final String[] NAMES = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final Pattern DAYS = Pattern.compile("[A-Z][a-z](?:-[A-Z][a-z])?(?:,[A-Z][a-z](?:-[A-Z][a-z])?)*");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})");

    private AutoOpeningHours() {
    }

    @Nullable
    static String speak(@Nullable String value) {
        if (TextUtils.isEmpty(value)) return null;
        String trimmed = value.trim();
        if ("24/7".equals(trimmed)) return "open 24 hours, every day";
        StringBuilder result = new StringBuilder();
        for (String rawRule : trimmed.split(";")) {
            String rule = rawRule.trim();
            if (rule.isEmpty()) continue;
            String spoken = speakRule(rule);
            if (spoken == null) return null;
            if (result.length() > 0) result.append("; ");
            result.append(spoken);
        }
        return result.length() > 0 ? result.toString() : null;
    }

    /**
     * The card line for one day (0 = Monday): "Today 9:00–18:00", "Closed today", or null when the
     * value is outside the supported subset or says nothing about that day. Later rules win, as in OSM.
     */
    @Nullable
    static String today(@Nullable String value, int dayIndex) {
        if (TextUtils.isEmpty(value) || dayIndex < 0 || dayIndex > 6) return null;
        String trimmed = value.trim();
        if ("24/7".equals(trimmed)) return "Open 24 hours";
        String result = null;
        for (String rawRule : trimmed.split(";")) {
            String rule = rawRule.trim();
            if (rule.isEmpty()) continue;
            if (speakRule(rule) == null) return null;
            String[] parts = rule.split("\\s+", 2);
            boolean hasDays = DAYS.matcher(parts[0]).matches();
            if (hasDays && !coversDay(parts[0], dayIndex)) continue;
            String times = hasDays ? (parts.length > 1 ? parts[1].trim() : "") : rule;
            if ("off".equals(times) || "closed".equals(times)) {
                result = "Closed today";
            } else if (times.isEmpty()) {
                result = "Open today";
            } else {
                result = "Today " + speakTimes(times).replace(" to ", "–").replace(" and ", ", ");
            }
        }
        return result;
    }

    private static boolean coversDay(String days, int dayIndex) {
        for (String group : days.split(",")) {
            String[] range = group.split("-");
            int first = dayIndexOf(range[0]);
            int last = range.length > 1 ? dayIndexOf(range[1]) : first;
            if (first < 0 || last < 0) return false;
            if (first <= last ? dayIndex >= first && dayIndex <= last : dayIndex >= first || dayIndex <= last) return true;
        }
        return false;
    }

    private static int dayIndexOf(String code) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equals(code)) return i;
        }
        return -1;
    }

    @Nullable
    private static String speakRule(String rule) {
        String[] parts = rule.split("\\s+", 2);
        String days = null;
        String times = rule;
        if (DAYS.matcher(parts[0]).matches()) {
            days = speakDays(parts[0]);
            if (days == null) return null;
            times = parts.length > 1 ? parts[1].trim() : "";
        }
        String spokenTimes;
        if (times.isEmpty()) {
            if (days == null) return null;
            spokenTimes = "open";
        } else if ("off".equals(times) || "closed".equals(times)) {
            spokenTimes = "closed";
        } else {
            spokenTimes = speakTimes(times);
            if (spokenTimes == null) return null;
        }
        return days == null ? spokenTimes : days + ", " + spokenTimes;
    }

    @Nullable
    private static String speakDays(String value) {
        StringBuilder result = new StringBuilder();
        for (String group : value.split(",")) {
            String[] range = group.split("-");
            String first = dayName(range[0]);
            String last = range.length > 1 ? dayName(range[1]) : null;
            if (first == null || range.length > 1 && last == null) return null;
            if (result.length() > 0) result.append(", ");
            result.append(first);
            if (last != null) result.append(" to ").append(last);
        }
        return result.toString();
    }

    @Nullable
    private static String speakTimes(String value) {
        StringBuilder result = new StringBuilder();
        for (String range : value.split(",")) {
            Matcher matcher = TIME.matcher(range.trim());
            if (!matcher.matches()) return null;
            if (result.length() > 0) result.append(" and ");
            result.append(time(matcher.group(1), matcher.group(2))).append(" to ")
                    .append(time(matcher.group(3), matcher.group(4)));
        }
        return result.toString();
    }

    private static String time(String hours, String minutes) {
        return Integer.parseInt(hours) + ":" + minutes;
    }

    @Nullable
    private static String dayName(String code) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equals(code)) return NAMES[i];
        }
        return null;
    }
}
