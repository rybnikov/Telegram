package org.telegram.messenger.browser.external;

public interface PlaybackResolver {
    Playback resolvePlayback(ParsedLink link) throws Exception;

    default Playback resolvePlayback(ParsedLink link, ResolvedMedia.Video video) throws Exception {
        return resolvePlayback(link);
    }
}
