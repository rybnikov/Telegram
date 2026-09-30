package org.telegram.messenger.places;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.browser.external.ExternalHttpClient;
import org.telegram.messenger.duress.EmergencyPasscode;
import org.telegram.tgnet.ConnectionsManager;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class PlaceDetailsResolver {
    private static final long NEGATIVE_TTL_SECONDS = 24 * 60 * 60;
    private static final long POSITIVE_TTL_SECONDS = 7 * 24 * 60 * 60;
    private static final long FAILURE_PAUSE_MS = 10 * 60 * 1000L;
    private static final long OVERPASS_PAUSE_MS = 5 * 60 * 1000L;
    private static final long RETRY_DELAY_MS = 60 * 1000L;
    private static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";

    public interface Callback {
        void onResolved(@NonNull PlaceDetails details);
    }

    public static final class Request {
        public final String key;
        public final long dialogId;
        public final Double latitude;
        public final Double longitude;
        public final String title;
        public final PlaceDetails base;

        public Request(String key, long dialogId, Double latitude, Double longitude,
                       String title, PlaceDetails base) {
            this.key = key;
            this.dialogId = dialogId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.title = title;
            this.base = base != null ? base : new PlaceDetails();
        }
    }

    private static final class Waiter {
        final Callback callback;
        final PlaceDetails base;
        boolean complete;

        Waiter(Callback callback, PlaceDetails base) {
            this.callback = callback;
            this.base = base;
        }
    }

    private static final class Pending {
        final Request request;
        final ArrayList<Waiter> waiters = new ArrayList<>();

        Pending(Request request) {
            this.request = request;
        }
    }

    private final Context context;
    private final int account;
    private final MessagesStorage storage;
    private final DispatchQueue detailsQueue = new DispatchQueue("places-details");
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final HashMap<String, Pending> pendingByKey = new HashMap<>();
    private final HashMap<String, PlaceDetails> memoryCache = new HashMap<>();
    private final HashMap<String, Long> failedUntil = new HashMap<>();
    private final HashMap<String, Long> skipUntil = new HashMap<>();
    private Pending inFlight;
    private long overpassPausedUntil;
    private int generation;
    private volatile boolean destroyed;

    public PlaceDetailsResolver(@NonNull Context context, int account) {
        this.context = context.getApplicationContext();
        this.account = account;
        this.storage = MessagesStorage.getInstance(account);
    }

    public void prefetch(@NonNull Request request) {
        enqueue(request, null, 0);
    }

    public void awaitDetails(@NonNull Request request, long timeoutMs, @NonNull Callback callback) {
        enqueue(request, callback, timeoutMs);
    }

    public void onListHidden() {
        while (!queue.isEmpty()) {
            Pending pending = queue.removeFirst();
            pendingByKey.remove(identity(pending.request));
            deliverWaiters(pending, new PlaceDetails());
        }
    }

    public void destroy() {
        destroyed = true;
        generation++;
        while (!queue.isEmpty()) {
            Pending pending = queue.removeFirst();
            for (int i = 0; i < pending.waiters.size(); i++) pending.waiters.get(i).complete = true;
        }
        pendingByKey.clear();
        memoryCache.clear();
        skipUntil.clear();
    }

    /** A server that is rate limiting or failing is left alone; a single key backs off on its own. */
    static boolean throttled(long now, long pausedUntil, Long failedUntil) {
        return now < pausedUntil || failedUntil != null && now < failedUntil;
    }

    static long pauseUntil(int statusCode, long now) {
        return statusCode == 429 || statusCode >= 500 ? now + OVERPASS_PAUSE_MS : 0;
    }

    static long failureBackoff(long now) {
        return now + FAILURE_PAUSE_MS;
    }

    private void enqueue(Request request, Callback callback, long timeoutMs) {
        if (destroyed) return;
        String identity = identity(request);
        PlaceDetails cached = memoryCache.get(identity);
        if (cached != null) {
            if (callback != null) callback.onResolved(copy(request.base).merge(copy(cached)));
            return;
        }
        Long retryAt = skipUntil.get(identity);
        if (retryAt != null && System.currentTimeMillis() < retryAt) {
            // Offline or throttled: answer from the message instead of re-reading the cache on
            // every template rebuild.
            if (callback != null) callback.onResolved(copy(request.base));
            return;
        }
        Pending pending = pendingByKey.get(identity);
        if (pending == null) {
            pending = new Pending(request);
            pendingByKey.put(identity, pending);
            // A pressed row must not wait behind the prefetch queue.
            if (callback != null) queue.addFirst(pending);
            else queue.addLast(pending);
        } else if (callback != null && queue.remove(pending)) {
            queue.addFirst(pending);
        }
        if (callback != null) {
            Waiter waiter = new Waiter(callback, request.base);
            pending.waiters.add(waiter);
            if (timeoutMs > 0) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed || waiter.complete) return;
                    waiter.complete = true;
                    callback.onResolved(copy(request.base));
                }, timeoutMs);
            }
        }
        pump();
    }

    private void pump() {
        if (destroyed || inFlight != null || queue.isEmpty()) return;
        inFlight = queue.removeFirst();
        Pending pending = inFlight;
        int token = generation;
        if (pending.request.latitude == null || pending.request.longitude == null) {
            finish(pending, new PlaceDetails(), token, true);
            return;
        }
        String cacheKey = cacheKey(pending.request.latitude, pending.request.longitude);
        storage.getStorageQueue().postRunnable(() -> {
            PlaceDetails cached = readCache(cacheKey);
            AndroidUtilities.runOnUIThread(() -> {
                if (!isCurrent(pending, token)) return;
                if (cached != null) {
                    finish(pending, cached, token, true);
                } else if (!networkAllowed(pending.request)) {
                    finish(pending, new PlaceDetails(), token, false);
                } else {
                    detailsQueue.postRunnable(() -> resolveExternal(pending, cacheKey, token));
                }
            });
        });
    }

    private PlaceDetails readCache(String cacheKey) {
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized(
                    "SELECT data,time FROM places_meta_v1 WHERE url=?", cacheKey);
            if (cursor.next()) {
                String data = cursor.stringValue(0);
                long savedAt = cursor.longValue(1);
                if (isFresh(data, savedAt, System.currentTimeMillis() / 1000)) {
                    return PlaceDetails.fromJson(new JSONObject(data));
                }
            }
        } catch (Exception e) {
            storage.checkSQLException(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        return null;
    }

    private void resolveExternal(Pending pending, String cacheKey, int token) {
        PlaceDetails result = new PlaceDetails();
        boolean cache = true;
        if (!networkAllowed(pending.request)) {
            cache = false;
        } else {
            if (TextUtils.isEmpty(pending.request.base.address)) {
                result.merge(reverseGeocode(pending.request.latitude, pending.request.longitude));
            }
            if (networkAllowed(pending.request)) {
                long now = System.currentTimeMillis();
                if (!throttled(now, overpassPausedUntil, failedUntil.get(cacheKey))) {
                    try {
                        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
                        headers.put("User-Agent", "Foldogram/" + BuildVars.BUILD_VERSION_STRING);
                        headers.put("Accept-Language", Locale.getDefault().toLanguageTag());
                        String body = ExternalHttpClient.postForm(OVERPASS_URL,
                                buildOverpassBody(pending.request.latitude, pending.request.longitude, pending.request.title),
                                headers, 256 * 1024, 10_000);
                        result.merge(parseOverpass(body, pending.request.latitude, pending.request.longitude,
                                Locale.getDefault().getLanguage()));
                    } catch (ExternalHttpClient.HttpStatusException e) {
                        cache = false;
                        long pause = pauseUntil(e.statusCode, now);
                        if (pause > 0) overpassPausedUntil = pause;
                    } catch (IOException e) {
                        cache = false;
                        failedUntil.put(cacheKey, failureBackoff(now));
                    } catch (RuntimeException e) {
                        FileLog.e(e);
                    }
                } else {
                    cache = false;
                }
            } else {
                cache = false;
            }
        }
        if (cache) writeCache(cacheKey, result);
        PlaceDetails finalResult = result;
        boolean remember = cache;
        AndroidUtilities.runOnUIThread(() -> finish(pending, finalResult, token, remember));
    }

    private PlaceDetails reverseGeocode(double latitude, double longitude) {
        PlaceDetails result = new PlaceDetails();
        if (!Geocoder.isPresent()) return result;
        try {
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> addresses;
            if (Build.VERSION.SDK_INT >= 33) {
                ArrayList<Address> received = new ArrayList<>();
                CountDownLatch latch = new CountDownLatch(1);
                geocoder.getFromLocation(latitude, longitude, 1, value -> {
                    if (value != null) received.addAll(value);
                    latch.countDown();
                });
                if (!latch.await(5, TimeUnit.SECONDS)) return result;
                addresses = received;
            } else {
                FutureTask<List<Address>> task = new FutureTask<>(() -> geocoder.getFromLocation(latitude, longitude, 1));
                Thread thread = new Thread(task, "places-geocoder");
                thread.setDaemon(true);
                thread.start();
                addresses = task.get(5, TimeUnit.SECONDS);
            }
            if (addresses != null && !addresses.isEmpty()) result.address = formatAddress(addresses.get(0));
        } catch (Exception ignore) {
            // Missing or slow geocoding must not block Overpass or speech.
        }
        return result;
    }

    private static String formatAddress(Address address) {
        if (address == null) return null;
        String line = clean(address.getAddressLine(0));
        if (!TextUtils.isEmpty(line)) return line;
        StringBuilder result = new StringBuilder();
        if (!TextUtils.isEmpty(address.getThoroughfare())) result.append(address.getThoroughfare());
        if (!TextUtils.isEmpty(address.getSubThoroughfare())) {
            if (result.length() > 0) result.append(' ');
            result.append(address.getSubThoroughfare());
        }
        if (!TextUtils.isEmpty(address.getLocality())) {
            if (result.length() > 0) result.append(", ");
            result.append(address.getLocality());
        }
        return clean(result.toString());
    }

    private boolean networkAllowed(Request request) {
        return !destroyed && SharedConfig.extendedPreviews
                && !DialogObject.isEncryptedDialog(request.dialogId)
                && !EmergencyPasscode.isHidden(account, request.dialogId)
                && ConnectionsManager.getInstance(account).getConnectionState()
                != ConnectionsManager.ConnectionStateWaitingForNetwork;
    }

    private void writeCache(String cacheKey, PlaceDetails details) {
        try {
            String data = details.toJson().toString();
            storage.getStorageQueue().postRunnable(() -> {
                try {
                    SQLitePreparedStatement statement = storage.getDatabase()
                            .executeFast("REPLACE INTO places_meta_v1 VALUES(?,?,?)");
                    try {
                        statement.bindString(1, cacheKey);
                        statement.bindString(2, data);
                        statement.bindLong(3, System.currentTimeMillis() / 1000);
                        statement.step();
                    } finally {
                        statement.dispose();
                    }
                } catch (Exception e) {
                    storage.checkSQLException(e);
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void finish(Pending pending, PlaceDetails details, int token, boolean remember) {
        if (!isCurrent(pending, token)) return;
        String identity = identity(pending.request);
        if (remember) {
            memoryCache.put(identity, copy(details));
            skipUntil.remove(identity);
        } else {
            skipUntil.put(identity, System.currentTimeMillis() + RETRY_DELAY_MS);
        }
        pendingByKey.remove(identity);
        deliverWaiters(pending, details);
        inFlight = null;
        pump();
    }

    private boolean isCurrent(Pending pending, int token) {
        return !destroyed && token == generation && inFlight == pending;
    }

    private static void deliverWaiters(Pending pending, PlaceDetails details) {
        for (int i = 0; i < pending.waiters.size(); i++) {
            Waiter waiter = pending.waiters.get(i);
            if (waiter.complete) continue;
            waiter.complete = true;
            waiter.callback.onResolved(copy(waiter.base).merge(copy(details)));
        }
    }

    private static String identity(Request request) {
        return request.latitude != null && request.longitude != null
                ? cacheKey(request.latitude, request.longitude) : request.key;
    }

    private static PlaceDetails copy(PlaceDetails source) {
        PlaceDetails copy = new PlaceDetails();
        if (source == null) return copy;
        copy.title = source.title;
        copy.category = source.category;
        copy.stars = source.stars;
        copy.address = source.address;
        copy.openingHours = source.openingHours;
        copy.website = source.website;
        copy.phone = source.phone;
        return copy;
    }

    public static String cacheKey(double latitude, double longitude) {
        return String.format(Locale.US, "details:%.6f,%.6f", latitude, longitude);
    }

    static boolean isFresh(String data, long savedAtSeconds, long nowSeconds) {
        try {
            JSONObject json = new JSONObject(data);
            long ttl = json.optBoolean("empty") ? NEGATIVE_TTL_SECONDS : POSITIVE_TTL_SECONDS;
            return savedAtSeconds > 0 && nowSeconds - savedAtSeconds < ttl;
        } catch (Exception ignore) {
            return false;
        }
    }

    public static PlaceDetails parseOpenGraph(JSONObject json) {
        PlaceDetails result = new PlaceDetails();
        if (json == null) return result;
        String title = clean(json.optString("title", null));
        String description = clean(json.optString("address", null));
        if (!TextUtils.isEmpty(title) && !"Google Maps".equalsIgnoreCase(title)) {
            int split = title.indexOf(" · ");
            if (split > 0 && split < title.length() - 3) {
                result.title = clean(title.substring(0, split));
                result.address = clean(title.substring(split + 3));
            } else {
                result.title = title;
            }
        }
        if (!TextUtils.isEmpty(description)) {
            int split = description.indexOf(" · ");
            String rating = split > 0 ? description.substring(0, split).trim() : null;
            if (isStarRating(rating)) {
                result.stars = countStars(rating);
                result.category = clean(description.substring(split + 3));
            }
        }
        return result;
    }

    static PlaceDetails parseOverpass(String body, double latitude, double longitude, String language) {
        PlaceDetails empty = new PlaceDetails();
        try {
            JSONArray elements = new JSONObject(body).optJSONArray("elements");
            if (elements == null) return empty;
            JSONObject nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.optJSONObject(i);
                if (element == null) continue;
                JSONObject center = element.optJSONObject("center");
                double lat = center != null ? center.optDouble("lat", Double.NaN) : element.optDouble("lat", Double.NaN);
                double lon = center != null ? center.optDouble("lon", Double.NaN) : element.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                double distance = distanceSquared(latitude, longitude, lat, lon);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = element;
                }
            }
            if (nearest == null) return empty;
            JSONObject tags = nearest.optJSONObject("tags");
            if (tags == null) return empty;
            PlaceDetails result = new PlaceDetails();
            String normalizedLanguage = TextUtils.isEmpty(language) ? null : language.toLowerCase(Locale.ROOT);
            if (normalizedLanguage != null) result.title = clean(tags.optString("name:" + normalizedLanguage, null));
            if (TextUtils.isEmpty(result.title)) result.title = clean(tags.optString("name", null));
            result.openingHours = clean(tags.optString("opening_hours", null));
            result.website = first(tags, "website", "contact:website");
            result.phone = first(tags, "phone", "contact:phone");
            result.category = first(tags, "amenity", "shop", "tourism", "leisure", "office", "craft");
            if (result.category != null) result.category = result.category.replace('_', ' ');
            return result;
        } catch (Exception ignore) {
            return empty;
        }
    }

    static String buildOverpassBody(double latitude, double longitude, String title) {
        String point = String.format(Locale.US, "%.6f,%.6f", latitude, longitude);
        String selector;
        if (TextUtils.isEmpty(title)) {
            selector = "[name]";
            return "data=[out:json][timeout:8];(node(around:50," + point + ")" + selector
                    + ";way(around:50," + point + ")" + selector + ";);out center 5;";
        }
        String quoted = JSONObject.quote(escapeRegex(title));
        selector = "[\"name\"~" + quoted + ",i]";
        return "data=[out:json][timeout:8];(node(around:200," + point + ")" + selector
                + ";way(around:200," + point + ")" + selector + ";);out center 5;";
    }

    private static String first(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = clean(json.optString(key, null));
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
    }

    private static String escapeRegex(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) escaped.append('\\');
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static String clean(String value) {
        if (TextUtils.isEmpty(value)) return null;
        value = value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        return value.isEmpty() ? null : value;
    }

    private static boolean isStarRating(String value) {
        if (TextUtils.isEmpty(value) || value.length() != 5) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '★' && c != '☆') return false;
        }
        return true;
    }

    private static int countStars(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '★') count++;
        return count;
    }

    private static double distanceSquared(double lat1, double lon1, double lat2, double lon2) {
        double lat = lat1 - lat2;
        double lon = (lon1 - lon2) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return lat * lat + lon * lon;
    }
}
