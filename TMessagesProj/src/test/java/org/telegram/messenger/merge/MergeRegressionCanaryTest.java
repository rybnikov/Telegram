package org.telegram.messenger.merge;

import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE, sdk = 28)
public final class MergeRegressionCanaryTest {

    private static final int MIN_CHAT_MESSAGE_CELL_EXT_PREVIEW_MARKERS = 20;
    private static final int MIN_MESSAGES_STORAGE_EXT_PREVIEW_MARKERS = 6;
    private static final int MIN_FORCE_RESET_ANIMATION_CALL_SITES = 5;

    @Test
    public void externalPreviewForkHooksAreStillPresent() throws Exception {
        String chatMessageCell = readMainSource("org/telegram/ui/Cells/ChatMessageCell.java");
        String messagesStorage = readMainSource("org/telegram/messenger/MessagesStorage.java");
        String externalPreviewManager = readMainSource("org/telegram/messenger/browser/external/ExternalPreviewManager.java");
        String externalLinkRouter = readMainSource("org/telegram/messenger/browser/external/ExternalLinkRouter.java");

        assertCountAtLeast(
            "ChatMessageCell FOLDOGRAM marker missing — likely wiped by upstream merge",
            chatMessageCell,
            "// FOLDOGRAM-EXT-PREVIEW:",
            MIN_CHAT_MESSAGE_CELL_EXT_PREVIEW_MARKERS
        );
        assertCountAtLeast(
            "MessagesStorage FOLDOGRAM marker missing — likely wiped by upstream merge",
            messagesStorage,
            "// FOLDOGRAM-EXT-PREVIEW:",
            MIN_MESSAGES_STORAGE_EXT_PREVIEW_MARKERS
        );

        assertContains("ExternalPreviewManager.applyCachedPreviewIfAvailable missing — likely wiped by upstream merge", externalPreviewManager, "applyCachedPreviewIfAvailable");
        assertContains("ExternalPreviewManager.requestPreviewIfNeeded missing — likely wiped by upstream merge", externalPreviewManager, "requestPreviewIfNeeded");
        assertContains("ExternalPreviewManager.openCachedPreview missing — likely wiped by upstream merge", externalPreviewManager, "openCachedPreview");
        assertContains("ChatMessageCell cached preview hook missing — likely wiped by upstream merge", chatMessageCell, "ExternalPreviewManager.applyCachedPreviewIfAvailable(messageObject)");
        assertContains("ChatMessageCell preview request hook missing — likely wiped by upstream merge", chatMessageCell, "ExternalLinkRouter.requestPreviewIfNeeded(messageObject)");
        assertContains("ExternalLinkRouter openCachedPreview bridge missing — likely wiped by upstream merge", externalLinkRouter, "ExternalPreviewManager.openCachedPreview");

        assertMainSourceExists("org/telegram/messenger/browser/external/ExternalPreviewCellBinder.java");
        assertMainSourceExists("org/telegram/messenger/browser/external/ExternalPreviewStorage.java");
        assertMainSourceExists("org/telegram/messenger/browser/external/PreviewClickDispatcher.java");
        assertMainSourceExists("org/telegram/messenger/browser/external/ExternalHttpClient.java");
    }

    @Test
    public void navigationForkHooksAreStillPresent() throws Exception {
        String actionBarLayout = readMainSource("org/telegram/ui/ActionBar/ActionBarLayout.java");
        String applicationLoader = readMainSource("org/telegram/messenger/ApplicationLoader.java");
        String messagesController = readMainSource("org/telegram/messenger/MessagesController.java");

        assertContains("forceResetAnimationState implementation missing — likely wiped by upstream merge", actionBarLayout, "private void forceResetAnimationState()");
        assertCountAtLeast(
            "forceResetAnimationState call-sites missing — likely wiped by upstream merge",
            actionBarLayout,
            "forceResetAnimationState();",
            MIN_FORCE_RESET_ANIMATION_CALL_SITES
        );
        assertContains("ApplicationLoader.isUiCompletelyPaused missing — likely wiped by upstream merge", applicationLoader, "public static boolean isUiCompletelyPaused()");
        assertContains("ApplicationLoader paused-state predicate changed — likely wiped by upstream merge", applicationLoader, "return mainInterfacePaused && externalInterfacePaused;");
        assertContains("MessagesController.sortDialogs pause gate missing — likely wiped by upstream merge", messagesController, "if (chatsDict == null && ApplicationLoader.isUiCompletelyPaused())");
    }

    private static void assertMainSourceExists(String relativePath) throws IOException {
        Path path = sourceRoot().resolve(relativePath);
        assertTrue("FOLDOGRAM source " + relativePath + " missing — likely wiped by upstream merge", Files.isRegularFile(path));
    }

    private static String readMainSource(String relativePath) throws IOException {
        return new String(Files.readAllBytes(sourceRoot().resolve(relativePath)), StandardCharsets.UTF_8);
    }

    private static Path sourceRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("TMessagesProj/src/main/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("FOLDOGRAM source root missing — likely running tests from unexpected directory");
    }

    private static void assertContains(String message, String source, String needle) {
        assertTrue(message + ": " + needle, source.contains(needle));
    }

    private static void assertCountAtLeast(String message, String source, String needle, int minimum) {
        int count = countOccurrences(source, needle);
        assertTrue(message + ": expected >= " + minimum + ", actual " + count, count >= minimum);
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while (true) {
            index = source.indexOf(needle, index);
            if (index < 0) {
                return count;
            }
            count++;
            index += needle.length();
        }
    }
}
