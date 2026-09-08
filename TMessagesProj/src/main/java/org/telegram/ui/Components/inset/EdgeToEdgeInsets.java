package org.telegram.ui.Components.inset;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

/**
 * Separates system UI that needs an opaque app-side protection from transparent
 * gesture and IME insets. Layout code must still use and dispatch the original
 * insets to keep interactive content in a safe position.
 */
public final class EdgeToEdgeInsets {

    private EdgeToEdgeInsets() {
    }

    /**
     * Returns the visible part of the navigation UI that consumes taps. A zero
     * bottom inset identifies transparent gesture navigation; three-button
     * navigation and taskbars return their actual system-provided size.
     */
    @NonNull
    public static Insets getNavigationBarProtection(@NonNull WindowInsetsCompat insets) {
        final Insets navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Tappable-element insets were added in Android 10 together with
            // fully gestural navigation. Older navigation bars are opaque.
            return navigationBars;
        }
        final Insets tappableElements = insets.getInsets(WindowInsetsCompat.Type.tappableElement());

        return Insets.of(
            Math.min(navigationBars.left, tappableElements.left),
            Math.min(navigationBars.top, tappableElements.top),
            Math.min(navigationBars.right, tappableElements.right),
            Math.min(navigationBars.bottom, tappableElements.bottom)
        );
    }

    /**
     * Insets used only by legacy containers that cannot position their own
     * controls. Gesture navigation is deliberately excluded so their background
     * and content can reach the screen edge; IME and tappable system UI remain
     * protected.
     */
    public static int getLegacyBottomInset(@NonNull WindowInsetsCompat insets) {
        final int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        return Math.max(imeBottom, getNavigationBarProtection(insets).bottom);
    }
}
