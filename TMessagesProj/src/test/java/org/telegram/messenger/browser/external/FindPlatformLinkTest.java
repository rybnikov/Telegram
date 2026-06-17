package org.telegram.messenger.browser.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE)
public final class FindPlatformLinkTest {

    private boolean logsEnabled;

    @Before
    public void setUp() {
        logsEnabled = PreviewTestResources.disableLogs();
    }

    @After
    public void tearDown() {
        PreviewTestResources.restoreLogs(logsEnabled);
    }

    @Test
    public void multiLinkMessageUsesFirstRecognizedExternalPreviewEntityUrl() {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 42;
        message.message = "a b c";
        message.entities.add(textUrl("https://example.com/not-supported"));
        message.entities.add(textUrl("https://www.instagram.com/reel/DZNeXBhxq6H/"));
        message.entities.add(textUrl("https://vm.tiktok.com/ZGd9LDKge/"));

        ParsedLink link = PreviewRepository.findPlatformLink(message);

        assertNotNull(link);
        assertEquals("Instagram", link.platformName);
        assertEquals("https://www.instagram.com/reel/DZNeXBhxq6H/", link.canonicalUrl);
    }

    @Test
    public void entityUrlsAreScannedInMessageOrder() {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 43;
        message.message = "x y";
        message.entities.add(textUrl("https://www.tiktok.com/@rodionlyan/video/7649461509629791509"));
        message.entities.add(textUrl("https://www.instagram.com/reel/DZNeXBhxq6H/"));

        ParsedLink link = PreviewRepository.findPlatformLink(message);

        assertNotNull(link);
        assertEquals("TikTok", link.platformName);
        assertEquals("7649461509629791509", link.id);
    }

    private TLRPC.TL_messageEntityTextUrl textUrl(String url) {
        TLRPC.TL_messageEntityTextUrl entity = new TLRPC.TL_messageEntityTextUrl();
        entity.offset = 0;
        entity.length = 1;
        entity.url = url;
        return entity;
    }
}
