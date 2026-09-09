package org.telegram.messenger.places;

import org.telegram.messenger.MessageObject;
import java.util.ArrayList;

/** One source message, potentially containing multiple map destinations. */
public final class PlaceEntry {
    public final MessageObject message;
    public final ArrayList<Place> places;
    public PlaceEntry(MessageObject message) {
        this.message = message;
        places = PlaceExtractor.extract(message.messageOwner);
    }
    public String key() { return message.getDialogId() + ":" + message.getId(); }
}
