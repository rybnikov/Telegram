package org.telegram.messenger.places;

import android.net.Uri;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaceExtractor {
    private static final Pattern URL = Pattern.compile(
            "(?i)(?<![a-z0-9.-])(?:https?://)?(?:maps\\.app\\.goo\\.gl|goo\\.gl/maps|"
                    + "(?:www\\.)?google\\.com/maps|maps\\.google\\.com|maps\\.apple(?:\\.com)?|"
                    + "maps\\.yandex\\.(?:ru|com)|yandex\\.(?:ru|com|kz|by|uz)/maps|"
                    + "yandex\\.com\\.tr/harita|yandex\\.net/maps|(?:www\\.|ul\\.)?waze\\.com/(?:ul|live-map))"
                    + "[^\\s<>]*");
    private static final Pattern GOOGLE_POINT = Pattern.compile("!3d(-?[0-9.]+)!4d(-?[0-9.]+)");
    private PlaceExtractor() {}

    public static Place parse(String value) {
        if (value == null || value.length() > 8192) return null;
        String url = value.matches("(?i)^https?://.*") ? value : "https://" + value;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)) return null;
        host = host.toLowerCase(Locale.US);
        String path = uri.getPath() == null ? "" : uri.getPath();
        Place.Provider provider;
        if (host.equals("maps.app.goo.gl") || host.equals("maps.google.com")
                || ((host.equals("google.com") || host.equals("www.google.com")) && (path.equals("/maps") || path.startsWith("/maps/")))
                || ((host.equals("goo.gl") || host.equals("www.goo.gl")) && path.startsWith("/maps/"))) {
            provider = Place.Provider.GOOGLE;
        } else if ((host.equals("maps.apple.com") || host.equals("maps.apple") || host.endsWith(".maps.apple"))) {
            provider = Place.Provider.APPLE;
        } else if (host.equals("maps.yandex.ru") || host.equals("maps.yandex.com") || (host.equals("yandex.ru") || host.equals("yandex.com") || host.equals("yandex.kz") || host.equals("yandex.by") || host.equals("yandex.uz"))
                && (path.equals("/maps") || path.startsWith("/maps/")) || host.equals("yandex.com.tr") && path.startsWith("/harita/")
                || host.equals("yandex.net") && path.startsWith("/maps/")) {
            provider = Place.Provider.YANDEX;
        } else if (host.equals("waze.com") || host.equals("www.waze.com") || host.equals("ul.waze.com")) {
            if (!(path.equals("/ul") || path.startsWith("/ul/") || path.startsWith("/live-map"))) return null;
            provider = Place.Provider.WAZE;
        } else return null;
        Place place = new Place(provider, url);
        switch (provider) {
            case GOOGLE: {
                boolean route = path.contains("/dir/") || uri.getQueryParameter("destination") != null || uri.getQueryParameter("daddr") != null;
                String query = first(uri, route ? "destination" : "query", route ? "daddr" : "q");
                place.setCoordinates(query, false, route ? Place.Confidence.EXPLICIT_DESTINATION : Place.Confidence.EXPLICIT_POINT);
                if (!route && place.latitude == null) {
                    Matcher m = GOOGLE_POINT.matcher(url);
                    if (m.find()) {
                        String point = m.group(1) + "," + m.group(2);
                        if (!m.find()) place.setCoordinates(point, false, Place.Confidence.EXPLICIT_POINT);
                    }
                }
                if (query != null && place.latitude == null) place.title = query;
                int start = path.indexOf("/place/");
                if (start >= 0) place.title = path.substring(start + 7).split("/")[0].replace('+', ' ');
                break;
            }
            case APPLE: {
                String destination = first(uri, "destination", "daddr");
                if (destination != null) {
                    place.setCoordinates(destination, false, Place.Confidence.EXPLICIT_DESTINATION);
                    if (place.latitude == null) place.title = destination;
                } else {
                    place.title = first(uri, "name", "q");
                    // ll alone is a viewport; a labelled pin or explicit coordinate is a destination.
                    String point = path.equals("/frame") ? null : uri.getQueryParameter("coordinate");
                    if (point == null && place.title != null) point = uri.getQueryParameter("ll");
                    place.setCoordinates(point, false, Place.Confidence.EXPLICIT_POINT);
                }
                place.address = uri.getQueryParameter("address");
                break;
            }
            case YANDEX: {
                String route = uri.getQueryParameter("rtext");
                if (route != null) {
                    String[] stops = route.split("~", -1);
                    if (stops.length > 1) place.setCoordinates(stops[stops.length - 1], false, Place.Confidence.EXPLICIT_DESTINATION);
                } else {
                    String point = uri.getQueryParameter("pt");
                    if (point != null && !point.contains("~")) {
                        String[] components = point.split(",");
                        if (components.length >= 2) place.setCoordinates(components[0] + "," + components[1], true, Place.Confidence.EXPLICIT_POINT);
                    }
                }
                place.title = uri.getQueryParameter("text");
                break;
            }
            case WAZE:
                place.title = uri.getQueryParameter("q");
                if ("yes".equals(uri.getQueryParameter("navigate"))) {
                    place.setCoordinates(uri.getQueryParameter("ll"), false, Place.Confidence.EXPLICIT_DESTINATION);
                }
                break;
        }
        return place;
    }

    private static String first(Uri uri, String a, String b) {
        String value = uri.getQueryParameter(a);
        return value != null ? value : uri.getQueryParameter(b);
    }

    public static ArrayList<Place> extract(TLRPC.Message message) {
        LinkedHashMap<String, Place> links = new LinkedHashMap<>();
        ArrayList<Place> result = new ArrayList<>();
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive || media instanceof TLRPC.TL_messageMediaVenue) {
            Place place = new Place(Place.Provider.TELEGRAM, null);
            place.title = media.title;
            place.address = media.address;
            if (media.geo instanceof TLRPC.TL_geoPoint) place.setCoordinates(media.geo.lat + "," + media.geo._long, false, Place.Confidence.TELEGRAM);
            result.add(place);
        }
        String text = message.message == null ? "" : message.message;
        for (TLRPC.MessageEntity entity : message.entities) {
            if (entity.offset < 0 || entity.length <= 0 || entity.offset > text.length() - entity.length) continue;
            String url = entity instanceof TLRPC.TL_messageEntityTextUrl ? entity.url
                    : entity instanceof TLRPC.TL_messageEntityUrl ? text.substring(entity.offset, entity.offset + entity.length) : null;
            add(links, parse(url), message, entity.offset, entity.length);
        }
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String url = matcher.group().replaceAll("[.,;!?)}\\]]+$", "");
            add(links, parse(url), message, matcher.start(), url.length());
        }
        TLRPC.WebPage page = media == null ? null : media.webpage;
        if (page != null) {
            Place parsed = parse(page.url);
            // A preview must belong to this map link, never to the first unrelated URL.
            if (parsed != null) {
                Place place = links.get(parsed.originalUrl);
                if (place == null && links.size() == 1) {
                    Place only = links.values().iterator().next();
                    if (only.provider == parsed.provider) place = only;
                }
                if (place == null && links.isEmpty() && text.isEmpty()) {
                    place = parsed;
                    links.put(place.originalUrl, place);
                }
                if (place != null && !place.spoiler) {
                    if (!place.originalUrl.equals(parsed.originalUrl)) place.resolvedUrl = parsed.originalUrl;
                    if (place.latitude == null && parsed.latitude != null) {
                        place.latitude = parsed.latitude;
                        place.longitude = parsed.longitude;
                        place.confidence = parsed.confidence;
                    }
                    if (place.title == null) place.title = parsed.title;
                    if (page.title != null) place.title = page.title;
                    place.address = page.description;
                }
            }
        }
        result.addAll(links.values());
        return result;
    }

    private static void add(LinkedHashMap<String, Place> links, Place place, TLRPC.Message message, int offset, int length) {
        if (place == null) return;
        for (TLRPC.MessageEntity e : message.entities) {
            if (e instanceof TLRPC.TL_messageEntitySpoiler && offset < e.offset + e.length && offset + length > e.offset) place.spoiler = true;
        }
        Place old = links.get(place.originalUrl);
        if (old == null) links.put(place.originalUrl, place);
        else old.spoiler |= place.spoiler;
    }
}
