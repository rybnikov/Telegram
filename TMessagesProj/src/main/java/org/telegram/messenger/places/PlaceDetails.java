package org.telegram.messenger.places;

import android.text.TextUtils;

import org.json.JSONObject;

public final class PlaceDetails {
    public String title;
    public String category;
    public int stars = -1;
    public String address;
    public String openingHours;
    public String website;
    public String phone;

    public PlaceDetails merge(PlaceDetails other) {
        if (other == null) return this;
        if (TextUtils.isEmpty(title)) title = other.title;
        if (TextUtils.isEmpty(category)) category = other.category;
        if (stars < 0 && other.stars >= 0 && other.stars <= 5) stars = other.stars;
        if (TextUtils.isEmpty(address)) address = other.address;
        if (TextUtils.isEmpty(openingHours)) openingHours = other.openingHours;
        if (TextUtils.isEmpty(website)) website = other.website;
        if (TextUtils.isEmpty(phone)) phone = other.phone;
        return this;
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(title) && TextUtils.isEmpty(category) && stars < 0
                && TextUtils.isEmpty(address) && TextUtils.isEmpty(openingHours)
                && TextUtils.isEmpty(website) && TextUtils.isEmpty(phone);
    }

    JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        if (isEmpty()) return json.put("empty", true);
        put(json, "title", title);
        put(json, "category", category);
        if (stars >= 0) json.put("stars", stars);
        put(json, "address", address);
        put(json, "openingHours", openingHours);
        put(json, "website", website);
        put(json, "phone", phone);
        return json;
    }

    static PlaceDetails fromJson(JSONObject json) {
        PlaceDetails details = new PlaceDetails();
        if (json == null || json.optBoolean("empty")) return details;
        details.title = opt(json, "title");
        details.category = opt(json, "category");
        int parsedStars = json.optInt("stars", -1);
        details.stars = parsedStars >= 0 && parsedStars <= 5 ? parsedStars : -1;
        details.address = opt(json, "address");
        details.openingHours = opt(json, "openingHours");
        details.website = opt(json, "website");
        details.phone = opt(json, "phone");
        return details;
    }

    private static void put(JSONObject json, String key, String value) throws Exception {
        if (!TextUtils.isEmpty(value)) json.put(key, value);
    }

    private static String opt(JSONObject json, String key) {
        String value = json.optString(key, null);
        return TextUtils.isEmpty(value) ? null : value;
    }
}
