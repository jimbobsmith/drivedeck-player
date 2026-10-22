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
Flowable across deletion, unaffected other rows and exact-UID upsert. Runtime
execution requires an explicitly assigned disposable test slot. Build and test
evidence will be recorded on the issue/PR; no personal-device rollout follows
from this patch.
