package org.telegram.messenger.auto;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AutoPlaybackQueueTest {

    private static final AutoPlaybackQueue.Labels LABELS = AutoUnreadPlaybackController.LABELS;

    private static AutoPlaybackQueue.Entry text(int id, int date, String text) {
        return entry(id, date, false, true, 1, "Ann", text, AutoPlaybackQueue.Media.NONE, null, 0, false, false);
    }

    private static AutoPlaybackQueue.Entry entry(int id, int date, boolean out, boolean unread, long sender, String name,
                                                 String text, AutoPlaybackQueue.Media media, String label,
                                                 int duration, boolean noSound, boolean viewOnce) {
        return new AutoPlaybackQueue.Entry(id, date, out, unread, sender, name, text, media, label,
                duration, noSound, viewOnce, "payload" + id);
    }

    private static List<AutoPlaybackQueue.Item> build(List<AutoPlaybackQueue.Entry> source, boolean group,
                                                      AutoPlaybackQueue.Labels labels) {
        return AutoPlaybackQueue.build(source, group, labels).items;
    }

    private static String spoken(AutoPlaybackQueue.Item item) {
        StringBuilder builder = new StringBuilder();
        for (AutoPlaybackQueue.Part part : item.spoken) {
            if (builder.length() > 0) builder.append(" | ");
            builder.append(part.text);
        }
        return builder.toString();
    }

    @Test public void ordersOldestFirstAndSkipsOutgoingAndRead() {
        List<AutoPlaybackQueue.Entry> source = Arrays.asList(
                text(5, 50, "fifth"),
                entry(4, 40, true, true, 9, "Me", "mine", AutoPlaybackQueue.Media.NONE, null, 0, false, false),
                text(2, 20, "second"),
                entry(1, 10, false, false, 1, "Ann", "already read", AutoPlaybackQueue.Media.NONE, null, 0, false, false),
                text(3, 20, "third"));
        List<AutoPlaybackQueue.Item> items = build(source, false, LABELS);
        assertEquals(3, items.size());
        assertEquals("second", spoken(items.get(0)));
        assertEquals("third", spoken(items.get(1)));
        assertEquals("fifth", spoken(items.get(2)));
    }

    @Test public void dropsDuplicatesAndKeepsOnlyNewestTwenty() {
        ArrayList<AutoPlaybackQueue.Entry> source = new ArrayList<>();
        for (int i = 1; i <= 25; i++) source.add(text(i, i, "m" + i));
        source.add(text(25, 25, "m25"));
        List<AutoPlaybackQueue.Item> items = build(source, false, LABELS);
        assertEquals(AutoPlaybackQueue.MAX_ITEMS, items.size());
        assertEquals("m6", spoken(items.get(0)));
        assertEquals("m25", spoken(items.get(items.size() - 1)));
    }

    @Test public void groupPrefixesSenderOnlyWhenItChanges() {
        List<AutoPlaybackQueue.Entry> source = Arrays.asList(
                entry(1, 1, false, true, 1, "Ann", "hi", AutoPlaybackQueue.Media.NONE, null, 0, false, false),
                entry(2, 2, false, true, 1, "Ann", "there", AutoPlaybackQueue.Media.NONE, null, 0, false, false),
                entry(3, 3, false, true, 2, "Bob", null, AutoPlaybackQueue.Media.VOICE, null, 7, false, false));
        List<AutoPlaybackQueue.Item> items = build(source, true, LABELS);
        assertEquals("Ann: | hi", spoken(items.get(0)));
        assertEquals("there", spoken(items.get(1)));
        assertEquals("Bob:", spoken(items.get(2)));
        assertTrue(items.get(2).play);
    }

    @Test public void privateChatHasNoSenderPrefix() {
        List<AutoPlaybackQueue.Item> items = build(
                Arrays.asList(text(1, 1, "hello")), false, LABELS);
        assertEquals("hello", spoken(items.get(0)));
    }

    @Test public void voiceAndRoundPlayAndMarkContentRead() {
        List<AutoPlaybackQueue.Entry> source = Arrays.asList(
                entry(1, 1, false, true, 1, "Ann", null, AutoPlaybackQueue.Media.VOICE, null, 12, false, false),
                entry(2, 2, false, true, 1, "Ann", null, AutoPlaybackQueue.Media.ROUND, null, 8, false, false));
        List<AutoPlaybackQueue.Item> items = build(source, false, LABELS);
        assertEquals(2, items.size());
        for (AutoPlaybackQueue.Item item : items) {
            assertTrue(item.play);
            assertTrue(item.markContentRead);
            assertTrue(item.spoken.isEmpty());
        }
        assertEquals("payload1", items.get(0).payload);
    }

    @Test public void videoIsAnnouncedThenPlayedWithoutContentReadMark() {
        List<AutoPlaybackQueue.Item> items = build(Arrays.asList(
                entry(1, 1, false, true, 1, "Ann", "look", AutoPlaybackQueue.Media.VIDEO, "Video", 100, false, false)),
                false, LABELS);
        assertEquals("Video, 1:40 | look", spoken(items.get(0)));
        assertTrue(items.get(0).play);
        assertFalse(items.get(0).markContentRead);
    }

    @Test public void silentGifLongAndViewOnceVideosAreOnlySpoken() {
        List<AutoPlaybackQueue.Item> items = build(Arrays.asList(
                entry(1, 1, false, true, 1, "Ann", null, AutoPlaybackQueue.Media.VIDEO, "Video", 20, true, false),
                entry(2, 2, false, true, 1, "Ann", "fun", AutoPlaybackQueue.Media.GIF, null, 3, false, false),
                entry(3, 3, false, true, 1, "Ann", null, AutoPlaybackQueue.Media.VIDEO, "Video",
                        AutoPlaybackQueue.MAX_VIDEO_AUDIO_SECONDS + 1, false, false),
                entry(4, 4, false, true, 1, "Ann", null, AutoPlaybackQueue.Media.VOICE, null, 5, false, true)),
                false, LABELS);
        assertEquals(4, items.size());
        for (AutoPlaybackQueue.Item item : items) assertFalse(item.play);
        assertEquals("Video without sound", spoken(items.get(0)));
        assertEquals("Video without sound | fun", spoken(items.get(1)));
        assertEquals("Long video, 6 minutes, skipped", spoken(items.get(2)));
        assertEquals("View once voice message, open it on the phone", spoken(items.get(3)));
    }

    @Test public void videoAtLimitStillPlays() {
        List<AutoPlaybackQueue.Item> items = build(Arrays.asList(
                entry(1, 1, false, true, 1, "Ann", null, AutoPlaybackQueue.Media.VIDEO, "Video",
                        AutoPlaybackQueue.MAX_VIDEO_AUDIO_SECONDS, false, false)), false, LABELS);
        assertTrue(items.get(0).play);
    }

    @Test public void otherMediaReadsTypeAndCaptionAndSkipsEmpty() {
        List<AutoPlaybackQueue.Item> items = build(Arrays.asList(
                entry(1, 1, false, true, 1, "Ann", "sunset", AutoPlaybackQueue.Media.OTHER, "Photo", 0, false, false),
                entry(2, 2, false, true, 1, "Ann", null, AutoPlaybackQueue.Media.OTHER, "Sticker", 0, false, false),
                entry(3, 3, false, true, 1, "Ann", "caption only", AutoPlaybackQueue.Media.OTHER, null, 0, false, false),
                entry(4, 4, false, true, 1, "Ann", null, AutoPlaybackQueue.Media.OTHER, null, 0, false, false),
                entry(5, 5, false, true, 1, "Ann", "  ", AutoPlaybackQueue.Media.NONE, null, 0, false, false)),
                false, LABELS);
        assertEquals(3, items.size());
        assertEquals("Photo | sunset", spoken(items.get(0)));
        assertEquals("Sticker", spoken(items.get(1)));
        assertEquals("caption only", spoken(items.get(2)));
    }

    @Test public void formatsDuration() {
        assertEquals("0:05", AutoPlaybackQueue.formatDuration(5));
        assertEquals("1:40", AutoPlaybackQueue.formatDuration(100));
        assertEquals("0:00", AutoPlaybackQueue.formatDuration(-3));
    }

    @Test public void timedPreviewLabel() {
        assertEquals("Voice message, 0:12", AutoDialogPreviewFormatter.timedLabel("Voice message", 12, null));
        assertEquals("Video, 1:40. look", AutoDialogPreviewFormatter.timedLabel("Video", 100, "look"));
        assertEquals("Video message", AutoDialogPreviewFormatter.timedLabel("Video message", 0, ""));
    }

    @Test public void withoutUnreadPlaysLastFiveInBothDirectionsNamingSenders() {
        ArrayList<AutoPlaybackQueue.Entry> source = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            source.add(entry(i, i, false, false, 1, "Ann", "a" + i, AutoPlaybackQueue.Media.NONE, null, 0, false, false));
        }
        source.add(entry(7, 7, true, false, 9, "You", null, AutoPlaybackQueue.Media.ROUND, null, 4, false, false));
        AutoPlaybackQueue.Result result = AutoPlaybackQueue.build(source, false, LABELS);
        assertFalse(result.unread);
        assertEquals(AutoPlaybackQueue.RECENT_ITEMS, result.items.size());
        assertEquals("Ann: | a3", spoken(result.items.get(0)));
        assertEquals("a6", spoken(result.items.get(3)));
        AutoPlaybackQueue.Item own = result.items.get(4);
        assertEquals("You:", spoken(own));
        assertTrue(own.play);
        // An outgoing round is never marked listened from our side.
        assertFalse(own.markContentRead);
    }

    @Test public void unreadModeWinsAndFlagsResult() {
        AutoPlaybackQueue.Result result = AutoPlaybackQueue.build(Arrays.asList(
                entry(1, 1, false, false, 1, "Ann", "old", AutoPlaybackQueue.Media.NONE, null, 0, false, false),
                text(2, 2, "new")), false, LABELS);
        assertTrue(result.unread);
        assertEquals(1, result.items.size());
        assertEquals("new", spoken(result.items.get(0)));
    }
}
