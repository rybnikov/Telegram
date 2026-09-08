package org.telegram.ui.ActionBar;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.Components.inset.EdgeToEdgeInsets;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class DrawerLayoutContainerEdgeToEdgeTest {

    private static final int CONTENT_COLOR = Color.rgb(224, 30, 90);
    private static final int PROTECTION_COLOR = Color.rgb(12, 80, 210);

    @Before
    public void setUpApplicationContext() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationLoaderInstance = new ApplicationLoader();
    }

    @Test
    public void gestureContentRemainsVisibleAfterRootDrawEvenWithImeInsets() {
        DrawerLayoutContainer container = createContainer(100, 120);

        applyInsets(container, 24, 0, 72);

        assertEquals(CONTENT_COLOR, drawBottomPixel(container));
        assertEquals(0, EdgeToEdgeInsets.getNavigationBarProtection(createInsets(24, 0, 72)).bottom);
        assertEquals(72, EdgeToEdgeInsets.getLegacyBottomInset(createInsets(24, 0, 72)));
    }

    @Test
    public void tappableNavigationAndTaskbarKeepOpaqueProtection() {
        DrawerLayoutContainer threeButtonContainer = createContainer(100, 120);
        WindowInsetsCompat threeButtonInsets = createInsets(48, 48, 0);
        assertEquals(48, threeButtonInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom);
        assertEquals(48, threeButtonInsets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom);
        threeButtonContainer.onApplyWindowInsets(threeButtonContainer, threeButtonInsets);
        assertEquals(PROTECTION_COLOR, drawBottomPixel(threeButtonContainer));

        WindowInsetsCompat taskbarInsets = createInsets(80, 80, 0);
        assertEquals(80, EdgeToEdgeInsets.getNavigationBarProtection(taskbarInsets).bottom);
        assertEquals(80, EdgeToEdgeInsets.getLegacyBottomInset(taskbarInsets));
        DrawerLayoutContainer taskbarContainer = createContainer(160, 100);
        taskbarContainer.onApplyWindowInsets(taskbarContainer, taskbarInsets);
        assertEquals(PROTECTION_COLOR, drawBottomPixel(taskbarContainer));
    }

    @Test
    @Config(sdk = 28)
    public void preGestureAndroidProtectsItsNavigationBar() {
        DrawerLayoutContainer container = createContainer(100, 120);

        applyInsets(container, 48, 0, 0);

        assertEquals(PROTECTION_COLOR, drawBottomPixel(container));
    }

    @Test
    public void missingNavigationBarNeedsNoProtection() {
        DrawerLayoutContainer container = createContainer(100, 120);

        applyInsets(container, 0, 0, 0);

        assertEquals(CONTENT_COLOR, drawBottomPixel(container));
    }

    @Test
    public void reappliedInsetsAfterRotationReplaceOldNavigationMode() {
        DrawerLayoutContainer container = createContainer(100, 160);
        applyInsets(container, 48, 48, 0);
        assertEquals(PROTECTION_COLOR, drawBottomPixel(container));

        measureAndLayout(container, 160, 100);
        WindowInsetsCompat landscapeButtons = createInsets(
            Insets.of(0, 0, 48, 0),
            Insets.of(0, 0, 48, 0),
            0
        );
        container.onApplyWindowInsets(container, landscapeButtons);
        assertEquals(PROTECTION_COLOR, drawPixel(container, container.getWidth() - 1, container.getHeight() / 2));

        applyInsets(container, 24, 0, 0);

        assertEquals(CONTENT_COLOR, drawBottomPixel(container));
        assertEquals(CONTENT_COLOR, drawPixel(container, container.getWidth() - 1, container.getHeight() / 2));
    }

    private static DrawerLayoutContainer createContainer(int width, int height) {
        DrawerLayoutContainer container = new DrawerLayoutContainer(RuntimeEnvironment.getApplication());
        container.setInternalNavigationBarColor(PROTECTION_COLOR);

        View content = new View(container.getContext());
        content.setBackgroundColor(CONTENT_COLOR);
        container.addView(content, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        measureAndLayout(container, width, height);
        return container;
    }

    private static void measureAndLayout(View view, int width, int height) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        );
        view.layout(0, 0, width, height);
    }

    private static void applyInsets(DrawerLayoutContainer view, int navigationBottom, int tappableBottom, int imeBottom) {
        view.onApplyWindowInsets(view, createInsets(navigationBottom, tappableBottom, imeBottom));
    }

    private static WindowInsetsCompat createInsets(int navigationBottom, int tappableBottom, int imeBottom) {
        return createInsets(
            Insets.of(0, 0, 0, navigationBottom),
            Insets.of(0, 0, 0, tappableBottom),
            imeBottom
        );
    }

    private static WindowInsetsCompat createInsets(Insets navigationInsets, Insets tappableInsets, int imeBottom) {
        final Insets imeInsets = Insets.of(0, 0, 0, imeBottom);

        return new WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), navigationInsets)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigationInsets)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), !Insets.NONE.equals(navigationInsets))
            .setInsets(WindowInsetsCompat.Type.tappableElement(), tappableInsets)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.tappableElement(), tappableInsets)
            .setVisible(WindowInsetsCompat.Type.tappableElement(), !Insets.NONE.equals(tappableInsets))
            .setInsets(WindowInsetsCompat.Type.ime(), imeInsets)
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottom > 0)
            .build();
    }

    private static int drawBottomPixel(DrawerLayoutContainer view) {
        return drawPixel(view, view.getWidth() / 2, view.getHeight() - 1);
    }

    private static int drawPixel(DrawerLayoutContainer view, int x, int y) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.dispatchDraw(new Canvas(bitmap));
        return bitmap.getPixel(x, y);
    }
}
