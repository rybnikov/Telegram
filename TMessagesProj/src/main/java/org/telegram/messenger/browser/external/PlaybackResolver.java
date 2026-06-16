package org.telegram.messenger.browser.external;

public interface PlaybackResolver {
    Playback resolvePlayback(ParsedLink link) throws Exception;
}
