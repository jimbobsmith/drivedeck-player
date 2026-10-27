# Subscription profiles (issue #32)

Paired work: `jimbobsmith/drivedeck-desktop#12`,
`jimbobsmith/drivedeck-platform#43`; parent `jimbobsmith/drivedeckg#244`.

Profiles have the same meaning as FreeTube: named subsets of the global
**All Channels** subscription library. Watch history, resume positions,
playlists and credentials are not separate accounts or private per-profile
stores. History remains shared.

## Using profiles locally

The existing PipePipe subscription groups become Profiles. Existing groups,
icons, sort order and memberships are preserved. Select a card to choose the
active profile and open its feed; the subscription channel list, channel
subscribe button and existing feed tabs follow that selection. All Channels
shows the complete library. Active selection is local to this installation,
persists across restart and falls back to All Channels if its profile is deleted.
Long-press a profile to edit its name, subscriptions, icon or RGB colors, or
delete it. Empty profiles are supported. Profile editing offers channels from
the global library, including channels not yet in this profile.

Subscribing while a named profile is active adds the channel to that profile
and to All Channels if needed. Unsubscribing with the channel button while
a named profile is active removes only that membership. Removing a channel
from All Channels removes it from every profile. The profile channel menu
retains a separate explicit global Unsubscribe action. Deleting a profile
keeps all global channels and shared watch history.

Room migration **902 → 903** adds two non-null color columns with defaults
`#333333` and `#FFFFFF`; no existing group or subscription is deleted. A 901 →
903 migration regression fixture also checks names, sort order and membership.
Opening a 903 database with an older app is unsupported; preserve normal
library backups before upgrade. This work does not enable sync automatically.

## Optional encrypted synchronization

Import enrollment as before, then explicitly choose **Enable profile sync**
and confirm the owner-wide compatibility change. This is independent of the
playlist/history opt-in. Sync remains manual; no background worker is added.
Update the service first. Once profile data has been shared, v1/v2 clients
cannot read or mutate this owner's library until profile-capable software is
installed and opted in. The current browser library editor is also blocked;
its account/enrollment/export tools remain available. This is called out in
the confirmation rather than silently stranding other enrolled clients.

Payloads use the existing authenticated AES-GCM envelope and protocol 1:

* `profile`: provider `youtube`, version `3`, UUIDv4 `id`, UTF-8 `name` up to
  160 bytes, RGB `bgColor` and `textColor`.
* `profile-member`: provider `youtube`, version `3`, `profileId`, canonical
  `channelId` (`UC` plus 22 URL-safe characters), composite `id` equal to
  `<profileId>:<channelId>`.

Fields are exact. Unknown/private fields, bad identities and malformed colors
are rejected. Snapshot colors normalize to uppercase. Metadata and individual
memberships have separate causal revisions and conflict handling. Profiles
can contain more than 100 channels: membership is not packed into one bounded
record. Existing service/outbox quotas still apply (5,000 owner records,
1,000 queued mutations, 4 KiB encrypted envelope); quota failures are visible
and never silently truncate a profile.

Portable UUID mapping lives in local sync row 3 and survives disconnect.
Missing-global memberships are deferred in row 4 across pagination/restart;
they do not silently subscribe a global channel. The real group membership is
materialized only when the global subscription arrives. Explicit global
unsubscribes clear deferred membership and win over stale member puts. A known
profile tombstone suppresses stale concurrent member puts. Profile deletion
never deletes global subscriptions. Non-YouTube channels and memberships stay
local, and applying YouTube membership mutations does not replace them.
Colors and names are encrypted; service routing reveals the record kind, as
with previous sync categories. Playback, provider credentials and device-local
active profile selection are never transferred.

## Evidence and remaining gates

`ProfileRecordRulesTest` covers UTF-8/name limits, control characters, exact RGB
values and bound canonical membership identity without Android dependencies.
`MediaSyncProfilesTest` contains real Room cases for 103 channels, same-name
distinct identities, deferred before-global/metadata delivery and adapter
restart, profile delete plus stale concurrent member, global unsubscribe,
non-YouTube preservation, disconnect mapping and strict schema rejection.
The existing migration test now verifies the complete 901 → 902 → 903 chain.

The exact source `4c63f85a0dcc3df7223f046ded6b4e76c028c1cc` passes the full app
and instrumentation APK build, all 50 JVM tests across 8 suites (4 new profile
validation tests) and the 195-coordinate runtime dependency audit. All 12
selected instrumented tests pass on a newly created disposable API 36 emulator:
11 profile tests plus the complete 901 → 903 migration. This includes a retained
Room observer receiving subscribe/remove/resubscribe updates, existing feed
view-model selection propagation, and production profile editor/opt-in controls
in portrait and landscape. These are assertions on synthetic data, not a manual
visual review or a personal-device claim.

Public synthetic vectors produced by the real desktop crypto implementation
decrypt and match routing keys in the real Android implementation, including a
Unicode name. Android then seals counterparts; the production desktop decryptor
and routing-key generator verify both profile and member payloads in reverse.
No test key or identity is suitable for enrollment or real user data.

Curated source, build and test evidence is in [profiles-evidence.json](profiles-evidence.json).
The owned disposable emulator was shut down and its processes exited. No
personal phone, tablet, TV or shared emulator was operated. Full profile HTTP
round trips through the Android client and desktop/service, personal-library
upgrade, manual visual review, physical devices and hosted deployment remain
explicit field-validation gates. Profile sync stays disabled by default.

## Integration and import follow-up (2026-10-27)

Native profiles are integrated through Player PR #33, desktop PR #14 and platform PR #44. The last published private image predates profiles. The [browser/import extension](IMPORTS.md), Player #38 / desktop #15 / platform #45, is under review. Device and personal-library gates remain open.
