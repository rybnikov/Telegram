package org.telegram.messenger.auto;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Row;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.FileLog;

import java.util.List;

final class AutoPlaceItemFactory {
    interface AboutListener { void onAbout(@NonNull AutoPlaceItem item); }

    private final CarContext carContext;
    private final AutoAvatarProvider avatarProvider;
    private final AutoSpeechController speechController;

    AutoPlaceItemFactory(CarContext carContext, AutoAvatarProvider avatarProvider,
                         AutoSpeechController speechController) {
        this.carContext = carContext;
        this.avatarProvider = avatarProvider;
        this.speechController = speechController;
    }

    ItemList buildItemList(@NonNull Screen screen, @NonNull List<AutoPlaceItem> items,
                           @NonNull String emptyMessage,
                           @NonNull AutoConversationItemFactory.ViewportListener viewportListener,
                           @NonNull AboutListener aboutListener) {
        ItemList.Builder list = new ItemList.Builder();
        list.setOnItemsVisibilityChangedListener((start, end) -> {
            if (start < 0 || end < 0) viewportListener.onListHidden(AutoPlacesRepository.LIST_KEY);
            else viewportListener.onVisibleRangeChanged(AutoPlacesRepository.LIST_KEY, start, end);
        });
        int limit = getHostLimit();
        for (int i = 0; i < items.size() && i < limit; i++) {
            list.addItem(buildRow(screen, items.get(i), aboutListener));
        }
        if (items.isEmpty()) list.setNoItemsMessage(emptyMessage);
        return list.build();
    }

    Row buildRow(@NonNull Screen screen, @NonNull AutoPlaceItem item,
                 @NonNull AboutListener aboutListener) {
        long avatarId = item.senderId == 0 ? item.dialogId : item.senderId;
        return buildRow(item.displaySenderTitle(), item.title, item.subtitle,
                avatarProvider.getRowDialogIcon(avatarId),
                icon(android.R.drawable.ic_menu_info_details),
                speechController.isActive(item.key),
                () -> navigate(screen, item),
                () -> aboutListener.onAbout(item));
    }

    /**
     * A row of a ListTemplate renders a single secondary action; a second one is dropped by the
     * host without an error, so navigation stays on the row click and the action reads the place.
     */
    static Row buildRow(@NonNull String title, @NonNull String firstLine, @NonNull String secondLine,
                        CarIcon image, CarIcon aboutIcon, boolean speaking,
                        @NonNull Runnable onTap, @NonNull Runnable onAbout) {
        Row.Builder row = new Row.Builder()
                .setTitle(title)
                .addText(firstLine)
                .addText(secondLine)
                .setOnClickListener(onTap::run);
        if (image != null) row.setImage(image, Row.IMAGE_TYPE_LARGE);
        Action.Builder about = new Action.Builder()
                .setTitle(speaking ? "Stop" : "About")
                .setOnClickListener(onAbout::run);
        if (aboutIcon != null) about.setIcon(aboutIcon);
        row.addAction(about.build());
        return row.build();
    }

    private int getHostLimit() {
        try {
            ConstraintManager manager = carContext.getCarService(ConstraintManager.class);
            return Math.max(0, Math.min(20,
                    manager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)));
        } catch (RuntimeException e) {
            FileLog.e(e);
            return 20;
        }
    }

    private CarIcon icon(int resource) {
        return new CarIcon.Builder(IconCompat.createWithResource(carContext, resource)).build();
    }

    private void navigate(Screen screen, AutoPlaceItem item) {
        String uri = item.buildNavigationUri();
        if (uri == null) return;
        try {
            screen.getCarContext().startCarApp(new Intent(CarContext.ACTION_NAVIGATE, Uri.parse(uri)));
        } catch (RuntimeException e) {
            FileLog.e(e);
            CarToast.makeText(screen.getCarContext(), "No navigation app", CarToast.LENGTH_SHORT).show();
        }
    }
}
