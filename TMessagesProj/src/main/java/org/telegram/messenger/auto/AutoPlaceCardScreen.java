package org.telegram.messenger.auto;

import android.graphics.Bitmap;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.places.PlaceDetails;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * About: one card per place. Details load once (network only from this explicit open), the
 * picture follows, and reading aloud is a button; leaving the card stops the reading.
 */
final class AutoPlaceCardScreen extends Screen {
    private static final long DETAILS_WAIT_MS = 6_000;
    private static final long LANGUAGE_TIMEOUT_MS = 800;
    private static final int DEFAULT_ROW_LIMIT = 4;

    private final AutoPlaceItem item;
    private final AutoPlacesRepository repository;
    private final AutoSpeechController speech;
    private final AutoAvatarProvider avatarProvider;
    private final AutoSpeechController.Listener speechListener = this::invalidate;
    @Nullable private PlaceDetails details;
    @Nullable private String language;
    @Nullable private CarIcon picture;
    private boolean destroyed;

    AutoPlaceCardScreen(@NonNull CarContext carContext, @NonNull AutoPlaceItem item,
                        @NonNull AutoPlacesRepository repository, @NonNull AutoSpeechController speech,
                        @NonNull AutoAvatarProvider avatarProvider) {
        super(carContext);
        this.item = item;
        this.repository = repository;
        this.speech = speech;
        this.avatarProvider = avatarProvider;
        speech.addListener(speechListener);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                destroyed = true;
                speech.removeListener(speechListener);
                if (speech.isActive(item.key)) speech.stop();
            }
        });
        load();
    }

    private void load() {
        // The message language picks the Nominatim/Wikipedia answer language and the voices.
        String seed = TextUtils.join(". ", nonEmpty(item.placeName, item.messageText, item.title));
        AutoLanguageDetection.detect(seed, LANGUAGE_TIMEOUT_MS, detected -> {
            if (destroyed) return;
            language = detected;
            repository.awaitDetails(item, detected, DETAILS_WAIT_MS, resolved -> {
                if (destroyed) return;
                details = resolved;
                invalidate();
                loadPicture(resolved);
            });
        });
    }

    private void loadPicture(PlaceDetails resolved) {
        if (!repository.mayFetchExternal(item)) return;
        String url = !TextUtils.isEmpty(resolved.imageUrl) ? resolved.imageUrl : repository.previewImage(item);
        AutoPlaceImageLoader.load(url, bitmap -> {
            if (destroyed || bitmap == null) return;
            picture = icon(bitmap);
            invalidate();
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        Pane.Builder pane = new Pane.Builder();
        if (details == null) {
            pane.setLoading(true);
        } else {
            long now = System.currentTimeMillis() / 1000;
            for (AutoPlaceCard.Line line : AutoPlaceCard.lines(item, details, now, todayIndex(), rowLimit())) {
                Row.Builder row = new Row.Builder().setTitle(line.title);
                if (!TextUtils.isEmpty(line.text)) row.addText(line.text);
                pane.addRow(row.build());
            }
            pane.addAction(new Action.Builder()
                    .setTitle("Navigate")
                    .setFlags(Action.FLAG_PRIMARY)
                    .setOnClickListener(() -> AutoPlaceItemFactory.navigate(this, item))
                    .build());
            pane.addAction(new Action.Builder()
                    .setTitle(speech.isActive(item.key) ? "Stop" : "Read aloud")
                    .setOnClickListener(this::toggleReading)
                    .build());
            CarIcon image = picture != null ? picture : avatarProvider.getRowDialogIcon(
                    item.senderId == 0 ? item.dialogId : item.senderId);
            if (image != null) pane.setImage(image);
        }
        return new PaneTemplate.Builder(pane.build())
                .setTitle(AutoPlaceCard.title(item))
                .setHeaderAction(Action.BACK)
                .build();
    }

    private void toggleReading() {
        PlaceDetails current = details;
        if (current == null) return;
        if (!speech.prepare(item.key)) return;
        List<List<AutoPlaceSummaryBuilder.Part>> sentences = AutoPlaceSummaryBuilder.buildSentences(item, current,
                System.currentTimeMillis() / 1000, TextToSpeech.getMaxSpeechInputLength() - 1);
        String seedLanguage = language;
        AutoLanguageDetection.detect(AutoSpeechLanguage.contentText(sentences), LANGUAGE_TIMEOUT_MS, detected ->
                speech.speakPrepared(item.key, AutoSpeechLanguage.resolve(sentences,
                        detected != null ? detected : seedLanguage, Locale.getDefault())));
    }

    private int rowLimit() {
        try {
            ConstraintManager manager = getCarContext().getCarService(ConstraintManager.class);
            return Math.max(1, manager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE));
        } catch (RuntimeException e) {
            FileLog.e(e);
            return DEFAULT_ROW_LIMIT;
        }
    }

    /** 0 = Monday, as in OSM opening_hours. */
    static int todayIndex() {
        return (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    private static CarIcon icon(Bitmap bitmap) {
        return new CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build();
    }

    private static List<String> nonEmpty(String... values) {
        ArrayList<String> result = new ArrayList<>();
        for (String value : values) {
            if (!TextUtils.isEmpty(value) && !result.contains(value)) result.add(value);
        }
        return result;
    }
}
