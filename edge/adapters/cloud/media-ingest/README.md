# Edge media-ingest adapter

This module is the portable Android/JVM consumer of the publisher-owned
[`media-ingest` v1 API](../../../../cloud/apps/media-ingest/api/v1/openapi.json). It implements only the
runtime's chunk-upload port; it does not own capture policy, spool state, retries, credentials, or the
cloud contract.

## Exact boundary

`MediaIngestV1DescriptorMapper` independently maps a durable runtime `ChunkDescriptor` into the v1
canonical JSON shape and its RFC 8785 SHA-256 digest. `MediaIngestHttpAdapter` then:

1. rejects payloads above the publisher's 1 MiB hard cap, then checks the exact local byte length and
   SHA-256 digest from one explicit media copy;
2. recomputes the prepared descriptor digest to reject in-process drift;
3. obtains one capture-scoped bearer and UUIDv7 correlation identity through an injected port;
4. sends at most one `PUT` with the exact path, metadata headers, RFC 9530 `Content-Digest`, and body;
5. uses a dedicated client with no inherited interceptors, authenticators, cookies, cache, redirects,
   system proxy, or transparent connection retry, applies one bounded call deadline, and advertises
   both `application/json` acknowledgements and `application/problem+json` failures; and
6. accepts cloud durability only from a bounded JSON acknowledgement whose correlation, ingest,
   stream, chunk, descriptor digest, content digest, range, canonical range partition, and disposition
   all match the prepared upload.

Local mapping, payload, or authorization failures are `NotAttempted`. `Rejected` is deliberately
narrower than "a 4xx was received": the response must have the publisher problem shape and an exact
status/code pair reachable from the v1 `putMediaChunk` operation. A `401` must also carry the one
canonical data-plane bearer challenge. A `429` must carry one canonical `Retry-After` integer equal to
the body's `retryAfterSeconds`; retry metadata on any other response is rejected as contract drift.

A disconnect, timeout, lazy response-body I/O failure, malformed response, oversized response,
mismatched correlation, unlisted status/code pair, or acknowledgement ambiguity is `OutcomeUnknown`.
A valid `503 DURABILITY_UNAVAILABLE` is also `OutcomeUnknown`, never a definitive rejection or durable
acknowledgement. The runtime writes its durable attempt fence before invoking this adapter, so none of
those outcomes causes a blind automatic retry.

Acknowledgement decimal fields must be JSON strings in the publisher's canonical unsigned-64 shape;
numeric JSON primitives, non-canonical or overlapping range partitions (including overlap at
`18446744073709551615`), and contradictory durability snapshots are rejected. Typed problem codes are
trusted only from one `application/problem+json` response that satisfies the bounded publisher problem
shape, repeats the exact request identity and HTTP status, and passes the operation-specific checks
above.

Credentials and media use redacted wrapper types and never enter returned evidence. The endpoint is
HTTPS-only; cleartext is accepted solely for an explicitly enabled loopback test endpoint.

## Evidence and remaining scope

The common tests pin the canonical descriptor to the publisher's golden digest. JVM conformance tests
load the publisher's checked-in descriptor and acknowledgement fixtures, chunk/problem schemas, and
PUT OpenAPI operation through the Gradle-supplied repository root, so independently duplicated edge
assumptions fail loudly when that contract drifts. MockWebServer tests prove the exact request
bytes/headers, stored and duplicate acknowledgements, preflight zero-network behavior, the 1 MiB cap,
dedicated no-proxy transport policy, authorization failure, strict string/number/range shapes, response
bounds/content types, operation-specific problem mapping, disconnects before and during lazy body
reads, and coroutine cancellation. Run:

```sh
./gumiw :edge:adapters:cloud:media-ingest:allTests --console=plain
```

This is not yet a complete edge data-plane client. Status reconciliation, credential delivery/renewal,
session finalization, immutable-manifest handoff, production TLS pinning/identity policy, and Android
runtime composition remain explicit later ports. The cloud application still requires production
credential, metadata/object-store, rate-limit, deployment, and retention adapters. MockWebServer and
the cloud in-memory ports are test infrastructure, not durability evidence.
