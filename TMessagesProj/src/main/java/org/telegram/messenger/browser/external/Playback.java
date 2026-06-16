package org.telegram.messenger.browser.external;

public abstract class Playback {

    private Playback() {
    }

    public static final class DirectStream extends Playback {
        public final String url;

        public DirectStream(String url) {
            this.url = url;
        }
    }

    public static final class Embed extends Playback {
        public final String url;

        public Embed(String url) {
            this.url = url;
        }
    }

    public static final class External extends Playback {
        public static final External INSTANCE = new External();

        private External() {
        }
    }
}
