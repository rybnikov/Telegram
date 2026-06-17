package org.telegram.messenger.browser.external;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE)
public final class PreviewClickDispatcherTest {

    @Test
    public void playbackTypeSelectsClickBranch() {
        assertEquals(
            PreviewClickDispatcher.Branch.DIRECT_STREAM,
            PreviewClickDispatcher.branchFor(new Playback.DirectStream("https://cdn.example/video.mp4"))
        );
        assertEquals(
            PreviewClickDispatcher.Branch.EMBED,
            PreviewClickDispatcher.branchFor(new Playback.Embed("https://example.com/embed/video"))
        );
        assertEquals(
            PreviewClickDispatcher.Branch.EXTERNAL,
            PreviewClickDispatcher.branchFor(Playback.External.INSTANCE)
        );
        assertEquals(
            PreviewClickDispatcher.Branch.EXTERNAL,
            PreviewClickDispatcher.branchFor(null)
        );
    }
}
