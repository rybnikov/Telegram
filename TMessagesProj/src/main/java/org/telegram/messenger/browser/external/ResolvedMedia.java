package org.telegram.messenger.browser.external;

import java.util.ArrayList;

public abstract class ResolvedMedia {
    public final String title;
    public final String description;

    protected ResolvedMedia(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public abstract static class Single extends ResolvedMedia {
        protected Single(String title, String description) {
            super(title, description);
        }
    }

    public static final class Video extends Single {
        public final String videoUrl;
        public final String posterUrl;
        public final int width;
        public final int height;

        public Video(String videoUrl, String posterUrl, String title, String description, int width, int height) {
            super(title, description);
            this.videoUrl = videoUrl;
            this.posterUrl = posterUrl;
            this.width = width;
            this.height = height;
        }
    }

    public static final class Image extends Single {
        public final String imageUrl;
        public final int width;
        public final int height;

        public Image(String imageUrl, String title, String description, int width, int height) {
            super(title, description);
            this.imageUrl = imageUrl;
            this.width = width;
            this.height = height;
        }
    }

    public static final class Carousel extends ResolvedMedia {
        public final ArrayList<Single> items;

        public Carousel(ArrayList<Single> items, String title, String description) {
            super(title, description);
            this.items = items;
        }
    }

    public static final class Preview extends Single {
        public final String posterUrl;
        public final String sourceUrl;
        public final int width;
        public final int height;

        public Preview(String sourceUrl, String posterUrl, String title, String description, int width, int height) {
            super(title, description);
            this.sourceUrl = sourceUrl;
            this.posterUrl = posterUrl;
            this.width = width;
            this.height = height;
        }
    }
}
