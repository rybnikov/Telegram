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
Existing Android Auto code is unchanged.

History uses separate URL and geo `messages.search` cursors for each dialog. The
merge selects the newest unscanned frontier and continues over pages without map
links. A repeated non-advancing page is an error with retry; only an empty server
page ends a stream. Topics use `top_msg_id`; Saved Message dialogs use
`saved_peer_id`. Migrated history uses its original dialog ID. Reopening reads the
local index, resumes older cursors, and refreshes the head for new messages.

Account SQLite version 180 adds `places_v1`, pending source keys, history cursors,
metadata and an epoch. The 179 -> 180 migration preserves all earlier migrations.
Idempotent triggers observe message and topic inserts, edits and deletions; local
backfill is seeded lazily per opened dialog and parsed in batches on the storage
queue. Deletion call-outs also remove search-only records. Responses started
before an intervening write/delete cannot commit stale rows. Indexed data keeps
the original serialized message. Metadata is optional, cached for seven days,
and read by the repository as well as the UI.

Only visible rows and the next three rows request external metadata, using the
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
  metadata. Check hidden-chat behavior and confirm Android Auto still opens.

API references: [Telegram message search](https://core.telegram.org/method/messages.search),
[Google Maps URLs](https://developers.google.com/maps/documentation/urls/get-started),
[Apple unified URLs](https://developer.apple.com/documentation/mapkit/unified-map-urls),
[Yandex sharing](https://yandex.com/support/maps/en/concept/get-map-reference),
[Waze links](https://developers.google.com/waze/deeplinks).
