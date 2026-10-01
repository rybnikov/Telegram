package org.telegram.messenger.places;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class PlaceDetailsResolver {
    private static final long NEGATIVE_TTL_SECONDS = 24 * 60 * 60;
    private static final long POSITIVE_TTL_SECONDS = 7 * 24 * 60 * 60;
    private static final long FAILURE_PAUSE_MS = 10 * 60 * 1000L;
    private static final long SERVICE_PAUSE_MS = 5 * 60 * 1000L;
    private static final long RETRY_DELAY_MS = 60 * 1000L;
    private static final int NETWORK_TIMEOUT_MS = 4_000;
    // Elapsed-time limits inside one lookup, so a slow place frees the network slot quickly.
    private static final long REVERSE_BUDGET_MS = 3_000;
    private static final long WIKIPEDIA_BUDGET_MS = 3_500;
    private static final int MEMORY_LIMIT = 128;
    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/";
    private static final String WIKIDATA_API = "https://www.wikidata.org/w/api.php?";
    private static final int DESCRIPTION_LIMIT = 240;
    // Nominatim usage policy: at most one request per second from an application.
    private static final long MIN_REQUEST_GAP_MS = 1_100;
    // About 250 m around the point for a name search.
    private static final double SEARCH_RADIUS_DEGREES = 0.0025;
    private static final String[] NON_PLACE_CATEGORIES = {"highway", "place", "boundary", "landuse", "railway", "waterway"};
    private static final String[] POI_CATEGORIES = {"amenity", "shop", "tourism", "leisure", "office", "craft",
            "historic", "healthcare", "club", "emergency", "sport", "man_made", "aeroway"};

    public interface Callback {
        void onResolved(@NonNull PlaceDetails details);
    }

    public static final class Request {
        public final String key;
        public final long dialogId;
        public final Double latitude;
        public final Double longitude;
        /** A venue or map-link name. Never message text: it is sent to Nominatim. */
        public final String placeName;
        public final PlaceDetails base;
        /** Live locations and restricted messages never leave the device. */
        public final boolean localOnly;
        /** BCP 47 language for names and addresses, usually the message language; null = device. */
        public final String language;

        public Request(String key, long dialogId, Double latitude, Double longitude,
                       String placeName, PlaceDetails base, boolean localOnly) {
            this(key, dialogId, latitude, longitude, placeName, base, localOnly, null);
        }

        public Request(String key, long dialogId, Double latitude, Double longitude,
                       String placeName, PlaceDetails base, boolean localOnly, String language) {
            this.key = key;
            this.dialogId = dialogId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.placeName = placeName;
            this.base = base != null ? base : new PlaceDetails();
            this.localOnly = localOnly;
            this.language = language;
        }

        public Request withLanguage(String language) {
            return new Request(key, dialogId, latitude, longitude, placeName, base, localOnly, language);
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
    // Only network lookups are serialized (Nominatim allows one request per second); cache reads
    // run at once, so a cached place never waits behind another place's slow lookup.
    private final ArrayDeque<Pending> networkQueue = new ArrayDeque<>();
    private final HashMap<String, Pending> pendingByKey = new HashMap<>();
    private final HashMap<String, PlaceDetails> memoryCache = lru();
    private final HashMap<String, Long> failedUntil = lru();
    private final HashMap<String, Long> skipUntil = lru();
    private Pending inFlight;
    private long servicePausedUntil;
    private long lastRequestAt;
    private int generation;
    private volatile boolean destroyed;

    public PlaceDetailsResolver(@NonNull Context context, int account) {
        this.context = context.getApplicationContext();
        this.account = account;
        this.storage = MessagesStorage.getInstance(account);
    }

    private enum Outcome { REMEMBER, RETRY_LATER }

    private static <V> HashMap<String, V> lru() {
        return new LinkedHashMap<String, V>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MEMORY_LIMIT;
            }
        };
    }

    /** Only an explicit About open calls this; it is the only path that may reach the network. */
    public void awaitDetails(@NonNull Request request, long timeoutMs, @NonNull Callback callback) {
        if (destroyed) return;
        String identity = identity(request);
        PlaceDetails cached = memoryCache.get(identity);
        if (cached != null) {
            diag("memory hit " + describe(cached));
            callback.onResolved(copy(request.base).merge(copy(cached)));
            return;
        }
        Long retryAt = skipUntil.get(identity);
        if (retryAt != null && System.currentTimeMillis() < retryAt) {
            diag("retry pause, answering from the message");
            callback.onResolved(copy(request.base));
            return;
        }
        Waiter waiter = new Waiter(callback, request.base);
        if (timeoutMs > 0) {
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || waiter.complete) return;
                waiter.complete = true;
                diag("wait expired after " + timeoutMs + " ms");
                callback.onResolved(copy(request.base));
            }, timeoutMs);
        }
        Pending pending = pendingByKey.get(identity);
        if (pending != null) {
            pending.waiters.add(waiter);
            // The newest About goes first among queued network lookups.
            if (networkQueue.remove(pending)) networkQueue.addFirst(pending);
            return;
        }
        pending = new Pending(request);
        pending.waiters.add(waiter);
        pendingByKey.put(identity, pending);
        readCacheThenLookup(pending);
    }

    public void destroy() {
        destroyed = true;
        generation++;
        for (Pending pending : pendingByKey.values()) {
            for (int i = 0; i < pending.waiters.size(); i++) pending.waiters.get(i).complete = true;
        }
        networkQueue.clear();
        pendingByKey.clear();
        memoryCache.clear();
        skipUntil.clear();
        // Tasks queued before this one see `destroyed` and return; then the thread exits.
        detailsQueue.postRunnable(detailsQueue::recycle);
    }

    /** A server that is rate limiting or failing is left alone; a single key backs off on its own. */
    static boolean throttled(long now, long pausedUntil, Long failedUntil) {
        return now < pausedUntil || failedUntil != null && now < failedUntil;
    }

    static long pauseUntil(int statusCode, long now) {
        return statusCode == 429 || statusCode >= 500 ? now + SERVICE_PAUSE_MS : 0;
    }

    static long failureBackoff(long now) {
        return now + FAILURE_PAUSE_MS;
    }

    private void readCacheThenLookup(Pending pending) {
        int token = generation;
        // Query-only map links (name/address, no coordinates) are looked up by that text.
        String cacheKey = storageKey(pending.request);
        if (!hasPoint(pending.request)) diag("no coordinates, link name=" + (cacheKey != null));
        if (cacheKey == null) {
            finish(pending, new PlaceDetails(), token, Outcome.REMEMBER);
            return;
        }
        storage.getStorageQueue().postRunnable(() -> {
            PlaceDetails cached = readCache(cacheKey);
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || token != generation) return;
                if (cached != null) {
                    diag("cache hit " + describe(cached));
                    finish(pending, cached, token, Outcome.REMEMBER);
                } else if (!networkAllowed(pending.request)) {
                    diag("network not allowed: localOnly=" + pending.request.localOnly
                            + " previews=" + SharedConfig.extendedPreviews);
                    finish(pending, new PlaceDetails(), token, Outcome.RETRY_LATER);
                } else {
                    networkQueue.addFirst(pending);
                    pumpNetwork();
                }
            });
        });
    }

    private void pumpNetwork() {
        if (destroyed || inFlight != null || networkQueue.isEmpty()) return;
        Pending pending = networkQueue.removeFirst();
        inFlight = pending;
        int token = generation;
        String cacheKey = storageKey(pending.request);
        diag("lookup name=" + !TextUtils.isEmpty(pending.request.placeName) + " lang=" + pending.request.language);
        detailsQueue.postRunnable(() -> resolveExternal(pending, cacheKey, token));
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
        boolean cache = networkAllowed(pending.request);
        if (cache) {
            boolean point = hasPoint(pending.request);
            double latitude = point ? pending.request.latitude : 0, longitude = point ? pending.request.longitude : 0;
            // Geocoder and Nominatim run side by side so one About press fits the car wait.
            FutureTask<PlaceDetails> geocode = null;
            if (point && TextUtils.isEmpty(pending.request.base.address)) {
                geocode = new FutureTask<>(() -> reverseGeocode(latitude, longitude));
                Thread thread = new Thread(geocode, "places-geocoder");
                thread.setDaemon(true);
                thread.start();
            }
            long now = System.currentTimeMillis();
            if (!throttled(now, servicePausedUntil, failedUntil.get(cacheKey))) {
                try {
                    String language = !TextUtils.isEmpty(pending.request.language)
                            ? pending.request.language : Locale.getDefault().toLanguageTag();
                    PlaceDetails found = null;
                    if (!point) {
                        found = parseNominatimSearch(nominatim(searchUrl(null, null,
                                lookupQuery(pending.request), language), language));
                        if (TextUtils.isEmpty(pending.request.base.address)) found = brandOnly(found);
                    } else if (!TextUtils.isEmpty(pending.request.placeName)) {
                        found = parseNominatimSearch(nominatim(searchUrl(latitude, longitude,
                                pending.request.placeName, language), language));
                    }
                    // Budget: the car waits 6 s, so optional steps are skipped once time runs short.
                    boolean needReverse = point && (found == null || TextUtils.isEmpty(found.title));
                    if (needReverse && System.currentTimeMillis() - now >= REVERSE_BUDGET_MS) {
                        // Skipped for time, not answered: never remember the partial result.
                        cache = false;
                    } else if (needReverse) {
                        PlaceDetails nearby = parseNominatim(new JSONObject(nominatim(
                                reverseUrl(latitude, longitude, language), language)));
                        found = found == null ? nearby : found.merge(nearby);
                    }
                    result.merge(found);
                    diag("nominatim " + describe(found));
                    boolean hasArticle = !TextUtils.isEmpty(result.wikipedia) || !TextUtils.isEmpty(result.wikidata);
                    if (hasArticle && System.currentTimeMillis() - now >= WIKIPEDIA_BUDGET_MS) {
                        cache = false;
                    } else if (!enrichFromWikipedia(result, language)) {
                        // An incomplete answer must not be remembered for a week.
                        cache = false;
                    }
                } catch (ExternalHttpClient.HttpStatusException e) {
                    diag("nominatim HTTP " + e.statusCode);
                    cache = false;
                    long pause = pauseUntil(e.statusCode, now);
                    if (pause > 0) servicePausedUntil = pause;
                } catch (IOException e) {
                    diag("nominatim failed " + e.getClass().getSimpleName());
                    cache = false;
                    failedUntil.put(cacheKey, failureBackoff(now));
                } catch (Exception e) {
                    // A captive portal or other non-JSON body: not an answer, so never cache it.
                    diag("nominatim unreadable " + e.getClass().getSimpleName());
                    cache = false;
                }
            } else {
                diag("nominatim paused");
                cache = false;
            }
            if (geocode != null) {
                try {
                    long left = Math.max(1, NETWORK_TIMEOUT_MS - (System.currentTimeMillis() - now));
                    result.merge(geocode.get(left, TimeUnit.MILLISECONDS));
                } catch (Exception ignore) {
                    // Missing or slow geocoding must not block Nominatim or speech, but an address
                    // that timed out must not be cached as absent.
                    if (TextUtils.isEmpty(result.address)) cache = false;
                }
            }
            if (destroyed) return;
        }
        diag("resolved " + describe(result) + " cached=" + cache);
        if (cache) writeCache(cacheKey, result);
        PlaceDetails finalResult = result;
        Outcome outcome = cache ? Outcome.REMEMBER : Outcome.RETRY_LATER;
        AndroidUtilities.runOnUIThread(() -> finish(pending, finalResult, token, outcome));
    }

    /** Blocking; runs on its own daemon thread, bounded by the caller's wait. */
    private PlaceDetails reverseGeocode(double latitude, double longitude) {
        PlaceDetails result = new PlaceDetails();
        if (!Geocoder.isPresent()) return result;
        try {
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> addresses;
            if (Build.VERSION.SDK_INT >= 33) {
                ArrayList<Address> received = new ArrayList<>();
                CountDownLatch latch = new CountDownLatch(1);
                geocoder.getFromLocation(latitude, longitude, 1, new Geocoder.GeocodeListener() {
                    @Override public void onGeocode(@NonNull List<Address> value) {
                        received.addAll(value);
                        latch.countDown();
                    }

                    @Override public void onError(@Nullable String message) {
                        latch.countDown();
                    }
                });
                if (!latch.await(NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return result;
                addresses = received;
            } else {
                addresses = geocoder.getFromLocation(latitude, longitude, 1);
            }
            if (addresses != null && !addresses.isEmpty()) result.address = formatAddress(addresses.get(0));
        } catch (Exception ignore) {
            // Missing or slow geocoding must not block Nominatim or speech.
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
        return !destroyed && !request.localOnly && SharedConfig.extendedPreviews
                && !DialogObject.isEncryptedDialog(request.dialogId)
                && !EmergencyPasscode.isHidden(account, request.dialogId)
                && ConnectionsManager.getInstance(account).getConnectionState()
                != ConnectionsManager.ConnectionStateWaitingForNetwork;
    }

    private void writeCache(String cacheKey, PlaceDetails details) {
        if (destroyed) return;
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

    private void finish(Pending pending, PlaceDetails details, int token, Outcome outcome) {
        if (destroyed || token != generation) return;
        String identity = identity(pending.request);
        if (outcome == Outcome.REMEMBER) {
            memoryCache.put(identity, copy(details));
            skipUntil.remove(identity);
        } else {
            skipUntil.put(identity, System.currentTimeMillis() + RETRY_DELAY_MS);
        }
        pendingByKey.remove(identity);
        deliverWaiters(pending, details);
        if (inFlight == pending) {
            inFlight = null;
            pumpNetwork();
        }
    }

    private static void deliverWaiters(Pending pending, PlaceDetails details) {
        for (int i = 0; i < pending.waiters.size(); i++) {
            Waiter waiter = pending.waiters.get(i);
            if (waiter.complete) continue;
            waiter.complete = true;
            waiter.callback.onResolved(copy(waiter.base).merge(copy(details)));
        }
    }

    /**
     * Memory identity equals the disk key, so requests in different languages never share an
     * answer: names, addresses and descriptions are language-specific.
     */
    private static String identity(Request request) {
        String key = storageKey(request);
        return key != null ? key : request.key;
    }

    /** Disk/memory key for a request, or null when it has neither a point nor a link name. */
    static String storageKey(Request request) {
        String language = languageKey(request.language);
        if (hasPoint(request)) {
            // Several venues share a point (one building): the searched name is part of the answer.
            String name = clean(request.placeName);
            String point = cacheKey(request.latitude, request.longitude, language);
            return name == null ? point : point + "|" + name.toLowerCase(Locale.ROOT);
        }
        String query = lookupQuery(request);
        return query != null ? queryKey(query, language) : null;
    }

    /** "" = device language (no message language known). */
    static String languageKey(String language) {
        return TextUtils.isEmpty(language) ? "" : primaryLanguage(language);
    }

    private static boolean hasPoint(Request request) {
        return request.latitude != null && request.longitude != null;
    }

    /** Link name plus link address; both come from the map URL or its preview, never the caption. */
    static String lookupQuery(Request request) {
        String name = clean(request.placeName);
        if (name == null) return null;
        String address = clean(request.base.address);
        if (address == null || name.toLowerCase(Locale.ROOT).contains(address.toLowerCase(Locale.ROOT))) return name;
        return name + ", " + address;
    }

    /**
     * An unbounded name search returns the first branch anywhere ("Sports 2000" is a shop in
     * Valencia first). Without a link address only what is true for every branch is kept.
     */
    static PlaceDetails brandOnly(PlaceDetails found) {
        if (found == null) return null;
        PlaceDetails result = new PlaceDetails();
        result.title = found.title;
        result.category = found.category;
        return result;
    }

    static String queryKey(String query, String language) {
        return "details3:qb:" + (TextUtils.isEmpty(language) ? "" : language + ":") + query.trim().toLowerCase(Locale.ROOT);
    }

    private static PlaceDetails copy(PlaceDetails source) {
        return source == null ? new PlaceDetails() : source.copy();
    }

    private static void diag(String message) {
        if (BuildVars.LOGS_ENABLED) FileLog.d("[AutoPlacesDiag] details " + message);
    }

    /** Which fields are present, never their values: logs must not carry place data. */
    private static String describe(PlaceDetails details) {
        if (details == null) return "{none}";
        return "{title=" + !TextUtils.isEmpty(details.title) + " category=" + !TextUtils.isEmpty(details.category)
                + " address=" + !TextUtils.isEmpty(details.address) + " hours=" + !TextUtils.isEmpty(details.openingHours) + "}";
    }

    /** v3 adds descriptions and images; v2 and older entries are ignored. */
    static String cacheKey(double latitude, double longitude, String language) {
        String point = String.format(Locale.US, "%.6f,%.6f", latitude, longitude);
        return "details3:" + (TextUtils.isEmpty(language) ? "" : language + ":") + point;
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

    /** Detailsqueue only: paces requests per the Nominatim usage policy. */
    private String nominatim(String url, String language) throws IOException {
        long wait = lastRequestAt + MIN_REQUEST_GAP_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        lastRequestAt = System.currentTimeMillis();
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Foldogram/" + BuildVars.BUILD_VERSION_STRING + " (Android Auto places)");
        headers.put("Accept-Language", language + ",en");
        return ExternalHttpClient.getText(url, headers, 64 * 1024, NETWORK_TIMEOUT_MS);
    }

    static String reverseUrl(double latitude, double longitude, String language) throws IOException {
        LinkedHashMap<String, String> query = baseQuery(language);
        query.put("lat", String.format(Locale.US, "%.6f", latitude));
        query.put("lon", String.format(Locale.US, "%.6f", longitude));
        query.put("zoom", "18");
        return NOMINATIM_URL + "reverse?" + ExternalHttpClient.encodeForm(query);
    }

    /** Searches only near the point, so a venue name never resolves to a namesake elsewhere. */
    static String searchUrl(Double latitude, Double longitude, String placeName, String language) throws IOException {
        LinkedHashMap<String, String> query = baseQuery(language);
        query.put("q", placeName);
        if (latitude != null && longitude != null) {
            double lonRadius = SEARCH_RADIUS_DEGREES / Math.max(0.01, Math.cos(Math.toRadians(latitude)));
            query.put("viewbox", String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f",
                    longitude - lonRadius, latitude + SEARCH_RADIUS_DEGREES,
                    longitude + lonRadius, latitude - SEARCH_RADIUS_DEGREES));
            query.put("bounded", "1");
        }
        query.put("limit", "1");
        return NOMINATIM_URL + "search?" + ExternalHttpClient.encodeForm(query);
    }

    private static LinkedHashMap<String, String> baseQuery(String language) {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("format", "jsonv2");
        query.put("addressdetails", "1");
        query.put("namedetails", "1");
        query.put("extratags", "1");
        query.put("accept-language", TextUtils.isEmpty(language) ? "en" : language + ",en");
        return query;
    }

    /** null = no match; a body that is not a JSON array (captive portal, error page) throws. */
    static PlaceDetails parseNominatimSearch(String body) throws JSONException {
        JSONArray results = new JSONArray(body);
        JSONObject first = results.optJSONObject(0);
        return first == null ? null : parseNominatim(first);
    }

    /** Streets, squares and areas give an address but are not "the place". */
    static PlaceDetails parseNominatim(JSONObject json) {
        PlaceDetails result = new PlaceDetails();
        if (json == null || json.has("error")) return result;
        String category = json.optString("category", "");
        String type = clean(json.optString("type", null));
        if (!contains(NON_PLACE_CATEGORIES, category)) result.title = clean(json.optString("name", null));
        if (contains(POI_CATEGORIES, category) && type != null && !"yes".equals(type)) {
            result.category = type.replace('_', ' ');
        }
        JSONObject tags = json.optJSONObject("extratags");
        if (tags != null) {
            result.wikipedia = clean(tags.optString("wikipedia", null));
            result.wikidata = clean(tags.optString("wikidata", null));
            result.openingHours = clean(tags.optString("opening_hours", null));
            result.website = first(tags, "website", "contact:website");
            result.phone = first(tags, "phone", "contact:phone");
        }
        JSONObject address = json.optJSONObject("address");
        if (address != null) {
            String street = first(address, "road", "pedestrian", "footway", "square", "place");
            String number = first(address, "house_number");
            String city = first(address, "city", "town", "village", "hamlet", "municipality");
            StringBuilder line = new StringBuilder();
            if (street != null) line.append(street);
            if (street != null && number != null) line.append(' ').append(number);
            if (city != null) {
                if (line.length() > 0) line.append(", ");
                line.append(city);
            }
            result.address = clean(line.toString());
        }
        return result;
    }

    /**
     * Notable places (museums, sights) link an article in OSM. The summary endpoint is key-free and
     * gives a description and a photo; the message language wins when that article exists.
     * Failures only leave the card without a description.
     */
    /** false only when a lookup was attempted and failed; "no article" is a complete answer. */
    private boolean enrichFromWikipedia(PlaceDetails details, String language) {
        if (TextUtils.isEmpty(details.wikipedia) && TextUtils.isEmpty(details.wikidata)) return true;
        String wanted = primaryLanguage(language);
        try {
            String[] article = parseWikipediaTag(details.wikipedia);
            if ((article == null || !article[0].equals(wanted)) && isWikidataId(details.wikidata)) {
                String title = parseSitelink(wikimedia(sitelinkUrl(details.wikidata, wanted)), details.wikidata, wanted);
                if (title == null && article == null) {
                    wanted = "en";
                    title = parseSitelink(wikimedia(sitelinkUrl(details.wikidata, wanted)), details.wikidata, wanted);
                }
                if (title != null) article = new String[]{wanted, title};
            }
            if (article == null) return true;
            PlaceDetails summary;
            try {
                summary = parseSummary(wikimedia(summaryUrl(article[0], article[1])));
            } catch (ExternalHttpClient.HttpStatusException e) {
                // A deleted article is a complete answer without a description.
                if (e.statusCode == 404) return true;
                throw e;
            }
            details.merge(summary);
            diag("wikipedia description=" + !TextUtils.isEmpty(summary.description)
                    + " image=" + !TextUtils.isEmpty(summary.imageUrl));
            return true;
        } catch (Exception e) {
            diag("wikipedia failed " + e.getClass().getSimpleName());
            return false;
        }
    }

    private String wikimedia(String url) throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Foldogram/" + BuildVars.BUILD_VERSION_STRING + " (Android Auto places)");
        return ExternalHttpClient.getText(url, headers, 128 * 1024, NETWORK_TIMEOUT_MS);
    }

    static String primaryLanguage(String tag) {
        if (TextUtils.isEmpty(tag)) return "en";
        int dash = tag.indexOf('-');
        String language = (dash > 0 ? tag.substring(0, dash) : tag).toLowerCase(Locale.ROOT);
        return language.matches("[a-z]{2,3}") ? language : "en";
    }

    /** OSM "wikipedia" tag: "lang:Title". */
    static String[] parseWikipediaTag(String tag) {
        if (TextUtils.isEmpty(tag)) return null;
        int colon = tag.indexOf(':');
        if (colon < 2 || colon > 3 || colon == tag.length() - 1) return null;
        String language = tag.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!language.matches("[a-z]{2,3}")) return null;
        return new String[]{language, tag.substring(colon + 1).trim()};
    }

    static boolean isWikidataId(String id) {
        return id != null && id.matches("Q[0-9]+");
    }

    static String sitelinkUrl(String wikidataId, String language) throws IOException {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("action", "wbgetentities");
        query.put("ids", wikidataId);
        query.put("props", "sitelinks");
        query.put("sitefilter", language + "wiki");
        query.put("format", "json");
        return WIKIDATA_API + ExternalHttpClient.encodeForm(query);
    }

    /** null = the item has no article in that language; an error body (maxlag, portal) throws. */
    static String parseSitelink(String body, String wikidataId, String language) throws JSONException {
        JSONObject root = new JSONObject(body);
        if (root.has("error")) throw new JSONException("wikidata error");
        JSONObject entity = root.getJSONObject("entities").optJSONObject(wikidataId);
        // A deleted or redirected item is a permanent "no article", not a failure.
        if (entity == null || entity.has("missing")) return null;
        JSONObject sitelinks = entity.optJSONObject("sitelinks");
        JSONObject link = sitelinks == null ? null : sitelinks.optJSONObject(language + "wiki");
        return link == null ? null : clean(link.optString("title", null));
    }

    static String summaryUrl(String language, String title) throws IOException {
        String path = java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20");
        return "https://" + language + ".wikipedia.org/api/rest_v1/page/summary/" + path;
    }

    /** A disambiguation or other non-article page is a complete "no description"; a bad body throws. */
    static PlaceDetails parseSummary(String body) throws JSONException {
        PlaceDetails result = new PlaceDetails();
        JSONObject json = new JSONObject(body);
        if (!"standard".equals(json.optString("type", "standard"))) return result;
        result.description = shortDescription(clean(json.optString("extract", null)));
        JSONObject thumbnail = json.optJSONObject("thumbnail");
        String image = thumbnail == null ? null : thumbnail.optString("source", null);
        if (image != null && image.startsWith("https://")) result.imageUrl = image;
        return result;
    }

    /** Whole sentences up to the limit, so the car never shows or speaks half a sentence. */
    static String shortDescription(String extract) {
        if (extract == null || extract.length() <= DESCRIPTION_LIMIT) return extract;
        int end = -1;
        for (int i = 0; i < DESCRIPTION_LIMIT; i++) {
            char c = extract.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < extract.length() && extract.charAt(i + 1) == ' ') end = i + 1;
        }
        if (end > 0) return extract.substring(0, end);
        int space = extract.lastIndexOf(' ', DESCRIPTION_LIMIT - 1);
        return extract.substring(0, space > 0 ? space : DESCRIPTION_LIMIT) + "…";
    }

    private static boolean contains(String[] values, String value) {
        for (String candidate : values) {
            if (candidate.equals(value)) return true;
        }
        return false;
    }

    private static String first(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = clean(json.optString(key, null));
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
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
}
