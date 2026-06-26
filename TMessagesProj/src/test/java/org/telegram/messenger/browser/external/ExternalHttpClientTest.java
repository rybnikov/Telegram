package org.telegram.messenger.browser.external;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE, sdk = 28)
public final class ExternalHttpClientTest {

    @Test
    public void browserProfileContainsRequiredHeaders() {
        Map<String, String> headers = ExternalHttpClient.buildHeaders(null);

        assertEquals(ExternalHttpClient.BROWSER_USER_AGENT, headers.get("User-Agent"));
        assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", headers.get("Accept"));
        assertEquals("en-US,en;q=0.9", headers.get("Accept-Language"));
        assertEquals("\"macOS\"", headers.get("sec-ch-ua-platform"));
        assertEquals("document", headers.get("Sec-Fetch-Dest"));
        assertEquals("navigate", headers.get("Sec-Fetch-Mode"));
        assertEquals("none", headers.get("Sec-Fetch-Site"));
        assertEquals("1", headers.get("Upgrade-Insecure-Requests"));
    }

    @Test
    public void overridesAreAppliedOnTopOfBrowserProfile() {
        HashMap<String, String> overrides = new HashMap<>();
        overrides.put("User-Agent", "TelegramBot (like TwitterBot)");
        overrides.put("Priority", "u=0, i");

        Map<String, String> headers = ExternalHttpClient.buildHeaders(overrides);

        assertEquals("TelegramBot (like TwitterBot)", headers.get("User-Agent"));
        assertEquals("Priority override should be preserved", "u=0, i", headers.get("Priority"));
        assertEquals("en-US,en;q=0.9", headers.get("Accept-Language"));
        assertEquals("document", headers.get("Sec-Fetch-Dest"));
    }
}
