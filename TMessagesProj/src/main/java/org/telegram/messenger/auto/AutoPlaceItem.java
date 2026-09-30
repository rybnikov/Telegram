package org.telegram.messenger.auto;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.places.Place;
import org.telegram.messenger.places.PlaceEntry;
import org.telegram.tgnet.TLRPC;

import java.util.Locale;
import java.util.List;

final class AutoPlaceItem {
    final String key;
    final long dialogId;
    final int messageId;
    final long senderId;
    final String senderName;
    final String chatTitle;
    final boolean isGroup;
    final String title;
    final String subtitle;
    final Double latitude;
    final Double longitude;
    final String query;
    final Place.Provider provider;
    final int date;
    final String messageText;
    final boolean isLive;

    AutoPlaceItem(@NonNull String key, long dialogId, int messageId, long senderId,
                  @Nullable String senderName, @Nullable String chatTitle, boolean isGroup,
                  @Nullable String title, @Nullable String subtitle,
                  @Nullable Double latitude, @Nullable Double longitude, @Nullable String query,
                  @NonNull Place.Provider provider, int date, @Nullable String messageText,
                  boolean isLive) {
        this.key = key;
        this.dialogId = dialogId;
        this.messageId = messageId;
        this.senderId = senderId;
        this.senderName = compact(senderName, 80);
        this.chatTitle = emptyToNull(compact(chatTitle, 80));
        this.isGroup = isGroup;
        this.title = fallback(compact(title, 80), "Location");
        this.subtitle = fallback(compact(subtitle, 80), providerName(provider));
        this.latitude = latitude;
        this.longitude = longitude;
        this.query = emptyToNull(compact(query, 200));
        this.provider = provider;
        this.date = date;
        this.messageText = emptyToNull(compact(messageText, 200));
        this.isLive = isLive;
    }

    @Nullable
    static AutoPlaceItem fromEntry(@NonNull PlaceEntry entry, long senderId,
                                   @Nullable String senderName, @Nullable String chatTitle,
                                   boolean isGroup) {
        return fromMessage(entry.message.messageOwner, entry.message.getDialogId(), entry.message.getId(),
                entry.places, senderId, senderName, chatTitle, isGroup);
    }

    @Nullable
    static AutoPlaceItem fromMessage(@NonNull TLRPC.Message message, long dialogId, int messageId,
                                     @NonNull List<Place> places, long senderId,
                                     @Nullable String senderName, @Nullable String chatTitle,
                                     boolean isGroup) {
        Place chosen = firstNavigable(places);
        if (chosen == null || chosen.spoiler) {
            return null;
        }
        String messageText = compact(message.message, 200);
        boolean live = message.media instanceof TLRPC.TL_messageMediaGeoLive;
        String title = chosen.title;
        if (TextUtils.isEmpty(title)) title = messageText;
        if (TextUtils.isEmpty(title)) title = live ? "Live location" : "Location";
        String subtitle;
        if (!TextUtils.isEmpty(chosen.address)) {
            subtitle = chosen.address;
        } else if (chosen.latitude != null && chosen.longitude != null) {
            subtitle = chosen.providerName() + " · " + formatCoordinates(chosen.latitude, chosen.longitude, 5);
        } else {
            subtitle = chosen.providerName();
        }
        return new AutoPlaceItem(dialogId + ":" + messageId, dialogId, messageId, senderId,
                senderName, chatTitle, isGroup, title, subtitle, chosen.latitude, chosen.longitude,
                chosen.latitude == null ? navigationQuery(chosen) : null, chosen.provider,
                message.date, messageText, live);
    }

    /** The place a row navigates to: the first one with coordinates or a usable label. */
    @Nullable
    static Place firstNavigable(@NonNull List<Place> places) {
        for (int i = 0; i < places.size(); i++) {
            Place place = places.get(i);
            if (!place.spoiler && (place.latitude != null && place.longitude != null
                    || !TextUtils.isEmpty(place.title) && !place.providerName().equalsIgnoreCase(place.title))) return place;
        }
        return null;
    }

    private static String navigationQuery(Place place) {
        if (TextUtils.isEmpty(place.address) || place.address.equals(place.title)) return place.title;
        return place.title + ", " + place.address;
    }

    @NonNull
    String displaySenderTitle() {
        String sender = fallback(senderName, chatTitle);
        sender = fallback(sender, "Unknown");
        if (!TextUtils.isEmpty(chatTitle) && !chatTitle.equals(sender)) {
            return sender + " · " + chatTitle;
        }
        return sender;
    }

    @Nullable
    String buildNavigationUri() {
        if (latitude != null && longitude != null) {
            String coordinates = String.format(Locale.US, "%.5f,%.5f", latitude, longitude);
            String label = compact(title.replace("(", " ").replace(")", " ").replace(",", " "), 60);
            return "geo:" + coordinates + "?q=" + coordinates + "(" + Uri.encode(label) + ")";
        }
        if (!TextUtils.isEmpty(query)) {
            return "geo:0,0?q=" + Uri.encode(query);
        }
        return null;
    }

    static String formatCoordinates(double latitude, double longitude, int decimals) {
        return String.format(Locale.US, "%1$." + decimals + "f, %2$." + decimals + "f", latitude, longitude);
    }

    private static String providerName(Place.Provider provider) {
        return new Place(provider, null).providerName();
    }

    static String compact(@Nullable String value, int limit) {
        if (TextUtils.isEmpty(value)) return "";
        String normalized = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        normalized = normalized.replaceAll("\\s+", " ");
        if (normalized.length() <= limit) return normalized;
        int end = Math.max(0, limit - 3);
        if (end > 0 && Character.isHighSurrogate(normalized.charAt(end - 1))) end--;
        return normalized.substring(0, end).trim() + "...";
    }

    private static String fallback(@Nullable String value, @Nullable String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static String emptyToNull(@Nullable String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }
}
