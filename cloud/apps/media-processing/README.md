# Gumi media processing

Status: executable v1 contract candidate. The provider-neutral core, publisher-owned OpenAPI/JSON
Schemas, golden artifacts, a dependency-free Node HTTP adapter, and deterministic failure suite run
offline. A dependency-free, fixture-driven ElevenLabs Scribe v2 webhook normalizer now proves one
candidate provider-to-artifact trust boundary without making a request or handling a secret. No
provider, queue, durable database, production artifact store, credential verifier, TLS ingress, or
deployment is selected or claimed.

`media-processing` owns one narrow remote invariant: an immutable, digest-bound media input may produce
an idempotent processing job, a bounded history of leased attempts, and at most one immutable derived
artifact for that job. Reprocessing uses a new request/job and preserves the earlier provenance; it
never rewrites an existing artifact.

It does **not** own a Gumi `Recording`, `Transcript`, `Conversation`, `VoiceTurn`, or action. Those are
semantic authorities of the `gumi.astrale.ai` application. It does not ingest raw audio, mutate the
Astrale graph, execute transcript text, or grant a provider broad application authority.

## Boundary

```text
gumi.astrale.ai --caller-scoped control credential-->
    create job(manifestId + expected manifest digest)
                        |
                        v
      manifest reader resolves immutable media facts
      (object handle + input content digest; never caller supplied)
                        |
                        v
worker --worker credential--> claim / renew bounded attempt lease
                        |
                        v
configured provider adapter stages transcript artifact bytes
                        |
                        +-- provider + job + attempt callback binding -->
                        |       bounded digest/provenance/failure facts
                        v
        immutable artifact store + terminal job commit
                        |
                        v
gumi.astrale.ai --control credential + expected input/output digests-->
        read-only derived-artifact projection
                        |
                        +-- same owner + same two digests -->
                            bounded immutable transcript pages
```

The canonical HTTP candidate is [api/v1/openapi.json](api/v1/openapi.json). JSON Schema 2020-12
documents under [api/v1/schemas](api/v1/schemas) own all published values. A consumer generates a
client from this application-owned surface; it must not copy these schemas into a global contracts
directory or import this application's state/storage implementation.

## Authority separation

Three actual principals are supplied by trusted adapters, never accepted from request-body identity
fields:

- a control principal may create/read/retry/cancel only its own jobs and resolve results only with both
  expected input and output digests;
- a worker principal carries an adapter-derived pipeline allowlist, may atomically claim only matching
  queued work, and may renew only its own current lease; and
- a provider-callback principal is bound to one configured provider, one existing job, and one attempt.

Control, worker, and callback credentials are mutually non-interchangeable. A callback cannot invent a
job, select another input, change the pipeline, publish a semantic Transcript, or mutate a conversation.
A worker receives an app-scoped opaque media handle only in its no-store lease response; the handle
alone is not read authority, and the media adapter must recheck the authenticated live lease. Credentials,
signed URLs, provider request/response bodies, transcript content, and raw audio are absent from job
state, result projections, errors, and ordinary logs.

## State and retry model

```text
queued --claim generation N--> running --reserve/commit--> completing --success--> succeeded
   ^                               |--retryable failure--> retryable --explicit retry--+
   |                               |--permanent/exhausted failure--> failed
   +--explicit retry after expiry--+

queued / running / retryable / outcome-unknown --cancel--> canceled
```

Every caller request, callback, job, attempt, lease, and artifact has a stable identity. Request replay
with identical immutable facts converges to the first committed result; identity reuse with different
facts fails and never overwrites durable state. Terminal request replays are resolved before consulting
the clock or allocating another identity, and completion recovery reuses an existing reserved artifact
identity. A worker lease replay also rechecks the caller's current pipeline allowlist before returning
the opaque media handle. Attempt `generation` and lease `revision` reject stale workers, callbacks, and
renewals.

A bounded provider `retryAfterSeconds` becomes `retryNotBefore` on the failed attempt and is enforced
before explicit retry. It is advisory only within the hard attempt budget; it can never create an
unbounded retry loop.

A lease timeout is deliberately **not** treated as provider failure. The provider effect may have
happened even though no callback committed, so status projects `outcome-unknown`. Another worker cannot
claim automatically. A control caller must retry with `acknowledgeOutcomeUnknown: true`, accepting the
risk of a duplicated external effect, before the next generation can exist. A late callback is rejected;
it never launders an old provider result into a newer attempt.

Cancellation stops future work but is not deletion. Canceling an in-flight attempt records
`providerOutcomeUnknown: true`, because a remote provider may still finish. Later callbacks cannot
change the canceled job. Retention and deletion must separately cover source media, derived bytes,
provider copies, job records, callbacks, logs, and semantic projections.

The candidate defaults are three attempts, a 120-second lease, and a 64-MiB immutable artifact maximum;
the constructor permits bounded test/deployment policy changes. There is no unbounded worker retry.

## Immutable transcript artifact

Successful callbacks carry only an app-owned staging handle, output length/digest/type, and bounded
provenance aggregates. The artifact port independently verifies the staged bytes before a completion
reservation can commit, then verifies them again while making the final object immutable. This avoids
locking a job to a malformed provider payload and closes the mutation race between validation and
commit. The executable oracle decodes the exact bytes as fatal UTF-8, and the published decimal-string
limits are ordinary JSON Schema constraints rather than generator-specific extension metadata.

The core also binds reported media duration back to the resolved immutable input and requires
`basis: requested` language results to equal the pipeline's language hint. A provider cannot manufacture
plausible but unrelated provenance around otherwise valid staged bytes.

[transcript-artifact.schema.json](api/v1/schemas/transcript-artifact.schema.json) defines the first
derived format. Segment text is explicitly untrusted content. It can later be shown to a person or
passed as user/data context to an authorized model flow; it is never interpreted as a system prompt,
policy, graph query, tool grant, action request, or executable instruction. The control projection
contains only:

- input manifest/content digests;
- output artifact digest, bounded byte length, content type, and opaque app handle;
- pipeline/configuration identity;
- provider, model, and model-version identity;
- attempt and generation identity;
- language basis, provider timing, media duration, and aggregate segment facts; and
- immutable commit time.

Transcript content crosses a separate owner-scoped read boundary. `readTranscriptPage` rechecks the
job owner plus both immutable digests, revalidates the committed artifact bytes and aggregate facts,
and returns at most four contiguous segments with an exact half-open range. Four is deliberately small:
even maximally escaped v1 text stays inside the HTTP adapter's default 1 MiB response envelope. An empty
artifact has exactly the page at index `0`; a continuation index is present only when another page
exists. Speaker labels remain provider evidence and are never resolved into people or authority.

## ElevenLabs Scribe v2 evaluation adapter

[`normalizeScribeV2Webhook`](src/integrations/elevenlabs/normalize-scribe-v2.mjs) is deliberately a pure
normalizer, not a provider selection or webhook server. It consumes one already-authenticated
`speech_to_text_transcription` envelope plus the adapter's expected provider request, processing job,
attempt, generation, configuration digest, and media duration. All four webhook metadata fields must
match that pre-existing lease binding exactly.

The normalizer accepts only the currently documented `word`, `spacing`, and `audio_event` records,
preserves provider spacing without inventing spaces, keeps prompt-like text inert, and emits the
canonical transcript artifact. Consecutive speech from one provider speaker becomes one bounded
segment; audio events retain `kind: audio-event`. Cross-speaker temporal overlap is rejected because
the current canonical artifact cannot represent concurrent tracks without falsifying time order.
Unknown fields/types, malformed or out-of-media timing, ambiguous spacing, oversized input, aggregate
text drift, and binding substitution all fail closed.

The checked-in docs-derived input and normalized output live under
[`fixtures/providers/elevenlabs`](fixtures/providers/elevenlabs). Before a network adapter exists, add
captured sandbox fixtures under explicit consent and pin them to the provider/model/configuration
tested. Current provider behavior and request options must be rechecked against the official
[Scribe v2 API](https://elevenlabs.io/docs/api-reference/speech-to-text/convert) and
[asynchronous webhook guide](https://elevenlabs.io/docs/eleven-api/guides/how-to/speech-to-text/batch/webhooks).

The checked-in artifact store is test infrastructure. It retains fixture bytes behind the port so the
suite can prove that prompt-like transcript text never appears in control metadata or projections.

## Executable core and durability boundaries

[MediaProcessingService](src/core/media-processing-service.mjs) is a dependency-light application
core, not an HTTP server. It uses five injected ports:

- `storage` provides serialized metadata transactions plus job/request reads;
- `clock` supplies explicit time;
- `ids` allocates opaque UUIDv7 identities;
- `manifestReader` resolves and verifies a digest-bound immutable input rather than trusting a caller
  object handle; and
- `artifacts` validates app-staged transcript bytes and idempotently commits an immutable derived
  object.

A successful callback crosses three resumable boundaries: completion reservation, immutable artifact
commit, and terminal job/result commit. A crash before a boundary makes no claim about it. A crash
after any boundary is recovered by replaying the exact callback ID and facts. Once a valid completion
is reserved, cancel/retry are rejected until that completion converges, and the exact replay may finish
after the worker lease deadline. This prevents an already-committing artifact from racing a retry into
a second provider effect.

[In-memory ports](src/testing/in-memory-ports.mjs) inject failures before and after each boundary and
are an executable oracle only. They are not production persistence, authentication, scheduling, or
artifact storage.

## HTTP adapter candidate

[createMediaProcessingHttpServer](src/http/server.mjs) exposes the nine publisher-owned OpenAPI
operations over a real Node HTTP server without introducing another contract. It enforces canonical
origin-form v1 routes, exact methods, bounded UTF-8 JSON bodies and responses, no compressed request
bodies, and exact path/body identity agreement. Result resolution requires exactly these two unique
query parameters (in either order, with a raw or normally percent-encoded digest colon):

```text
?expectedInputContentDigest=sha256:<64 lowercase hex>&expectedOutputContentDigest=sha256:<64 lowercase hex>
```

Control, worker, and provider-callback credentials require distinct configured audiences, distinct
credential kinds, and distinct scopes. The injected verifier derives the control caller, worker ID
and pipeline allowlist, or provider/job/attempt callback binding; none are accepted as authority from
request JSON. The checked-in in-memory authorizer is test infrastructure, not a signature verifier,
issuer, audience registry, revocation system, or deployment choice.

Every response is `no-store`, has an independently generated UUIDv7 `X-Request-ID`, and echoes only a
valid caller-supplied UUIDv7 `X-Correlation-ID`. Failures use the existing publisher-owned RFC 7807
Problem schema, so arbitrary exception/provider messages and request bodies never cross the boundary.
Error objects cannot supply wire headers: the adapter synthesizes only its bounded `Retry-After`,
`WWW-Authenticate`, and `Allow` values, while response security and request/correlation identity
headers are invariant.
Logs are an allowlist of operation, validated resource IDs, status/code, timing, and effect phase; they
never include URLs, query digests, credentials, bodies, staged handles, transcript content, provider
payloads, or arbitrary exception strings.

Disconnect is explicit: authorization and body reads are canceled before core entry. Once an
idempotent mutating core operation starts, the adapter lets it settle and sends no false acknowledgement
to a disconnected caller; replaying the same stable request or callback identity is the recovery path.
Graceful shutdown stops admission and drains bounded work. When its grace period expires, it signals
request cancellation and closes the transport; an injected adapter that ignores its `AbortSignal`
cannot be forcibly settled by JavaScript, so `close()` reports the request as active and
`drained: false` instead of claiming cancellation. It also cannot cancel an already-entered provider/storage
effect, so production operations still require reconciliation around the core's durable boundaries.

## Stable failures

HTTP status is transport-level; clients branch on the stable problem `code`. Important groups are:

| Status | Representative codes | Meaning |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | The normalized request violates v1. |
| `401/403` | `AUTHENTICATION_REQUIRED`, `CONTROL_SCOPE_MISMATCH`, `WORKER_SCOPE_MISMATCH`, `CALLBACK_SCOPE_MISMATCH` | No valid principal or the principal is outside its narrow authority. |
| `404` | `PROCESSING_JOB_NOT_FOUND`, `ATTEMPT_NOT_FOUND`, `RESULT_NOT_FOUND` | The caller-scoped resource does not exist. |
| `409` | `REQUEST_ID_CONFLICT`, `GENERATION_CONFLICT`, `ATTEMPT_SUPERSEDED`, `ATTEMPT_LEASE_EXPIRED`, `OUTCOME_UNKNOWN_ACK_REQUIRED`, `COMPLETION_IN_PROGRESS`, `RESULT_DIGEST_MISMATCH` | Durable or time-bound state contradicts the requested transition. |
| `413/422` | `ARTIFACT_TOO_LARGE`, `ARTIFACT_INTEGRITY_MISMATCH` | Derived bytes exceed the hard bound or do not match their digest/schema/aggregate facts. |
| `503` | `INPUT_MANIFEST_UNAVAILABLE`, `ARTIFACT_STAGE_NOT_FOUND`, `DURABILITY_UNAVAILABLE` | No new completion is claimed; retry the same stable request after reconciliation. |

Problems expose a correlation `traceId` but never embed provider error messages or payloads. Provider
failures are reduced by the adapter to a finite app-owned retryable/permanent code vocabulary and an
optional bounded retry delay; arbitrary provider strings are rejected.

## Offline verification

No package installation or external network access is required on Node 22+; the HTTP suite opens only
ephemeral loopback sockets:

```bash
npm run verify
```

The suites prove schema/OpenAPI reference integrity, contract/core byte equality, caller/worker/provider
scope, concurrent claim serialization, stable replay/conflict, lease renewal and expiry, explicit
outcome-unknown retry, cancellation, bounded attempts/artifacts, late/foreign callback rejection,
immutable provenance, malformed transcript rejection before reservation, content isolation, and crash
recovery after every durable completion boundary. Digest-bound transcript tests cover empty, terminal,
continued, malformed, and foreign-caller pages while keeping content out of control metadata and logs.
Real-socket HTTP tests cover all nine operations,
credential non-interchangeability, canonical routing/media/query rules, bounded streams and responses,
RFC 7807 failures, contained error headers, rate limiting, redacted allowlisted logs,
disconnect/replay, and graceful shutdown including an uncooperative-adapter witness.
The boundary suite also forbids another cloud app's internals, provider SDKs,
network/filesystem/process APIs, and undeclared dependencies in the core.

## Explicit production gates

This candidate does not yet select or implement:

- a production credential verifier/issuer, key rotation/revocation store, or distributed rate limiter;
- TLS termination, trusted proxy policy, ingress hardening, or an HTTP deployment;
- a scheduler/queue and worker deployment;
- durable serializable job/request storage and migrations;
- a media-ingest manifest reader adapter with scoped authorization;
- an allowlisted pipeline/provider registry that constrains which control callers may select each
  provider, model, version, language, and configuration digest;
- immutable staging/object storage, encryption, retention, deletion, or regional policy;
- provider callback signature/token verification and one-time attempt binding;
- a selected transcription/diarization provider, consented evaluation corpus, captured sandbox
  fixtures, model/option commitment, or pricing/privacy decision;
- observability, dead-letter/reconciliation operators, load/abuse tests, or deployment; or
- the Astrale Transcript/Conversation schema and the separately authorized publication step. The page
  API is only a least-authority content source for that future importer; it does not publish graph data.

Production adapters must pass this same fault suite plus real-store concurrency/crash tests. No local
green result is evidence of deployed transcription, live Astrale publication, or M1 completion.
