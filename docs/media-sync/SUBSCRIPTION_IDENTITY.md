# Exact subscription identity (issue #34)

Canonical channel URLs are identities, including the case-sensitive public
channel ID. SQL `LIKE` interprets `_` as any one character and normally ignores
ASCII case. It can match a different valid channel when a literal channel URL
is passed as the pattern.

The four global subscription identity predicates now use `url = :url` with the
existing `service_id` constraint: one-shot lookup, observed lookup, targeted
unsubscribe and the internal UID lookup used by upsert. Name search retains its
existing `LIKE` behavior. No schema, URL normalization or dependency changes.

`SubscriptionIdentityTest` uses real in-memory Room with two distinct canonical
channel IDs differing only at an underscore/letter position, a case-distinct
ID and the same URL on another service. It checks exact reads, a retained
Flowable across deletion, unaffected other rows/profile memberships and exact-UID
upsert. It also verifies that name search retains its existing pattern behavior.

Source `8de7c5c851a44ee63bbd04d337e740aa4fdc12dc`, based on integrated
schema-903 main `e7692ff51b2f6d9037bf5dd608c5adccac7f7c73`, passes the full
app/instrumentation APK build, all 50 JVM tests across 8 suites and the
195-coordinate runtime dependency audit. All five selected instrumented tests
pass on a fresh disposable API 36 emulator: the three identity regressions and
existing profile subscription-action/deletion compatibility tests. The owned
emulator was shut down and its processes exited; no personal/shared device was
operated. [Curated evidence](subscription-identity-evidence.json) records exact
source and artifact hashes. This is a local Test build, not a release.

The patch awaits maintainer review and explicit merge authority. Related profile
HTTP integration, hosted rollout, personal-library migration and physical field
gates remain open on #17/#32; this database regression proof does not close them.
