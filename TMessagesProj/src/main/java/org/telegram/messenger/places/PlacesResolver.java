package org.telegram.messenger.places;

import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.browser.external.ExternalHtmlUtils;
import org.telegram.messenger.browser.external.ExternalHttpClient;
import org.telegram.messenger.duress.EmergencyPasscode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/** Bounded, visible-row enrichment; failure never hides or disables a source link. */
public final class PlacesResolver {
    private static final DispatchQueue QUEUE = new DispatchQueue("places-metadata");
    private final int account;
    private final HashMap<String, JSONObject> cache = new HashMap<>();
    private final HashSet<String> pending = new HashSet<>();
    private volatile boolean closed;
    private volatile HashSet<String> wanted = new HashSet<>();

    public void setVisible(ArrayList<PlaceEntry> entries, Runnable changed) {
        HashSet<String> urls = new HashSet<>();
        for (PlaceEntry entry : entries) {
            for (Place place : entry.places) {
                if (place.originalUrl != null && (!place.spoiler || entry.message.isSpoilersRevealed)) {
                    urls.add(place.originalUrl);
                }
            }
        }
        wanted = urls;
        for (PlaceEntry entry : entries) resolve(entry, changed);
    }
    public PlacesResolver(int account) { this.account = account; }

    public void apply(PlaceEntry entry) {
        if (!SharedConfig.extendedPreviews || DialogObject.isEncryptedDialog(entry.message.getDialogId())) return;
        for (Place place : entry.places) {
            JSONObject json = cache.get(place.originalUrl);
            if (json != null && (!place.spoiler || entry.message.isSpoilersRevealed)) apply(place, json);
        }
    }

    public void resolve(PlaceEntry entry, Runnable changed) {
        if (closed || !SharedConfig.extendedPreviews || entry.message.isRestrictedMessage
                || DialogObject.isEncryptedDialog(entry.message.getDialogId())
                || EmergencyPasscode.isHidden(account, entry.message.getDialogId())) return;
        apply(entry);
        for (Place place : entry.places) {
            String url = place.originalUrl;
            if (url == null || place.spoiler && !entry.message.isSpoilersRevealed || cache.containsKey(url) || !pending.add(url)) continue;
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                JSONObject cached = null;
                try {
                    SQLiteCursor c = storage.getDatabase().queryFinalized("SELECT data FROM places_meta_v1 WHERE url=? AND time>?", url, System.currentTimeMillis() / 1000 - 604800);
                    try { if (c.next()) cached = new JSONObject(c.stringValue(0)); } finally { c.dispose(); }
                } catch (Exception e) { storage.checkSQLException(e); }
                JSONObject result = cached;
                AndroidUtilities.runOnUIThread(() -> {
                    if (closed) return;
                    if (result != null) { finish(url, result, changed); return; }
                    storage.getExternalPreview(url, preview -> {
                        if (closed) return;
                        if (preview != null) {
                            JSONObject json = new JSONObject();
                            try {
                                json.put("title", preview.title);
                                json.put("address", preview.description);
                                json.put("image", preview.posterUrl != null ? preview.posterUrl : preview.mediaUrl);
                            } catch (Exception ignored) {}
                            finish(url, json, changed);
                        } else fetch(entry, url, changed);
                    });
                });
            });
        }
    }

    private void fetch(PlaceEntry entry, String url, Runnable changed) {
        QUEUE.postRunnable(() -> {
            if (closed || !SharedConfig.extendedPreviews || !wanted.contains(url) || EmergencyPasscode.isHidden(account, entry.message.getDialogId())) {
                AndroidUtilities.runOnUIThread(() -> pending.remove(url));
                return;
            }
            JSONObject json = new JSONObject();
            try {
                ExternalHtmlUtils.FetchResult result = ExternalHttpClient.fetchHtmlWithFinalUrl(url, null, "</head>", 96 * 1024);
                json = parseMetadata(url, result);
            } catch (Exception ignored) { /* Original destination remains usable offline. */ }
            if (closed) return;
            JSONObject metadata = json;
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                if (closed) return;
                try {
                    SQLitePreparedStatement s = storage.getDatabase().executeFast("REPLACE INTO places_meta_v1 VALUES(?,?,?)");
                    try { s.bindString(1, url); s.bindString(2, metadata.toString()); s.bindLong(3, System.currentTimeMillis() / 1000); s.step(); }
                    finally { s.dispose(); }
                    storage.getDatabase().executeFast("DELETE FROM places_meta_v1 WHERE time < " + (System.currentTimeMillis() / 1000 - 604800)).stepThis().dispose();
                } catch (Exception e) { storage.checkSQLException(e); }
            });
            AndroidUtilities.runOnUIThread(() -> finish(url, metadata, changed));
        });
    }

    static JSONObject parseMetadata(String url, ExternalHtmlUtils.FetchResult result) throws Exception {
        JSONObject json = new JSONObject();
        Place original = PlaceExtractor.parse(url), resolved = PlaceExtractor.parse(result.finalUrl);
        if (original != null && resolved != null && original.provider == resolved.provider) {
            json.put("resolved", result.finalUrl);
            json.put("title", ExternalHtmlUtils.findMetaContentDecoded(result.html, "property", "og:title"));
            json.put("address", ExternalHtmlUtils.findMetaContentDecoded(result.html, "property", "og:description"));
            String image = ExternalHtmlUtils.findMetaContentDecoded(result.html, "property", "og:image");
            if (image != null && image.startsWith("https://")) json.put("image", image);
        }
        return json;
    }

    private void finish(String url, JSONObject metadata, Runnable changed) {
        if (closed) return;
        pending.remove(url);
        cache.put(url, metadata);
        changed.run();
    }

    static void apply(Place place, JSONObject json) {
        String resolved = json.optString("resolved", null);
        Place parsed = PlaceExtractor.parse(resolved);
        if (parsed != null && parsed.provider == place.provider) {
            place.resolvedUrl = resolved;
            if (parsed.latitude != null) {
                place.latitude = parsed.latitude;
                place.longitude = parsed.longitude;
                place.confidence = parsed.confidence;
            }
            if (parsed.title != null) place.title = parsed.title;
        }
        String title = json.optString("title", null), address = json.optString("address", null);
        if (title != null && !title.isEmpty()) place.title = title;
        if (address != null && !address.isEmpty()) place.address = address;
        place.imageUrl = json.optString("image", null);
    }

    public void close() { closed = true; cache.clear(); pending.clear(); }
}
