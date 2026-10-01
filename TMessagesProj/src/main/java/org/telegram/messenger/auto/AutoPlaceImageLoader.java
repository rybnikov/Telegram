package org.telegram.messenger.auto;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.external.ExternalHttpClient;

import java.util.LinkedHashMap;

/** Downloads one card picture (Wikipedia photo or map-link preview), bounded in bytes and pixels. */
final class AutoPlaceImageLoader {
    interface Callback { void onLoaded(@Nullable Bitmap bitmap); }

    private static final DispatchQueue QUEUE = new DispatchQueue("places-images");
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PIXELS = 480;
    private static final int TARGET_PIXELS = 320;
    private static final int TIMEOUT_MS = 4_000;

    private AutoPlaceImageLoader() {
    }

    static void load(@Nullable String url, Callback callback) {
        if (url == null || !url.startsWith("https://")) {
            callback.onLoaded(null);
            return;
        }
        QUEUE.postRunnable(() -> {
            Bitmap bitmap = null;
            try {
                LinkedHashMap<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Foldogram/" + BuildVars.BUILD_VERSION_STRING + " (Android Auto places)");
                byte[] bytes = ExternalHttpClient.getBytes(url, headers, MAX_BYTES, TIMEOUT_MS);
                bitmap = decode(bytes);
            } catch (Exception e) {
                if (BuildVars.LOGS_ENABLED) FileLog.d("[AutoPlacesDiag] card image failed " + e.getClass().getSimpleName());
            }
            Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(result));
        });
    }

    @Nullable
    static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) return null;
        // The bitmap travels to the car host inside every template: keep it small.
        int[] size = scaledSize(decoded.getWidth(), decoded.getHeight());
        if (size[0] == decoded.getWidth() && size[1] == decoded.getHeight()) return decoded;
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, size[0], size[1], true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }

    /** Longest side at most {@link #TARGET_PIXELS}, aspect ratio kept. */
    static int[] scaledSize(int width, int height) {
        int longest = Math.max(width, height);
        if (longest <= TARGET_PIXELS) return new int[]{width, height};
        float scale = TARGET_PIXELS / (float) longest;
        return new int[]{Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))};
    }

    static int sampleSize(int width, int height) {
        int sample = 1;
        while (Math.max(width, height) / (sample * 2) >= MAX_PIXELS) sample *= 2;
        return sample;
    }
}
