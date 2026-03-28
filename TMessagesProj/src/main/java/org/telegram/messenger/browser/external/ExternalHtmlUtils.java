package org.telegram.messenger.browser.external;

import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ExternalHtmlUtils {

    private ExternalHtmlUtils() {
    }

    public static String readUtf8Until(InputStream inputStream, String endMarker, int maxChars) throws IOException {
        return readUtf8Until(inputStream, null, endMarker, maxChars);
    }

    public static String readUtf8Until(InputStream inputStream, String startMarker, String endMarker, int maxChars) throws IOException {
        InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder(Math.min(maxChars, 64 * 1024));
        char[] buffer = new char[4096];
        boolean waitingForStart = !TextUtils.isEmpty(startMarker);
        int startIndex = -1;
        while (builder.length() < maxChars) {
            int limit = Math.min(buffer.length, maxChars - builder.length());
            int read = reader.read(buffer, 0, limit);
            if (read < 0) {
                break;
            }
            builder.append(buffer, 0, read);
            if (waitingForStart) {
                startIndex = builder.indexOf(startMarker);
                waitingForStart = startIndex < 0;
                if (waitingForStart) {
                    continue;
                }
            }
            int searchFrom = startIndex >= 0 ? startIndex : 0;
            if (builder.indexOf(endMarker, searchFrom) >= 0) {
                break;
            }
        }
        return builder.toString();
    }

    public static String findScriptContentById(String html, String scriptId) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(scriptId)) {
            return null;
        }
        int searchFrom = 0;
        while (true) {
            int tagStart = indexOfIgnoreCase(html, "<script", searchFrom);
            if (tagStart < 0) {
                return null;
            }
            int tagEnd = html.indexOf('>', tagStart);
            if (tagEnd < 0) {
                return null;
            }
            String tag = html.substring(tagStart, tagEnd + 1);
            String id = getAttribute(tag, "id");
            if (scriptId.equals(id)) {
                int closeStart = indexOfIgnoreCase(html, "</script>", tagEnd + 1);
                if (closeStart < 0) {
                    return null;
                }
                return html.substring(tagEnd + 1, closeStart);
            }
            searchFrom = tagEnd + 1;
        }
    }

    public static String findMetaContent(String html, String key, String expectedValue) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(key) || TextUtils.isEmpty(expectedValue)) {
            return null;
        }
        int searchFrom = 0;
        while (true) {
            int tagStart = indexOfIgnoreCase(html, "<meta", searchFrom);
            if (tagStart < 0) {
                return null;
            }
            int tagEnd = html.indexOf('>', tagStart);
            if (tagEnd < 0) {
                return null;
            }
            String tag = html.substring(tagStart, tagEnd + 1);
            String actualValue = getAttribute(tag, key);
            if (expectedValue.equalsIgnoreCase(actualValue)) {
                String content = getAttribute(tag, "content");
                if (!TextUtils.isEmpty(content)) {
                    return content;
                }
            }
            searchFrom = tagEnd + 1;
        }
    }

    public static int indexOfIgnoreCase(String value, String needle, int fromIndex) {
        if (value == null || needle == null) {
            return -1;
        }
        int max = value.length() - needle.length();
        for (int i = Math.max(fromIndex, 0); i <= max; i++) {
            if (value.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static String getAttribute(String tag, String attributeName) {
        if (TextUtils.isEmpty(tag) || TextUtils.isEmpty(attributeName)) {
            return null;
        }
        int length = tag.length();
        int index = 0;
        while (index < length) {
            char ch = tag.charAt(index);
            if (ch == '<' || ch == '>' || Character.isWhitespace(ch) || ch == '/') {
                index++;
                continue;
            }
            int nameStart = index;
            while (index < length) {
                ch = tag.charAt(index);
                if (ch == '=' || ch == '>' || ch == '/' || Character.isWhitespace(ch)) {
                    break;
                }
                index++;
            }
            String name = tag.substring(nameStart, index);
            while (index < length && Character.isWhitespace(tag.charAt(index))) {
                index++;
            }
            String value = null;
            if (index < length && tag.charAt(index) == '=') {
                index++;
                while (index < length && Character.isWhitespace(tag.charAt(index))) {
                    index++;
                }
                if (index < length) {
                    char quote = tag.charAt(index);
                    if (quote == '"' || quote == '\'') {
                        index++;
                        int valueStart = index;
                        while (index < length && tag.charAt(index) != quote) {
                            index++;
                        }
                        value = tag.substring(valueStart, Math.min(index, length));
                        if (index < length && tag.charAt(index) == quote) {
                            index++;
                        }
                    } else {
                        int valueStart = index;
                        while (index < length) {
                            ch = tag.charAt(index);
                            if (Character.isWhitespace(ch) || ch == '>') {
                                break;
                            }
                            index++;
                        }
                        value = tag.substring(valueStart, index);
                    }
                }
            }
            if (attributeName.equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }
}
