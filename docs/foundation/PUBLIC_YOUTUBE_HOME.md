# Public YouTube home for DD

Refs [Player #36](https://github.com/jimbobsmith/drivedeck-player/issues/36) and
[DD #275](https://github.com/jimbobsmith/drivedeckg/issues/275).
Baseline: Player main 70217ec0. Extractor c68e10e2 unchanged.

Integration: maintainer-approved PR #37 merged to main at
f29609571f7a8f17a2ffa54e89c16b894870219c; companion DD PR #276 merged to master
at 497d878781ad7cddd0f12f02439084f2fee91368. Fetched remote merge trees match
the reviewed heads (Player cc7db638, DD 17727e82). This is integrated source,
with no accompanying release or installation. #36 remains open as
field-validation for the actual cross-app/provider/device gates below.

The maintainer requested a useful public YouTube home inside DD without adding
subscriptions or history. The exported PublicYouTubeBrowserService serves only
the pinned default YouTube kiosk (currently Recommended Lives), public search
and short-lived continuations. DD renders the grid in its own home.
This reuses extraction; it does not introduce a new decoder or change playback.

## Boundary

Discovery inspects only the selected known Player package's declared services
for exact metadata presentation youtube-public-v1. The exported service declares
no intent filter; DD binds that component explicitly through MediaBrowser.
It is intentionally absent from ordinary media/Android Auto discovery, including
older DD versions. Existing playback service discovery remains unchanged.
This follows Android's [explicit bound-service guidance](https://developer.android.com/guide/components/intents-filters#Receiving). Roots are home, a
URL-encoded search: query (1..200 characters), or a server-issued page: UUID.
No arbitrary URL, private library, history, subscription, account or playback
operation exists here. Each page contains at most 23 public video rows plus one
More continuation. Video rows have zero playable/browsable flags; DD explicitly
hands their canonical eleven-character IDs to its existing Watch/Audio route.

Root binding and every load require the OS-reported package to belong to the
calling UID and pass the signer policy. Release DD com.drivedeck.g uses the
verified official SHA-256:
29d743afecc02f012121ad06759abac5fcab5812c5d0af01a4258346495f05c0.
Debug Player additionally permits DD official/Test identities with the exact
nonempty signer set of that debug Player. Unrelated packages and mismatched
signers are rejected. Player's own UID is permitted for local inspection.
Signer rotation is deliberately not inferred and requires a reviewed update.

The inactive, zero-action MediaSession token exists only because MediaBrowser
requires a token. This service constructs no Player/decoder/foreground service,
acquires no audio focus and reads no Room library. It never activates its token
or publishes Playing. It adds no permission or dependency and leaves the
extractor/source pin, upstream attribution and standalone tabs unchanged.

Only safe public titles/channels, canonical IDs and HTTPS i.ytimg.com or
img.youtube.com artwork leave the process. Provider Page objects, cookies and
resolved/signed media URLs do not. Extraction retains normal fork network
configuration; this is not an independent privacy certification of upstream.

## Lifetimes and failures

At most two extraction loads are pending. Pages cap raw item count at 500.
Continuations live only in memory, belong to one UID, expire after three minutes,
and retain at most 16 entries. Successful continuation loads are memoized;
failed loads may retry. Expired, unknown or foreign tokens fail without fetching.
Completed work is removed from the disposable set. Destroy closes the store,
rejects late registrations and disposes Rx work.

Malformed requests, unavailable provider/network and busy responses produce a
bounded status row. DD times out its own wait, unsubscribes, and offers retry;
a browser connection is not a claim that YouTube is reachable.
The endpoint is local to the device running DD; audio-host selection is separate.
An older Player lacks the endpoint and DD must explain that update requirement.

## Evidence and acceptance

PublicYouTubePolicyTest covers caller spoofing/signers, debug scope, request
bounds, canonical IDs, text and safe thumbnail hosts. PublicBrowsePagesTest
covers owner UID, expiry, eviction, stable success/retry and close during a load.
On 2026-10-26, JDK 25.0.4.1+1 / SDK 37 / Gradle 9.5.1 passed:

    :app:compileDebugKotlin :app:testDebugUnitTest
      --tests org.schabi.newpipe.drivedeck.*
      :app:writeFoundationRuntimeAudit
      --no-configuration-cache --no-daemon --max-workers=1

Nine tests passed, zero failed/skipped. The 195-coordinate resolved runtime
graph passed the forbidden-services audit (SHA-256
2a4b448cd29dd47eb523f6c35da85a2032d567c94f33ae304309fcff178ff19b).
Generated-manifest inspection confirmed exported explicit-only service metadata,
no intent filter and no change to source permissions. Final local build log
SHA-256: fbe4b1b9509f5993812117e4fa8970a36998f1322248f3b3871e0c89484cc299.
Scratch logs remain outside Git.

Real compatible-app binding, public Home/search/paging, portrait/landscape,
offline/retry and repeated opening are still provider/runtime gates. Confirm
no playback/session/audio-focus change during browse, and explicit phone audio
with paired picture separately. No device install, emulator run, signing,
publication, merge or personal-data changes are implied by compile evidence.
