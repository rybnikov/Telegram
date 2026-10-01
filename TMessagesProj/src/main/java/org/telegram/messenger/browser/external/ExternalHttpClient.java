package org.telegram.messenger.browser.external;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ExternalHttpClient {

    public static final String BROWSER_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final Map<String, String> BROWSER_PROFILE_HEADERS = createBrowserProfileHeaders();

    private ExternalHttpClient() {
    }

    public static String fetchHtml(String url, String startMarker, String endMarker, int maxChars) throws IOException {
        return fetchHtml(url, startMarker, endMarker, maxChars, null);
    }

    public static String fetchHtml(String url, String startMarker, String endMarker, int maxChars, Map<String, String> overrideHeaders) throws IOException {
        return ExternalHtmlUtils.fetchHtml(url, startMarker, endMarker, maxChars, buildHeaders(overrideHeaders));
    }

    public static ExternalHtmlUtils.FetchResult fetchHtmlWithFinalUrl(String url, String startMarker, String endMarker, int maxChars) throws IOException {
        return fetchHtmlWithFinalUrl(url, startMarker, endMarker, maxChars, null);
    }

    public static ExternalHtmlUtils.FetchResult fetchHtmlWithFinalUrl(String url, String startMarker, String endMarker, int maxChars, Map<String, String> overrideHeaders) throws IOException {
        return ExternalHtmlUtils.fetchHtmlWithFinalUrl(url, startMarker, endMarker, maxChars, buildHeaders(overrideHeaders));
    }

    /** Plain bounded GET for small JSON APIs; non-2xx answers throw {@link HttpStatusException}. */
    public static String getText(String url, Map<String, String> headers, int maxBytes, int timeoutMs) throws IOException {
        return new String(getBytes(url, headers, maxBytes, timeoutMs), StandardCharsets.UTF_8);
    }

    /** Bounded GET of a small binary body such as a preview image. */
    public static byte[] getBytes(String url, Map<String, String> headers, int maxBytes, int timeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpStatusException(status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                int cap = Math.max(0, maxBytes);
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > cap) throw new IOException("HTTP response exceeds limit");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Query-string or form encoding: every name and value is percent-encoded. */
    public static String encodeForm(Map<String, String> fields) throws IOException {
        StringBuilder body = new StringBuilder();
        if (fields == null) return "";
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getKey() == null) continue;
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), "UTF-8"));
        }
        return body.toString();
    }

    public static final class HttpStatusException extends IOException {
        public final int statusCode;

        HttpStatusException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    static Map<String, String> buildHeaders(Map<String, String> overrideHeaders) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>(BROWSER_PROFILE_HEADERS);
        if (overrideHeaders != null) {
            for (Map.Entry<String, String> entry : overrideHeaders.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                headers.put(entry.getKey(), entry.getValue());
            }
        }
        return headers;
    }

    private static Map<String, String> createBrowserProfileHeaders() {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", BROWSER_USER_AGENT);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("sec-ch-ua-platform", "\"macOS\"");
        headers.put("Sec-Fetch-Dest", "document");
        headers.put("Sec-Fetch-Mode", "navigate");
        headers.put("Sec-Fetch-Site", "none");
        headers.put("Upgrade-Insecure-Requests", "1");
        return Collections.unmodifiableMap(headers);
    }
}
