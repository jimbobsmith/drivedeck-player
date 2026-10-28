# Library imports and browser categories (2026-10-27 review candidate)

Tracking: platform #45, desktop #15, Player #38; parent DriveDeck #244.
Native profiles are integrated in platform PR #44, desktop PR #14 and Player PR #33. This extension is under review, not released or deployed. All field-validation issues remain open.

## Categories and appearance

Categories are named, colored subsets of All Channels, separate from video playlists. A channel may belong to several categories. History, progress, playlists and Watch Later remain shared. PipePipe uses feed groups; desktop uses FreeTube profiles. Active selection, icons and ordering remain local.

The signed-in, unlocked portal adds category creation, rename, colors, membership and deletion. Removing categories keeps global subscriptions. Global unsubscribe removes known memberships. Deferred members waiting for a global subscription are shown separately and retained on rename; removing them requires an explicit checkbox.

Portal Appearance offers System, Light and Dark. System has independent light/day (Paper or Warm) and dark/night (GitHub, Midnight or Dimmed) presets, reacts live to OS changes and defaults to GitHub dark. Appearance stays in that browser. Native import screens use the app's existing theme. Installation/import never changes sync enrollment or opt-ins.

## File support

- Current FreeTube v0.25.3 subscription/profile, playlist and history `.db` exports are UTF-8 NDJSON, not SQLite. A trailing newline is optional; equivalent identified JSON objects/arrays work. Empty files/arrays are no-ops. Older playlists lacking `_id` require conversion through current FreeTube; the unified importer rejects them visibly.
- PipePipe/NewPipe subscription JSON imports canonical YouTube subscriptions. That format contains no categories, history or lists.
- PipePipe full ZIP reads only `newpipe.db`, known ordinary tables/columns, schema versions 1-903: subscriptions, groups/memberships, local playlists, latest watched timestamp per video and progress. Missing older color columns receive defaults. Views, generated/virtual tables, malformed/future schemas fail closed.
- FreeTube Watch Later uses `_id: watchLater`. DD PipePipe recognizes only the special-list UID in `dd_media_sync` row 2. A stock playlist merely named Watch Later remains ordinary. Remote bookmarked playlists without local contents are reported omitted.

Only canonical YouTube IDs and recognized HTTPS content URLs are portable. Other providers/unresolved aliases are reported unsupported; no provider lookup occurs. Imported labels use IDs; metadata hydration remains an existing field gate. Credentials, cookies, settings, downloads and search history do not transfer.

## Use and merge rules

Choose Import exports in the portal, Import library exports in desktop Data Settings, or the new library import entry in Android's existing backup/content settings. Select files, review counts/omissions and explicitly confirm. Cancel leaves the source unapplied. Android accepts custom FreeTube file MIME types through content validation.

Existing category/list names and colors win. Existing progress wins. History keeps the latest timestamp. Lists retain current order and append missing occurrences, preserving duplicates. Imports never delete items or replace the live database. Existing mixed-provider/oversized native collections stay untouched during unrelated imports; an import touching such a collection fails for review.

Identities derive from source IDs, never names. Foreign ddSync IDs, enrollment and local-only fields are ignored. Desktop recognizes its own current local export IDs without changing sync identities. PipePipe group/list identity uses the exact database hash plus UID: the same backup repeats idempotently; a different backup creates separate categories/lists, as disclosed in preview. Browser tombstones stay removed.

Bounds: 20 files / 64 MiB aggregate; 5,000 portable records; 200 occurrences per list; 160 UTF-8 bytes per name; 1,000 changes per confirmation. Source array/table and ZIP limits also apply before normalization. Limits reject visibly, without truncation. Browser parsing times out after 30 seconds. Existing server tombstone/record/log quotas remain; smaller selections may be needed.

## Privacy and recovery

Browser files parse in a disposable local worker. Raw backups never upload; only confirmed encrypted mutations go to the service. Java-serialized `newpipe.settings` is never deserialized. Only the special-list mapping row 2 is examined; enrollment row 1 is not read. Native SQLite is read-only in an owned temporary location, cleaned afterward. Android changes and mappings commit in one Room transaction.

Browser pending v1 remains readable. New v2 batches retain exact encrypted wire bytes across reload/offline/rate limits. Continue sends at most 60 changes per action. Key/owner/generation/epoch mismatches block retry. Conflict choices keep browser, keep server for only this item, or separately discard the remaining batch; accepted changes stay. Export the encrypted batch first when recovery is needed.

Desktop NeDB is not cross-file ACID. A private SQLite journal records approved changes, write intents and acknowledgements. Resume skips acknowledged items and recomputes remaining additive work against current data. Intervening renames/progress remain. If an acknowledged item was deleted or an interrupted write cannot be proved satisfied, Resume stops for fresh review: keep the partial result, discard the unfinished journal, and select exports again for a new preview. Discard never undoes applied records. Synthetic close/reopen and deletion cases cover this boundary; personal-library migration remains unqualified.

## Browser SQLite provenance

The ZIP worker lazily loads locally vendored sql.js 1.15.2 (MIT); no CDN or server runtime dependency. Official tarball: https://registry.npmjs.org/sql.js/-/sql.js-1.15.2.tgz ; documentation: https://sql.js.org/documentation/ . License is beside the assets.

Verified npm integrity: `sha512-ZmMYFsZPZWDKIJUh3A5rdnpRV3hB/r7AijwAzSzLnfv/9CSx4obUmDtA8brAjWJWjGtUVh43nIEJ+6nl6HuOUg==`

SHA-256:
- sql-wasm.js: `b46d4bddfdde8a787fc4764bd0a405cdfdf96fbd77e6a4e95446106826ecfa47`
- sql-wasm.wasm: `0dfeecda2070b36da0cd4f6afa250af9453de528a983770f85b13d5d6b667572`

CSP permits same-origin workers and WASM compilation, without general unsafe-eval. Desktop uses Node 24 SQLite; Android uses existing SQLite/Room. org.json 20240303 is JVM-test-only, not a runtime dependency. Provider/source registry is unchanged: these are operator-selected local exports.

## Evidence and rollout gates

See `import-evidence.json`. Tests cover shipped WASM and native SQLite with DEFLATE descriptor ZIPs, explicit Watch Later mapping, 6,001 dated history observations, NDJSON, quotas, deferred members, encrypted batches and actual NeDB recovery. New Android Room rollback/reimport/mixed-provider tests are compiled only.

Browser visual/worker-upload QA is unverified: two preview-tab attachments timed out despite HTTP/API/assets responding. No alternate control workaround was used. New Android instrumentation, portrait/landscape UI, personal libraries, provider metadata, real cross-device convergence and NAS/private pull/reboot/restore remain open. No shared device or emulator was used.

Last verified private image remains source 9bae1c9, digest `sha256:c1589ea4c6e67c22d571158f533483094a1f61cb636c78f83f078f7dd1ec351f`. It predates profiles/imports. Review and authorized merges, separately approved image/client publication, NAS installation, compatible updates on every client and explicit enrollment/opt-in must precede personal use. No new release/deployment is claimed.

## Room runtime checkpoint, 2026-10-28

All three new LibraryImportRoomTest methods pass on one fresh, isolated AOSP API36 default x86_64 guest at frozen Player39 source 752decc. Actual target app and runner hashes were resolved before install; see `room-runtime-20261028.json`. This proves preview/idempotency, full rollback and preservation of unrelated non-YouTube lists. It does not prove import Activity portrait/landscape UI or personal-library convergence. The bounded campaign consumed 3/30 invocations and is transitioning to the separately tracked real-app E2E task Player #40; personal devices/NAS remain untouched.
