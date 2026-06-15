package org.telegram.messenger.browser.external;

import java.io.IOException;
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
