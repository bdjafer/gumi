# Realtime gateway protocol v1

The transport is WebSocket at `/v1/realtime` with the single required subprotocol
`gumi.realtime.v1`. The HTTP Upgrade carries one short-lived Bearer credential whose verified claims
bind exactly one deployment binding, physical device, edge host, admission, and session. The credential
principal is the edge host. A general Astrale session, provider key, user ID in a frame, or bearer
forwarded from another audience is invalid here.

One admitted session owns at most one immutable `(turnId, retryId)` pair. `retryId` is the provider
effect identity, not a request counter. Reconnect mints a new `connectionId`; the gateway increments
`connectionGeneration` and fences every older connection. Any non-terminal turn requires the client to
resume with the exact turn, retry, and next sequence. A new turn or retry never resolves ambiguity in a
previous provider effect.

## Sequence

1. Upgrade with the scoped credential and exact subprotocol.
2. Send `client.hello` before the five-second deadline.
3. Send `turn.start`; an exact replay returns the existing state.
4. Send `GRT1` binary Opus frames in exact zero-based order. The gateway verifies the SHA-256 payload
   digest and advances a chained `sequenceDigest` only after the provider port gives a known exact ACK.
5. Send `turn.end` with the gateway's exact final sequence and chained digest, or `turn.cancel`.
6. Treat `turn.result.result.text` only as inert display content. It is explicitly untrusted.

Every audio/control/result frame and every per-turn/session collection has a contract bound. Audio is
never included in control frames, results, status, ordinary logs, or the local session store. Duplicate
audio is acknowledged only when its identity and digest match; conflicting replay, sequence gaps, and
digest mismatches never advance state.

## Provider outcome and recovery

The stable states are `receiving`, `outcome-unknown`, `completed`, `cancelled`, and `failed`; the
contract may expose the bounded transition states `opening-provider`, `sending-audio`, `finishing`,
`cancelling`, and `reconciling` in status. A crash/reconnect that observes one of those transition
records fences it as `outcome-unknown`.

`outcome-unknown` is not failure and is not permission to retry. Further audio and finalization stop.
`turn.reconcile` asks the provider port about the same idempotency identity and may produce completed,
cancelled, failed/not-found, or remain unknown. Cancellation is allowed against an unknown attempt but
does not become known unless the provider confirms it.

## Explicit exclusions

The protocol has no action, tool, query, policy, grant, arbitrary JSON result, raw transcript execution,
batch ingest, or general graph credential surface. A later semantic action system may consume an opaque,
audited proposal, but this gateway cannot authorize or execute it. Provider selection, production
credential verification, persistence, queues, retention, and deployment are outside v1's local
composition and remain production gates.
