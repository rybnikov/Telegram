package org.telegram.messenger.browser.external;

import android.os.Build;
import android.text.Html;
import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ExternalHtmlUtils {

    private static final String DESKTOP_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private ExternalHtmlUtils() {
    }

    public static String fetchHtml(String url, String startMarker, String endMarker, int maxChars) throws IOException {
        return fetchHtml(url, startMarker, endMarker, maxChars, null);
    }

    public static String fetchHtml(String url, String startMarker, String endMarker, int maxChars, java.util.Map<String, String> extraHeaders) throws IOException {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", getAcceptLanguageHeader());
            connection.setRequestProperty("Cache-Control", "max-age=0");
            connection.setRequestProperty("DNT", "1");
            connection.setRequestProperty("Sec-Fetch-Dest", "document");
            connection.setRequestProperty("Sec-Fetch-Mode", "navigate");
            connection.setRequestProperty("Sec-Fetch-Site", "none");
            connection.setRequestProperty("Sec-Fetch-User", "?1");
            connection.setRequestProperty("Sec-GPC", "1");
            connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
            connection.setRequestProperty("User-Agent", DESKTOP_UA);
            if (extraHeaders != null) {
                for (java.util.Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            connection.connect();

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_ACCEPTED && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                throw new IOException("Unexpected HTTP " + code);
            }

            inputStream = connection.getInputStream();
            return readUtf8Until(inputStream, startMarker, endMarker, maxChars);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignore) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static FetchResult fetchHtmlWithFinalUrl(String url, String startMarker, String endMarker, int maxChars) throws IOException {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", getAcceptLanguageHeader());
            connection.setRequestProperty("Cache-Control", "max-age=0");
            connection.setRequestProperty("DNT", "1");
            connection.setRequestProperty("Sec-Fetch-Dest", "document");
            connection.setRequestProperty("Sec-Fetch-Mode", "navigate");
            connection.setRequestProperty("Sec-Fetch-Site", "none");
            connection.setRequestProperty("Sec-Fetch-User", "?1");
            connection.setRequestProperty("Sec-GPC", "1");
            connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
            connection.setRequestProperty("User-Agent", DESKTOP_UA);
            connection.connect();

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_ACCEPTED && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                throw new IOException("Unexpected HTTP " + code);
            }

            String finalUrl = connection.getURL() != null ? connection.getURL().toString() : url;
            inputStream = connection.getInputStream();
            String html = readUtf8Until(inputStream, startMarker, endMarker, maxChars);
            return new FetchResult(html, finalUrl);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignore) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static String readUtf8Until(InputStream inputStream, String endMarker, int maxChars) throws IOException {
        return readUtf8Until(inputStream, null, endMarker, maxChars);
    }

    public static String readUtf8Until(InputStream inputStream, String startMarker, String endMarker, int maxChars) throws IOException {
        InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        try {
            StringBuilder builder = new StringBuilder(Math.min(maxChars, 64 * 1024));
            char[] buffer = new char[4096];
            boolean waitingForStart = !TextUtils.isEmpty(startMarker);
            int startIndex = -1;
            int lastSearchPos = 0;
            while (builder.length() < maxChars) {
                int limit = Math.min(buffer.length, maxChars - builder.length());
                int read = reader.read(buffer, 0, limit);
                if (read < 0) {
                    break;
                }
                int prevLen = builder.length();
                builder.append(buffer, 0, read);
                if (waitingForStart) {
                    int searchFrom = Math.max(0, prevLen - startMarker.length());
                    startIndex = builder.indexOf(startMarker, searchFrom);
                    waitingForStart = startIndex < 0;
                    if (waitingForStart) {
                        continue;
                    }
                }
                if (endMarker != null) {
                    int searchFrom = Math.max(startIndex >= 0 ? startIndex : 0, Math.max(0, prevLen - endMarker.length()));
                    if (builder.indexOf(endMarker, searchFrom) >= 0) {
                        break;
                    }
                }
            }
            return builder.toString();
        } finally {
            try {
                reader.close();
            } catch (Exception ignore) {
            }
        }
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

    public static String findMetaContentDecoded(String html, String key, String expectedValue) {
        String content = findMetaContent(html, key, expectedValue);
        if (!TextUtils.isEmpty(content)) {
            return decodeHtml(content);
        }
        return null;
    }

    public static String decodeHtml(String value) {
        if (TextUtils.isEmpty(value)) {
            return value;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        return Html.fromHtml(value).toString();
    }

    public static int parseIntSafe(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception ignore) {
            return 0;
        }
    }

    public static String trimForLog(String value) {
        if (TextUtils.isEmpty(value) || value.length() <= 180) {
            return value;
        }
        return value.substring(0, 180);
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

    static String getAcceptLanguageHeader() {
        Locale locale = Locale.getDefault();
        String language = locale.getLanguage();
        String country = locale.getCountry();
        if (TextUtils.isEmpty(language)) {
            return "en-US,en;q=0.9";
        }
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(country)) {
            sb.append(language).append('-').append(country);
            if (!"en".equals(language) || !"US".equals(country)) {
                sb.append(",").append(language).append(";q=0.9");
            }
        } else {
            sb.append(language);
        }
        if (!"en".equals(language)) {
            sb.append(",en-US;q=0.8,en;q=0.7");
        }
        return sb.toString();
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

    public static final class FetchResult {
        public final String html;
        public final String finalUrl;

        public FetchResult(String html, String finalUrl) {
            this.html = html;
            this.finalUrl = finalUrl;
        }
    }
}
