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
of every dialog are queued once for indexing, in batches of 20 dialogs. Auto's
recent seed uses separate `places_local_v1` source markers (2/3), leaving the full
phone backfill markers (0/1) available. Both pending parsing and index reads use
bounded batches; filtered rows cannot stop an index read at its first page.
Global `messages.searchGlobal` URL and geo searches have independent peer/id/rate
offsets. They keep advancing through ordinary links until both date frontiers
pass the twentieth navigable place, or return an empty page. Repeated pages stop
with a retry on reselecting the Places tab; reconnecting refreshes the head.
Search responses use the index epoch to avoid restoring deleted or edited rows.
Short links are resolved in a bounded newest window before being rejected as
non-navigable; fresh message links have a separate priority queue so history
enrichment cannot delay them. A row navigates when tapped and carries one
action, because a list row renders a single secondary action and a second one is
dropped by the host without an error. That action reads a short About summary
assembled only from the message, cached Open Graph metadata, Android Geocoder,
and OpenStreetMap Overpass. Network enrichment obeys
the extended-preview, offline, hidden-dialog, and secret-chat gates. Geocoder and
Overpass results share `places_meta_v1` keys formatted as
`details:<latitude>,<longitude>`; positive entries live for seven days and empty
entries for one day. © OpenStreetMap contributors.

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
metadata. Auto resolves up to 23 newest candidates during its session so new
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
