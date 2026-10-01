# Places

Places is a local shared-media tab beside Links, including in empty chats. Each
row represents one source message. Repeated sends stay separate; multiple map
links in one message share a row. Rows use Links' month sections, selection and
URL opening. Telegram locations open LocationActivity.

The shared `org.telegram.messenger.places` package exposes source messages,
providers, original/resolved URLs, metadata and optional coordinates. Coordinates
carry a confidence value; UNKNOWN is not a navigation destination. Google camera
centers, Yandex `ll`, and Apple frame centers are not place coordinates. Routes
retain their original URL and only explicit endpoints are navigation candidates.

Android Auto exposes the 20 newest navigable places across the account in a
dedicated Places tab: Telegram geo, venue and live-location messages as well as
map links, including both received and own sent messages. New messages and live
location edits update the list without opening a phone chat. Read-only broadcast channels are excluded by the reader,
which tells a channel from a supergroup; the stored channel flag covers both, so
it is not a filter. Hidden dialogs, deleted private users,
spoilers, and non-navigable entries are excluded too. So that the tab is not
limited to dialogs opened on the phone, the newest 100 cached messages per source
of every dialog active in the last 60 days are queued once for indexing, in batches of 20 dialogs. Auto's
recent seed uses separate `places_local_v1` source markers (2/3), leaving the full
phone backfill markers (0/1) available. Both pending parsing and index reads use
bounded batches; filtered rows cannot stop an index read at its first page.
Global `messages.searchGlobal` URL and geo searches have independent peer/id/rate
offsets. They keep advancing through ordinary links until both date frontiers
pass the twentieth navigable place, return an empty page, or reach 10 pages (500
results) since the last head reset. Repeated pages stop with a retry on
reselecting the Places tab; reconnecting refreshes the head. Search responses use
a deletion epoch (row 2 of `places_epoch_v1`, moved only by deletions, history
clears and edits, never by ordinary message inserts) to avoid restoring deleted or
edited rows: a page that loses the check is requested again after 1, 2 and 4 s; after that the cursor advances and
only messages the device stores are indexed, while that page's search-only rows
are dropped. A message the device already stores is always queued for the local
drain rather than written from the server copy. URL and geo searches each run as
separate private-chat and group streams, so broadcast channels are never requested.
Short links are resolved in a bounded newest window before being rejected as
non-navigable; fresh message links have a separate priority queue so history
enrichment cannot delay them. A row navigates when tapped and carries one
action, because a list row renders a single secondary action and a second one is
dropped by the host without an error. That action (About) opens a place card: the
name as title, then up to the host's pane row limit of type and rating, address,
today's opening hours, a short description and the sender with the cleaned note
(description, then hours, type and address are dropped first; the sender row
stays). The picture is the Wikipedia photo, else the map-link preview, else the
sender avatar. Navigate is the primary action; Read aloud/Stop speaks a summary:
place name, category and rating, address and opening hours (OSM syntax is
converted to speech or skipped), then the sender and age. URLs, coordinates and
spoiler text are never spoken; a caption with its links and spoilers removed is
only a trailing note, and a link-only caption has none. Rows never show a URL as
their title. Fixed wording is spoken in English; names, addresses, the sender and
the note are spoken by the voice of their own language. The on-device ML Kit
language id (the one used by translation) names the message language, and the
alphabet of each fragment decides when detection is missing or disagrees, so a
Cyrillic name is never spelled by an English voice. A voice that is not installed
falls back to English.

Sources, each only filling fields still missing: the map-link Open Graph preview
(Google share links: name, address, rating, category); for Google query/search
links, whose preview is a generic card, the coordinates at the center of the
preview's static map (zoom 15+ only; the home card is a wide region around the
requester); Nominatim; Wikipedia, when OSM tags the place with `wikipedia` or
`wikidata` (Wikidata sitelinks pick the message-language article, summary API
gives the description and photo); and the platform Geocoder for the address.

Data leaving the device for About: only an About tap starts enrichment. Visible
rows are prefetched from the local `places_meta_v1` cache only. On a cache miss
the tap sends the coordinates to the platform Geocoder (when the address is
unknown) and, in parallel, to OpenStreetMap Nominatim
(`nominatim.openstreetmap.org`): a search for the venue or map-link name within
about 250 m when one exists, else a reverse lookup at the point. Answers use the
message language. Requests are spaced at least 1.1 s apart and identify the app,
per the Nominatim usage policy; 429/5xx pause the service for five minutes.
Overpass was dropped: its public server regularly took over 4 s or returned 504.
Wikipedia/Wikidata receive only the article name or id from OSM, and the card
picture is fetched from the Wikipedia or link-preview URL (bounded to 2 MB,
downsampled to 480 px). The card picture follows the same gates (never for live locations, restricted messages, secret chats or hidden dialogs). Message text is never sent. Live
locations, restricted messages, secret chats and hidden dialogs never leave the
device, and extended previews must be enabled and the device online. Each
service has a 4 s timeout and the car waits 6 s; a late answer is cached for the
next tap. Results share `places_meta_v1` keys formatted as
`details3:[<language>:]<latitude>,<longitude>[|<place name>]` (per message language and searched venue name, since several venues can share a point; older versions are ignored); positive entries live for seven days and empty
entries for one day. © OpenStreetMap contributors.

The account-wide reader pages `places_v1` by the `(date, mid, uid)` keyset over
the `places_recent_v1` index, which is created idempotently on the first Auto
session for databases that reached version 180 before it existed, before the
first ordered read. Global search pauses on FLOOD_WAIT and restarts from the head
on reconnect at most once a minute. Nothing in Places uses the network until the
Places tab is first shown in the session, and neither do index reads or the
account-wide seed and drain; the seed skips broadcast channels, which the car never lists. One refresh
reads at most 5 index pages (600 rows), loads link previews only for those pages'
URLs, and publishes once (enough rows or the last page), so the list never
shrinks to a first page mid-refresh. New messages are indexed immediately but
refresh the list debounced, and only after Places was shown. Only one drain chain
and one index read run at a time; a refresh requested during a read runs after it
publishes. Refreshes are debounced by 250 ms with a 1 s maximum wait, and they no
longer interrupt the drain. Messages still being sent (temporary negative ids) are never indexed; the stored copy arrives through the insert trigger. A short-link
preview whose fetch failed, or landed on a non-map page (captcha, consent), is kept
in memory only and retried, never persisted or replaced by the empty result.
Only edits of place messages the device does not store move the deletion epoch. Card pictures are scaled to at most 320 px.

History uses separate URL and geo `messages.search` cursors for each dialog. The
merge selects the newest unscanned frontier and continues over pages without map
links. A repeated non-advancing page is an error with retry; only an empty server
page ends a stream. Topics use `top_msg_id`; Saved Message dialogs use
`saved_peer_id`. Migrated history uses its original dialog ID. Reopening reads the
local index, resumes older cursors, and refreshes the head for new messages.

Account SQLite version 183 adds `places_v1`, pending source keys, history cursors,
metadata and an epoch. The 179 -> 180 migration preserves all earlier migrations;
180 -> 181 resets only Places cursors after the first pagination correction;
181 -> 182 clears only the optional metadata cache so failed previews retry;
182 -> 183 resets only Places cursors after removing an invalid `max_id` boundary.
Idempotent triggers observe message and topic inserts, edits and deletions; local
backfill is seeded lazily per opened dialog and parsed in batches on the storage
queue. Deletion call-outs also remove search-only records. Responses started
before an intervening write/delete cannot commit stale rows. Indexed data keeps
the original serialized message. Metadata is optional, cached for seven days,
and read by the repository as well as the UI.

On the phone, only visible rows and the next three rows request external
metadata. Once its Places tab has been shown, Auto resolves up to 23 newest candidates so new
destinations can become navigable without opening a phone chat. Both use the
existing HTTP utilities and external-preview cache. Extended previews must be
enabled. Secret chats use local history and never automatically fetch metadata;
spoiler links do not request it while hidden. Emergency-hidden dialogs and saved
sources are excluded. Protected messages retain normal forwarding restrictions
and do not offer URL copying from Places.

## Validation

Automated tests cover provider/domain recognition, short URLs and redirect
metadata, hidden links and captions, mixed messages, invalid coordinates, route
endpoints versus map centers, sparse history, merged frontiers, overlapping and
repeated pages, topic/Saved scope fields, idempotent schema creation, message and
topic lifecycle triggers, and independent account databases.

Device checks use an in-place Foldogram Beta upgrade:

- Open an empty chat profile: Places is present and has a clear empty/loading or
  retry state. Reopen offline and verify cached places remain available.
- In a chat with all four map providers and Telegram geo/venue/live messages,
  verify month grouping, newest-first ordering, search and loading older results.
- In a mixed message, tap each map URL and the row; the unrelated preview must
  never open. Long-press a URL for Open/Copy and a row for selection. Test multiple
  selection, forwarding, permitted deletion and jump to one source message.
- Edit/remove a map link, update live location and clear history; verify the index
  refreshes. Test a topic, Saved Message dialog and migrated group history.
- Verify spoilers and protected messages; secret chats must not fetch external
  metadata. Check hidden-chat behavior and confirm the Android Auto Places tab
  shows avatars, opens navigation, and reads About summaries with Stop toggling.
- With the phone chat unopened, send a Telegram geo/venue/live location and a
  short map link from another account while Auto is connected. Verify each
  appears at the top, opens the correct destination, and live updates change the
  destination. Repeat with a place sent before connecting and an own sent place.
- Verify an older map survives more than 50 ordinary URL results and more than
  120 newer filtered index rows. Reconnect after an offline interval; new places
  must appear while the cached list stays usable.

API references: [Telegram message search](https://core.telegram.org/method/messages.search),
[Telegram global search](https://core.telegram.org/method/messages.searchGlobal),
[Google Maps URLs](https://developers.google.com/maps/documentation/urls/get-started),
[Apple unified URLs](https://developer.apple.com/documentation/mapkit/unified-map-urls),
[Yandex sharing](https://yandex.com/support/maps/en/concept/get-map-reference),
[Waze links](https://developers.google.com/waze/deeplinks).
