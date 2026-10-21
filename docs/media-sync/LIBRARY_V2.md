# Expanded personal library sync — 2026-10-20

Work items: [platform #31](https://github.com/jimbobsmith/drivedeck-platform/issues/31), [desktop #9](https://github.com/jimbobsmith/drivedeck-desktop/issues/9), [Player #25](https://github.com/jimbobsmith/drivedeck-player/issues/25). Parent: platform #27 / DD #244.

## User behavior

Existing protected enrollment and manual Sync now remain. Select **Include playlists, Watch Later and history** and confirm separately on each device. Existing enrollment never silently enables these categories. Opt-in persists locally; disconnect stops sync and preserves app library data and local playlist identity mappings. Remote copies/backups require the existing operator deletion/revocation controls. No periodic worker, autoplay, active-player seek, queue sync, provider account access or media proxy is introduced.

A list shares its name and ordered canonical YouTube video IDs; duplicate occurrences remain. Stable random UUIDs bind local list IDs without matching titles. Same-named lists are independent. FreeTube's built-in watchLater identity maps to a distinct Watch Later (synced) list in Android. An existing Android list named Watch Later remains an ordinary list. Deleting the Android shared list recreates an empty shared list on the next manual sync; on desktop, removing the built-in list similarly means clearing its items. Watch Later is cleared with an empty put, never a removal tombstone.

History shares membership and the latest observed watch timestamp per canonical video, matching FreeTube's per-video model. It does not mirror every Android dated event, transfer repeat counts, or claim another view. Android retains its dated rows/counts and imports a zero-count observation. An older received observation cannot erase a newer local view; a causally acknowledged follow-up publishes that newer timestamp. This is a greatest-observed-event rule only for history. Resume retains explicit conflict handling and deliberate rewind support. Explicit history removal deletes that video's history on peers, leaving resume/playlist data independent. Deletions concurrent with a new view require conflict resolution.

Concurrent playlist rename/reorder/removal uses the existing revision/conflict UI. No wall-clock winner, union that destroys order, or name-based merge is used. Both clients keep local intent and exact durable wire bytes through outages and retries. Imported unknown videos show a minimal Synced video label; ordinary client-side loading can hydrate details. Titles/thumbnails, provider credentials, signed/resolved stream URLs, local paths and local stream UIDs are not shared. Playlist names and history times are newly opted-in encrypted data.

## Version and record contract

The v1 authenticated envelope, routing HMAC identities, key material, enrollment format and API paths stay compatible. Three new kinds have plaintext **version: 2**:

- playlist: kind, provider=youtube, version=2, id=UUID, name, items=[video IDs].
- watch-later: kind, provider=youtube, version=2, id=watch-later, items=[video IDs].
- history: kind, provider=youtube, version=2, id=video ID, watchedAtMs=positive integer milliseconds.

Every opted-in HTTP request carries x-dd-sync-library: 2. Requests without that capability continue v1 behavior until an extended record is committed. Thereafter the service rejects incapable clients with 409 library_upgrade_required **before** reads/writes; no old cursor skips new kinds and no unreviewed migration rewrites old ciphertext/pending requests. Update the service and all clients before enabling an expanded library. A new client that has not opted in also receives that actionable error. Existing v1 pending requests retain their exact bytes and idempotency IDs.

Bounds remain deliberate: 200 video occurrences/list, name <=160 UTF-8 bytes, existing 4 KiB ciphertext, 16 KiB request, 1,000-intent outbox, 5,000 total server records including tombstones and 10,000 log entries. Empty lists are valid. Unsupported/mixed-provider identities, oversized lists/names, malformed/unknown fields or versions fail visibly before network publication; nothing is silently truncated. Large-library paging, pruning and retention are separate work. A list exceeding these prototype limits blocks manual sync until corrected; per-list exclusions/chunking are not included.

## Persistence and test boundary

FreeTube stores fork-owned ddSyncId/ddSyncOrigin fields alongside existing NeDB playlist data. The existing SQLite projection journal coordinates local writes and remote cursor recovery; it is not a cross-file ACID claim. Copied lists receive independent identities. Android uses a second row of the existing dd_media_sync table for non-secret local UUID-to-UID mapping; row 1 remains enrollment-specific client state. Disconnect clears only row 1. No Room schema/dependency/extractor migration is needed. Mapping updates and projections stay inside the enclosing Room transaction.

Regression evidence and build/runtime results are recorded in library-v2-evidence.json. Cross-client synthetic evidence uses actual NeDB handlers and the production Room adapter against a dedicated database on task-owned DD_Library_Sync_API36. It does not qualify personal libraries, provider playback, protected enrollment UI, physical phone/Fire/TV operation, or hosting. Existing provider/DD live-control authority is unchanged. Hosted HTTPS, security acceptance, restore reconciliation and retention remain open under platform #27.

No merge, publication, personal-device deployment or hosting is part of this implementation authority. Each fork-local PR is a review candidate; remaining device/provider gates stay open.

## Disposition / resume

Parked pending maintainer review on codex/issue-31-library-sync (platform), codex/issue-9-library-sync (desktop) and codex/issue-25-library-sync (Player), with linked open issues and PRs. Resume by reviewing the fork-local PRs and the recorded evidence; integrate the compatible service before enabling expanded clients. Merge only with explicit maintainer authority. Keep the remaining field gates open. Scratch worktrees contain committed review candidates, not an alternate integrated product line.
