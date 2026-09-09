package org.telegram.ui.Cells;

import android.content.Context;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.places.Place;
import org.telegram.messenger.places.PlaceEntry;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/** Links presentation with an immutable source-message boundary for selection and actions. */
public final class SharedPlaceCell extends SharedLinkCell {
    private PlaceEntry entry;
    public SharedPlaceCell(Context context, Theme.ResourcesProvider resources) {
        super(context, VIEW_TYPE_DEFAULT, resources);
    }
    public void setPlace(PlaceEntry entry, boolean divider) {
        this.entry = entry;
        super.setLink(presentation(entry), divider);
    }
    @Override public MessageObject getMessage() { return entry == null ? null : entry.message; }
    public PlaceEntry getEntry() { return entry; }

    public static MessageObject presentation(PlaceEntry entry) {
        TLRPC.Message source = entry.message.messageOwner;
        TLRPC.TL_message display = new TLRPC.TL_message();
        display.id = source.id;
        display.date = source.date;
        display.dialog_id = source.dialog_id;
        display.peer_id = source.peer_id;
        display.from_id = source.from_id;
        display.out = source.out;
        StringBuilder text = new StringBuilder();
        StringBuilder details = new StringBuilder();
        Place first = entry.places.get(0);
        boolean spoilers = false;
        for (Place place : entry.places) {
            boolean hidden = place.spoiler && !entry.message.isSpoilersRevealed;
            spoilers |= hidden;
            if (!hidden) {
                if (details.length() > 0) details.append('\n');
                details.append(place.providerName());
                if (place != first) details.append(" · ").append(place.displayTitle());
                if (place.address != null) details.append(" · ").append(place.address);
            }
            if (place.originalUrl == null) continue;
            if (text.length() > 0) text.append('\n');
            TLRPC.TL_messageEntityUrl entity = new TLRPC.TL_messageEntityUrl();
            entity.offset = text.length(); entity.length = place.originalUrl.length();
            display.entities.add(entity);
            if (hidden) {
                TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
                spoiler.offset = entity.offset; spoiler.length = entity.length;
                display.entities.add(spoiler);
            }
            text.append(place.originalUrl);
        }
        display.message = text.toString();
        TLRPC.TL_messageMediaWebPage media = new TLRPC.TL_messageMediaWebPage();
        TLRPC.TL_webPage page = new TLRPC.TL_webPage();
        page.url = first.originalUrl;
        page.display_url = first.originalUrl;
        page.site_name = first.providerName();
        page.title = first.spoiler && !entry.message.isSpoilersRevealed ? first.providerName() : first.displayTitle();
        page.description = details.toString();
        TLRPC.MessageMedia originalMedia = MessageObject.getMedia(source);
        if (!spoilers && originalMedia != null && originalMedia.webpage != null && first.originalUrl != null
                && (first.originalUrl.equals(originalMedia.webpage.url)
                || first.resolvedUrl != null && first.resolvedUrl.equals(originalMedia.webpage.url))) {
            page.photo = originalMedia.webpage.photo;
        }
        if (!spoilers) page.embed_url = first.imageUrl;
        media.webpage = page;
        display.media = media;
        MessageObject result = new MessageObject(entry.message.currentAccount, display, false, true);
        result.isSpoilersRevealed = entry.message.isSpoilersRevealed;
        return result;
    }
}
