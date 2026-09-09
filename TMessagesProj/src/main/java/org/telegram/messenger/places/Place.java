package org.telegram.messenger.places;

/** A destination, independent of its presentation or navigation client. */
public final class Place {
    public enum Provider { GOOGLE, APPLE, YANDEX, WAZE, TELEGRAM }
    public enum Confidence { UNKNOWN, EXPLICIT_POINT, EXPLICIT_DESTINATION, TELEGRAM }
    public final Provider provider;
    public final String originalUrl;
    public String resolvedUrl, title, address, imageUrl;
    public Double latitude, longitude;
    public Confidence confidence = Confidence.UNKNOWN;
    public boolean spoiler;

    public Place(Provider provider, String originalUrl) {
        this.provider = provider;
        this.originalUrl = originalUrl;
    }

    public boolean setCoordinates(String value, boolean longitudeFirst, Confidence confidence) {
        if (value == null) return false;
        String[] pair = value.trim().split(",");
        if (pair.length != 2) return false;
        try {
            double first = Double.parseDouble(pair[0].trim()), second = Double.parseDouble(pair[1].trim());
            double lat = longitudeFirst ? second : first, lon = longitudeFirst ? first : second;
            if (Double.isNaN(lat) || Double.isInfinite(lat) || Double.isNaN(lon) || Double.isInfinite(lon)
                    || Math.abs(lat) > 90 || Math.abs(lon) > 180) return false;
            latitude = lat;
            longitude = lon;
            this.confidence = confidence;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public String providerName() {
        switch (provider) {
            case GOOGLE: return "Google Maps";
            case APPLE: return "Apple Maps";
            case YANDEX: return "Yandex Maps";
            case WAZE: return "Waze";
            default: return "Telegram";
        }
    }

    public String displayTitle() {
        if (title != null && !title.isEmpty()) return title;
        if (latitude != null) return latitude + ", " + longitude;
        return providerName();
    }
}
