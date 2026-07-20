# Gumi media ingest

Status: executable v1 contract candidate with a dependency-free Node HTTP adapter. The provider-neutral
core, fixtures, and real-loopback HTTP tests are local evidence; the contract is not frozen or
production-qualified until durable infrastructure, a real credential verifier, deployment, and
end-to-end authorization gates pass.

`media-ingest` is the publisher and authority for Gumi's versioned edge-to-cloud media transfer
contract. Its M1 responsibility is deliberately narrow: authenticate a capture-scoped upload session,
durably accept and deduplicate sequenced bytes, expose exact resume state, and finalize one immutable
media manifest.

It does **not** own Gumi's semantic `Recording`, `Transcript`, `Conversation`, or authorization graph.
Those belong to the `gumi.astrale.ai` application. It also does not transcribe media or execute realtime
AI actions.

## Boundary

```text
gumi.astrale.ai --control credential--> POST /v1/ingest-sessions
                    |                         |
                    +--> POST /v1/ingest-sessions/{id}/credentials
                                              |
                                              v
edge runtime --capture-scoped bearer--> chunk/status/finalize data API
                                              |
                                              v
                         immutable object + manifest ID/digest
                                              |
                edge invokes CaptureSession.finalizeRecording
                                              |
                                              v
gumi.astrale.ai --control credential + expected digest-->
                         GET /v1/manifests/{manifestId}
                                              |
                                              v
                           narrow immutable projection
```

The control credential authenticates the Astrale application that has already authorized the person,
edge host, device, and capture. The returned ingest bearer is short-lived and bound to exactly one
ingest session, capture, device, edge host, stream set, and sequence policy. It is not an Astrale graph
credential and must never be accepted by another cloud application.

Session, credential, and retention clocks are separate. A session may remain resumable for up to 24
hours while each bearer lasts at most one hour. The control caller may re-authorize and issue another
bearer without changing the session or its durable bytes. `retainedUntil` is the earliest cleanup time
for an unfinalized session; a finalized object's retention policy is independent. Credential-bearing
responses require `Cache-Control: private, no-store`.

The reverse integration is deliberately read-only. The Gumi semantic application resolves a manifest
by both `manifestId` and the digest supplied to `CaptureSession.finalizeRecording`; media ingest returns
[`ImmutableManifestProjection`](api/v1/schemas/manifest.schema.json) only when both match. That
projection contains the capture, device, and edge-host identities, opaque object handle, half-open
aggregate sequence range, codec and timing facts, and the primary immutable object's exact SHA-256
content digest, canonical decimal u64 byte length, and content type needed to publish a `Recording`
and later submit those same bytes for processing. It never exposes the chunk ledger, provider storage
identity, object-store URL, or media bytes. Media ingest does not hold graph mutation authority.

The canonical API is [api/v1/openapi.json](api/v1/openapi.json). JSON Schema 2020-12 documents under
[`api/v1/schemas`](api/v1/schemas) own its durable payloads. Consumers generate clients from this
publisher-owned surface; schemas must not be copied into a root `contracts` directory.

[`ChunkDescriptor`](api/v1/schemas/chunk.schema.json) is the normalized golden representation of a
binary chunk request: path IDs, `Gumi-*` metadata headers, the RFC 9530 `Content-Digest`, and the exact
body integrity facts. `Content-Digest: sha-256=:<base64>:` is normalized to `sha256:<lowercase hex>` in
the descriptor. Optional absent headers are omitted; the two discontinuity headers are either both
absent or normalized into `sourceDiscontinuityBefore`. The wire body remains
`application/octet-stream`; it is not a JSON upload envelope.

## Protocol invariants

- IDs are server-accepted, stable UUIDv7 values. Object identity is never derived from wall-clock
  filenames, user labels, or storage paths.
- Every sequence number and byte count is an unsigned 64-bit integer encoded as a canonical decimal
  string. JavaScript number coercion is forbidden.
- Every chunk is addressed by `(ingestSessionId, streamId, chunkId)` and declares an exact inclusive
  encoded-frame sequence range plus a lowercase `sha256:<64 hex>` digest. Sequence scope has a first
  and maximum value; it does not pretend a live capture's final sequence is known at session creation.
- A retry with the same identity, range, length, and digest returns `disposition: duplicate` and the
  same immutable chunk binding. Its range snapshot may be newer if other chunks committed meanwhile;
  `stateRevision` orders snapshots. Reusing an identity with different content or metadata returns
  `CHUNK_DIGEST_CONFLICT` or `CHUNK_METADATA_CONFLICT` and never overwrites the first copy.
- A successful upload response is a **durable acknowledgement**, not request acceptance. It echoes the
  acknowledged chunk identity/body digest and the RFC 8785 descriptor digest, thereby binding all
  path and header metadata. It also names the canonical committed and missing ranges.
- Sparse or out-of-order chunks may be stored so reconnect can make progress. Gaps remain visible in
  status and make finalization fail with `SEQUENCE_GAP`.
- Finalization must declare the exact stream set from session creation, with each `streamId` exactly
  once. Every expected range starts at its policy `first`, covers every durable sequence through its
  declared terminal `last`, and contains no gap. Durable data after that terminal value is a conflict.
- Finalization succeeds only when every expected range, byte length, chunk count, and digest matches
  durable state. It writes the immutable media object and manifest before returning success; media
  ingest never invokes semantic graph mutation.
- Repeating the same finalization request returns the same manifest and manifest digest with
  `disposition: already-finalized`. A contradictory request returns `SESSION_ALREADY_FINALIZED`.
- Raw bytes, chunk ledgers, ingest credentials, signed object URLs, and high-frequency counters stay
  outside Astrale. Astrale receives only stable opaque handles, integrity facts, and semantic state.
- VAD may annotate a durable object later; it never authorizes ingest to discard capture bytes.
- `sourceDiscontinuityBefore` records physical-source loss before a transferred chunk and becomes a
  manifest discontinuity at that chunk's first sequence. It does not excuse missing transfer ranges.
- Manifest digests are SHA-256 over the UTF-8 output of RFC 8785 JSON Canonicalization Scheme. The
  semantic projection preserves that canonical manifest digest; it does not invent a second identity.

## M1 byte profile

`Gumi-Payload-Format` is fixed to `ogg-opus-page-fragment-v1`, and every stream declares the required
`oggLayout` session fact `{ profile, serialNumber, preSkip48kSamples }`. M1 selects only
`gumi.ogg-opus.single-packet-page.v1`: every audio page holds exactly one complete Opus packet, while
one HTTP chunk may batch a bounded contiguous run of those pages. Its inclusive sequence range maps
one-to-one to its audio pages, bounded by `maxChunkBytes`. No packet continues across an HTTP boundary.
The first chunk starts with page 0 `OpusHead` with BOS and page 1 `OpusTags`, followed by one or more
audio pages from page 2. Every later chunk contains one or more audio pages. Sequence values count Opus
audio packets; the two headers are not sequence values.

For audio sequence `s` and policy first sequence `f`, the page sequence is `2 + (s - f)` and the
untrimmed granule is `(s - f + 1) * fixedFrameSamples48k`. The scoped decimal u32 serial is identical
on every page. Only the final audio page may carry EOS; its granule may trim at most its one final
frame, may not precede scoped pre-skip, and remains within the v1 duration bound. These facts make a
sparse chunk independently checkable before durable acknowledgement. Once an EOS sequence is durable,
earlier gaps may still be repaired but no later sequence or earlier contradictory EOS can commit.

The candidate M1 header profile is deliberately narrower than generic Ogg Opus. `OpusHead` is the only
packet data on page zero and is exactly the 19-octet version-1, mapping-family-0 mono/stereo header. Its
channel count and input sample rate equal the scoped codec, and output gain is zero. Its unsigned
pre-skip must equal the session layout fact. `OpusTags` is exactly vendor `gumi`, zero comments, and no
trailing metadata, and finishes on page one. These immutable facts are
validated before a chunk can receive a durable ACK, so invalid header bytes cannot poison a session.

The immutable `audio/ogg; codecs=opus` object is constructed by sorting chunks by inclusive sequence
range and concatenating their exact request-body bytes. There is no remux, metadata rewrite, padding,
or transcoding. Therefore final `byteLength` is the sum of chunk lengths and final `contentDigest` is
SHA-256 of that concatenation. [The assembly fixture](fixtures/v1/success/deterministic-assembly.json)
contains real complete Ogg pages and proves those rules byte-for-byte without a media dependency.

For the primary stream, `startedAt` is the UTC normalization of `sourceStartedAt` on the chunk that
contains the policy's first sequence. `durationMs` is the floor of playable Ogg Opus granule duration
after pre-skip, and `endedAt` is exactly `startedAt + durationMs`. Source-loss wall time is not folded
silently into playable duration; the manifest discontinuity records it separately.

The low-level complete-stream inspector handles the general first-audio-page-is-EOS rules from
[RFC 7845 sections 4.4 and 4.5](https://www.rfc-editor.org/rfc/rfc7845.html#section-4.4). The M1 layout
is intentionally narrower: its granule origin is zero and a terminal first audio page can only trim
within that one frame while remaining at or after pre-skip.

## Status model

An ingest session moves monotonically through:

```text
open -> finalizing -> finalized
  \---------> failed
  \---------> expired
```

`GET /v1/ingest-sessions/{ingestSessionId}/status` returns each stream's canonical merged committed
ranges, immutable codec/layout facts, `terminalSequence`, and an `accountedRange`. Once any chunk is
durable, that range starts at the policy `first` and ends at the greatest sequence observed so far;
committed and missing ranges exactly partition it. This
means a first arrival at `200` truthfully reports the leading gap from policy `first` through `199`.
`durableThrough` is the last sequence in the contiguous prefix, or `null`. Future authorized sequence
values are not reported as missing. `stateRevision` is monotonic; clients ignore lower-revision stale
snapshots and use GET status as authoritative. A session can have durable chunks and still be `open` or
`failed`; status does not erase evidence.

HTTP status is transport-level. Callers branch on the stable problem `code`:

| HTTP | Codes | Meaning |
| --- | --- | --- |
| `400` | `INVALID_REQUEST`, `INVALID_SEQUENCE_RANGE` | The request cannot be interpreted under v1. |
| `401` | `AUTHENTICATION_REQUIRED`, `INVALID_CONTROL_CREDENTIAL`, `INVALID_INGEST_CREDENTIAL`, `INGEST_CREDENTIAL_EXPIRED` | No usable credential was presented. |
| `403` | `CONTROL_SCOPE_MISMATCH`, `SESSION_SCOPE_MISMATCH`, `DEVICE_REVOKED`, `CAPTURE_REVOKED` | The credential is valid but cannot perform this operation. |
| `404` | `INGEST_SESSION_NOT_FOUND`, `STREAM_NOT_FOUND` | The scoped resource does not exist. |
| `409` | `CHUNK_DIGEST_CONFLICT`, `CHUNK_METADATA_CONFLICT`, `SEQUENCE_OVERLAP`, `SEQUENCE_GAP`, `FINALIZATION_STREAM_SET_MISMATCH`, `FINALIZATION_RANGE_CONFLICT`, `REQUEST_ID_CONFLICT`, `INGEST_SESSION_EXPIRED`, `SESSION_ALREADY_FINALIZED`, `MANIFEST_DIGEST_CONFLICT` | Durable state contradicts the requested transition or expected manifest identity. |
| `413` | `REQUEST_BODY_TOO_LARGE`, `CHUNK_TOO_LARGE` | The bounded JSON request or binary chunk is too large. |
| `422` | `CONTENT_LENGTH_MISMATCH`, `CONTENT_DIGEST_MISMATCH`, `FINAL_DIGEST_MISMATCH` | Received bytes do not match declared integrity facts. |
| `429` | `RATE_LIMITED` | Retry only after the supplied delay. |
| `503` | `DURABILITY_UNAVAILABLE` | No durable acknowledgement was made; retry the identical request. |

Problem responses use `application/problem+json` and always include a correlation `traceId`. Logs may
record that ID, stable resource IDs, error codes, and transitions; they must not contain media bytes,
bearer credentials, signed URLs, or transcript content.

Every `401` includes `WWW-Authenticate`. Every `429` includes `Retry-After`, which agrees with the
problem body's `retryAfterSeconds`. A `503` is explicitly not a durable acknowledgement; the caller
retries the identical chunk identity, descriptor, and body.

## Executable core

[`src/core/ingest-service.mjs`](src/core/ingest-service.mjs) is the first provider-neutral executable
implementation of the candidate v1 rules. It validates the normalized payloads, performs exact `BigInt`
range arithmetic, parses and CRC-checks complete Ogg pages, keeps requested and durable state separate,
and produces the checked-in manifest and semantic projection byte-for-byte. It exposes application
methods for session creation, credential refresh, status, chunk commit, finalization, and digest-bound
manifest lookup; it is not an HTTP server.

The core has three injected ports:

- `storage` owns serializable metadata transitions, atomic chunk-metadata-plus-body commit, immutable
  object commit, and manifest lookup. A production implementation must preserve the named commit
  boundaries; returning before those guarantees is a protocol violation.
- `clock` supplies explicit time. IDs and ordering never derive from it inside the core.
- `tokens` allocates opaque UUIDv7 identities and issues capture-scoped credentials. Initial issuance
  is idempotent so a lost create response is recoverable; refresh issuance may leave multiple valid,
  bounded credentials and never revokes a previously returned token.

Finalization is an intentionally resumable protocol: it reserves stable manifest/object identities,
idempotently writes exact immutable bytes, then binds the manifest and terminal session state. A crash
before any boundary leaves no claim of durability; a crash after one is recovered by replaying the same
request. Raw chunk/object bytes live only behind the storage port and are absent from session metadata,
manifests, errors, and logs.

[`src/testing/in-memory-ports.mjs`](src/testing/in-memory-ports.mjs) is deterministic test infrastructure,
not a production database or token issuer. Its before/after-commit failure injection proves retry
semantics at session creation, chunk commit, finalization reservation, immutable object commit, and
manifest commit.

## HTTP adapter

[`createMediaIngestHttpServer`](src/http/server.mjs) routes all six OpenAPI operations over Node's
built-in HTTP server. The adapter has no framework or provider dependency and does not silently compose
test infrastructure: callers must inject a `MediaIngestService`, an `authorizer`, and the exact
principal/audience contract. Persistence, clock, and token issuance therefore remain explicit core
composition choices.

The authorizer implements `authenticate({ bearerToken, signal })`. Server composition must name an
exact `controlPrincipalId` and `credentialAudience`; neither has a permissive default. A successful
control credential returns that exact `principalId` and `audience`, `credentialKind: "control"`, and
`media-ingest:control`. A successful ingest credential returns its bound edge-host `principalId`, the
exact audience, `credentialKind: "ingest"`, `media-ingest:data`, its exact `ingestSessionId`, and its
bound `streamIds`. Returning `null` means invalid. A verifier may throw
`CredentialAuthenticationError` for an invalid/expired credential or `CredentialAuthorizationError`
for the typed `DEVICE_REVOKED` and `CAPTURE_REVOKED` denials. Authentication failure details are owned
by the HTTP adapter; verifier messages and causes are never copied to a response or log. The adapter
independently checks credential class, scope, principal/audience, session, and stream after verification,
so control and data bearers cannot be interchanged. [`InMemoryHttpAuthorizer`](src/testing/in-memory-http-authorizer.mjs)
is only a deterministic test double, not a signature, revocation, Astrale identity, or key adapter.

The wire boundary additionally enforces:

- a 256-KiB default JSON cutoff and 1-MiB default chunk cutoff while streaming, before concatenation;
- mandatory canonical `Content-Length` for binary chunks, exact equality with `Gumi-Payload-Bytes`,
  and rejection of compressed or `Expect` request bodies;
- single-occurrence path/metadata/auth headers and exact RFC 9530 `Content-Digest` normalization;
- strict canonical origin-form targets, with no authority form, query, percent-encoding, dot segment,
  or backslash normalization before route selection;
- byte-preserving `Buffer` transfer into the core, with no base64/UTF-8 media conversion;
- RFC 7807 `application/problem+json` responses, a fresh UUIDv7 `X-Request-ID` on every handled
  attempt, and optional validated UUIDv7 `X-Correlation-ID` echoing;
- `WWW-Authenticate` on every adapter authentication failure and no-store credential responses;
- typed `RateLimitedError` mapping to a validated `Retry-After` header and matching top-level
  `retryAfterSeconds`; and
- allowlisted structured logging only. Raw requests, response bodies, exceptions, bearer values, and
  media bytes are never passed to the logger.

Body reads and credential verification receive disconnect/shutdown cancellation. Once a core method
has started, the adapter deliberately awaits it: a lost client socket cannot prove that a durable
effect did not happen. `close({ gracePeriodMs })` stops admission, drains active work during the grace
period, then aborts remaining request I/O and closes sockets. Its result exposes `drained`,
`abortedRequests`, and `activeRequests`; a nonzero `activeRequests` means an injected core/port promise
is still unresolved and requires reconciliation rather than a false cancellation claim.

Composition is explicit:

```js
const http = createMediaIngestHttpServer({
  service,
  authorizer,
  logger,
  controlPrincipalId: 'gumi.astrale.ai',
  credentialAudience: 'gumi.media-ingest',
})
const { origin } = await http.listen({ host: '127.0.0.1', port: 0 })
// ...
await http.close({ gracePeriodMs: 10_000 })
```

The application `requestId` in create/refresh/finalize payloads and the chunk tuple own protocol
idempotency. `X-Request-ID` and `X-Correlation-ID` are observability identities only and never alter
replay semantics.

## Cross-runtime conformance witness

`fixtures/v1/conformance/edge-muxer-single-packet.hex` is an exact stream generated by the Kotlin
edge muxer. A JVM test regenerates and byte-compares it, while this application's independent Node
parser accepts it and rejects a one-byte corruption before durable acceptance. The fixture therefore
detects producer/consumer drift without sharing parser or muxer implementation code.

## Offline verification

No package installation or network access is required. The integration suite binds only an ephemeral
loopback port:

```bash
npm run verify
```

The contract suite parses every JSON artifact, resolves every OpenAPI `$ref`, checks the schema dialect
and ID / u64 / digest formats, and verifies all checked-in conformance fixtures. The executable suite
then drives the same fixture bytes through the core, including exact output equality, create replay and
conflict, credential bounds, sparse resume state, duplicate/conflict/overlap handling, multi-stream set
equality, terminal replay, raw-byte isolation, and before/after-commit crashes at every durable boundary.
The boundary/type checks import every ESM module, keep the core free of HTTP/provider dependencies, and
prove every OpenAPI operation is explicitly routed. Real-socket tests cover control/data scope
separation, principal/audience and revocation enforcement, session/stream binding, exact binary assembly,
concurrent idempotent upload, streamed body cutoffs, strict raw-target routing, RFC 7807 and rate-limit
responses, log redaction, mutating-effect disconnect/replay, and bounded shutdown.

## Still not selected

The checked-in Node adapter selects a concrete local HTTP transport, but it does not select a production
deployment runtime/ingress, durable metadata store, object store, control/ingest credential verifier or
key provider, rate limiter, queue, region, retention worker, or observability backend. There is no
migration/cleanup/reconciliation operator, load/abuse qualification, TLS/proxy policy, or deployed
service. Those adapters and policies must pass the same boundary/failure suite before deployment.

A production storage adapter must prove atomic metadata/body durability and ordered, immutable object
composition against the declared length and digest; the in-memory port is only the reference oracle for
those semantics. A production authorizer must verify Astrale control credentials and opaque ingest
credentials, expiry, revocation, and exact session bindings without turning log correlation into
authority. The deterministic edge muxer and cloud fixture agree locally,
but physical Omi qualification remains open until the consumer unit supplies evidence that its BLE
values are correctly delimited Opus packets and the assembled object decodes on the target playback
stack. The muxer contract does not infer packet boundaries from arbitrary device notifications. The
edge muxer/page composer and portable upload coordinator are executable primitives. The coordinator
selects an already-durable chunk, persists an exact attempt fence, hands verified opaque bytes to a
provider-neutral port, and applies only an exact normalized durable ACK. Mapping that spool descriptor
into a concrete edge HTTP client and proving this server adapter against production authentication and
persistence remain integration gates.

The old `astrale-ai/lab` Go/GCS applications remain useful implementation evidence, but their public
`uid` upload, timestamp naming, VAD data loss, and gap-derived conversation contract are not compatible
with this API.
