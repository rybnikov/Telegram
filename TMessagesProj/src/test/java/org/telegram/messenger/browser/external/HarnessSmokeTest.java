package org.telegram.messenger.browser.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE, sdk = 28)
public final class HarnessSmokeTest {

    @Test
    public void androidAndJsonClassesAreAvailable() throws Exception {
        Uri uri = Uri.parse("https://x");
        JSONObject json = new JSONObject("{}");

        assertEquals("https", uri.getScheme());
        assertTrue(TextUtils.isEmpty(""));
        assertEquals(0, json.length());
    }
}
