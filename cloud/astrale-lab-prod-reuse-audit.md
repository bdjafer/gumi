# `astrale-ai/lab` `prod` cloud reuse audit

Status: source-audited on 2026-07-19; no lab code imported.

## Verdict

Do not mirror the three legacy Omi services into Gumi and do not use their HTTP or storage shapes as
Gumi contracts. Gumi already has stronger ownership boundaries in
[`media-ingest`](apps/media-ingest/README.md),
[`media-processing`](apps/media-processing/README.md), and the
[`gumi.astrale.ai`](apps/gumi/README.md) semantic application. The lab branch remains valuable as:

1. a source of failure cases that Gumi must explicitly prevent;
2. evidence that Silero VAD deserves a separately pinned evaluation behind a replaceable port; and
3. a historical ElevenLabs payload/provider experiment from which to derive current fixtures, not
   source code or a production adapter.

There is no realtime speech service, realtime agent/action service, or formal OpenAPI/JSON Schema
contract in the audited branch. `omi-audio-streaming` is a request/response PCM chunk uploader despite
its name and its `websocket-server` Go module name.

The first implementation candidate was therefore a pure, fixture-driven ElevenLabs Scribe v2 result
normalizer inside `media-processing`; it now exists without a network call, secret, or copied lab code.
The completed local boundary and its remaining evaluation gates are specified below.

## Audited source and reproducibility

The `prod` branch of [`astrale-ai/lab`](https://github.com/astrale-ai/lab/tree/prod/apps) resolved to
commit [`89dd56d4fa3a10915d56862314300666e005dee5`](https://github.com/astrale-ai/lab/tree/89dd56d4fa3a10915d56862314300666e005dee5/apps)
when cloned for this audit. It contains 57 files and these three Go applications:

| Application | Actual responsibility | Runtime dependencies |
| --- | --- | --- |
| [`omi-audio-streaming`](https://github.com/astrale-ai/lab/tree/89dd56d4fa3a10915d56862314300666e005dee5/apps/omi-audio-streaming) | Public HTTP upload of assumed PCM16 mono bytes, optional VAD, WAV wrapping, GCS write | Go 1.22.1, GCS client, `silero-vad-go`, ONNX Runtime 1.16.3, moving Silero model download |
| [`omi-conversation-processor`](https://github.com/astrale-ai/lab/tree/89dd56d4fa3a10915d56862314300666e005dee5/apps/omi-conversation-processor) | Scheduled GCS listing, silence-gap grouping, WAV concatenation, ElevenLabs submission | Go 1.24, GCS, IAM Credentials, Cloud Scheduler/Run |
| [`omi-transcript-webhook`](https://github.com/astrale-ai/lab/tree/89dd56d4fa3a10915d56862314300666e005dee5/apps/omi-transcript-webhook) | ElevenLabs callback, SRT formatting, GCS mutation, best-effort outbound webhooks | Go 1.22.1, GCS, public Cloud Run ingress |

The source contains no `LICENSE`, `COPYING`, or `NOTICE` file, no `*_test.go` file, and no checked-in
CI workflow. [GitHub's licensing guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)
states that without a license the default copyright rules apply and reproduction, modification, and
derivative works are not granted. Therefore no lab-owned source may be copied unless the copyright
owner explicitly confirms Gumi's right to do so or the lab repository receives a suitable license.
If both repositories are under the same legal owner, record that internal authorization before
extraction; it does not remove the engineering provenance and quality gates below.

This is an engineering provenance finding, not legal advice. The audit is static: cloned service code
and newly downloaded dependencies were not executed because they are not qualified for this workspace.
Build status therefore remains unverified, and the branch itself supplies no test suite to execute.

## Dependency and licensing gates

| Dependency surface | Source state | Gumi decision/risk |
| --- | --- | --- |
| Lab-owned Go, Terraform, Docker, and documentation source | No declared repository license | Do not copy until an owner grant or repository license is recorded. A third-party dependency's license does not cover the lab glue around it. |
| `cloud.google.com/go/storage` 1.45.0/1.50.0 and IAM client | Versioned Go modules with a large transitive graph | Do not inherit merely because they compile. They lock the adapter to Go/GCP while Gumi has not selected that deployment. Any future GCP adapter needs its own dependency/SBOM/license/vulnerability review. |
| `silero-vad-go` 0.2.1, ONNX Runtime 1.16.3, and Silero model | Upstream projects are permissively licensed, but the model is fetched from moving `master` without a digest and current upstream compatibility has advanced | Evaluate current upstream releases independently; pin every artifact and checksum. Do not reuse the lab dependency set or Dockerfile. |
| ElevenLabs speech-to-text/webhooks | External API/service rather than redistributable lab software | Requires a current provider contract, DPA/privacy/region/retention review, quota and price gates, restricted secret, and quality corpus. Historical request fields are not a stable library. |
| `golang:*-bullseye`, `debian:bullseye-slim`, `apt`, downloaded ONNX archive/model | Mutable image tags and build-time network fetches; no image/archive/model digest or SBOM | Reject the images unchanged. A production build needs pinned image digests, verified artifacts, non-root runtime where possible, SBOM/provenance, and update policy. |
| OpenTofu Google provider 6.50.0 | Provider lock hashes are checked in | The lock is the strongest supply-chain element in the branch, but the modules still expose the wrong authority/secret/data-retention boundary. Re-author infrastructure rather than copying it. |

## Decision matrix

`Extract library` below means a small independently owned unit with its own tests and provenance. It
does not mean copying an unlicensed file. `Adapt behind port` means retain the behavior or provider
idea while Gumi owns a new contract and implementation.

| Lab asset | Decision | Reason and Gumi destination |
| --- | --- | --- |
| All three deployable services unchanged | **Reject** | Their identities, authentication, durability, and ownership boundaries contradict Gumi. No lab-owned source currently qualifies for unchanged reuse. |
| `/audio?uid=...` uploader and time-derived GCS object names | **Reject** | It is public and unauthenticated, reads an unbounded body, ignores the requested sample rate, assumes PCM16, permits uncontrolled path components, and can collide within one second. Gumi's `media-ingest` already owns capture-scoped credentials, immutable UUID identities, exact digests, durable acknowledgements, resume, and finalization. |
| PCM-to-WAV header helpers | **Reject for M1** | Gumi's M1 media object is deterministic Ogg Opus assembled without transcoding. A future PCM profile should use a tested media/container component and a publisher-owned profile, not duplicate these fixed constants. |
| Silero VAD concept and upstream model | **Adapt behind port** | Evaluate the independently MIT-licensed upstream project behind a provider-neutral VAD port. Do not import the lab wrapper or let VAD discard accepted capture truth. VAD may annotate or derive a view after durable ingest. Pin the model, runtime, checksum, architecture, license, and accuracy corpus. |
| Ten-second VAD discard policy | **Reject** | The source drops the whole chunk when its computed speech ratio is not above 20 percent and emits no discontinuity or durable evidence. This can erase short speech and cannot define a `Recording` or `Conversation`. |
| Two-minute silence-gap grouping | **Adapt behind port, later** | It may become one tested `ConversationProposalPolicy`, but never semantic truth or byte ownership. Explicit capture boundaries remain authoritative; a person or authorized Astrale policy accepts proposed grouping. Use stable media timestamps/durations rather than filename wall clocks. |
| GCS `unprocessed/` scan, WAV concatenation, and copy-then-delete queue | **Reject** | It has no lease, request identity, generation precondition, transaction, or durable reconciliation receipt. It loads all chunk bodies into memory in the active path and treats object movement as work completion. `media-processing` already owns jobs, attempt leases, outcome-unknown recovery, immutable artifacts, and provenance. |
| GCS signed URL idea | **Adapt behind port** | A provider worker may resolve a Gumi object handle to one short-lived, attempt-scoped read capability. The URL must remain outside graph state/logs and cannot use the hard-coded `astrale-lab` service account from the lab source. |
| ElevenLabs request integration | **Adapt behind provider port** | Preserve only the provider candidate and asynchronous correlation idea. Current official documentation uses `scribe_v2` and `source_url`; the lab pins `scribe_v1` and deprecated `cloud_storage_url`. Model, version, language, diarization, retention, and all options must be bound by Gumi's pipeline/configuration digest. |
| Lab webhook HMAC implementation | **Reject** | Verification is deliberately bypassed when the secret is absent, accepts arbitrarily future timestamps, has no durable delivery/request deduplication, and is not bound to a pre-existing Gumi attempt. Use the current provider SDK's raw-body verification or a separately tested verifier, then mint the narrow Gumi callback principal. |
| ElevenLabs word/payload shape | **Extract a fixture-backed normalizer** | The current `data.transcription.words` envelope and `word`/`spacing` records are useful provider evidence. Normalize them into Gumi's immutable transcript artifact under strict bounds; provider JSON and text never become control data. Derive fixtures from current official docs and a captured sandbox result, not from the lab example. |
| SRT formatter | **Reject as canonical; reconsider as exporter** | It joins every record with an added space, does not model `spacing` or audio-event records, and validates neither time order nor numeric bounds. Gumi's canonical output is ordered transcript segments. A later SRT export can be a pure library with conformance fixtures. |
| Post-response endpoint dispatcher | **Reject** | The goroutine has no outbox, retry, idempotency key, delivery receipt, or tenant-scoped secret. With request-based Cloud Run CPU it is explicitly unsafe to depend on background work after the response. Add a notification-delivery application only when external subscriptions are a real use case. |
| Terraform/GCP modules unchanged | **Reject** | They use mutable `latest` image tags, public unauthenticated ingress, environment/Terraform-state secrets, broad `objectAdmin`, a hard-coded project service account, no lifecycle on conversation data, and platform-specific state. Reuse only independently reviewed infrastructure patterns after Gumi selects a deployment target. |

## Source findings that prevent direct reuse

### Media and identity mismatch

The upload handler in
[`main.go`](https://github.com/astrale-ai/lab/blob/89dd56d4fa3a10915d56862314300666e005dee5/apps/omi-audio-streaming/main.go)
reads the complete request body, treats it as little-endian 16 kHz mono PCM, writes a temporary WAV,
and addresses it as `unprocessed/{uid}/{local-second}.wav`. It does not authenticate `uid`, constrain
its storage-key syntax, bind a device/capture/edge host, accept a caller request ID, verify a digest,
or return a durable content acknowledgement. Two uploads for one user in the same second target the
same identity; concurrent users also share one local temporary filename.

That is not a compatible fallback for Gumi's Omi path. The edge runtime already retains the hardware's
Opus frames and creates the deterministic Ogg Opus profile required by `media-ingest`; decoding to PCM,
discarding with VAD, wrapping as WAV, and re-uploading would lose codec provenance and the exact
restart/resume identity.

The VAD wrapper in
[`vad.go`](https://github.com/astrale-ai/lab/blob/89dd56d4fa3a10915d56862314300666e005dee5/apps/omi-audio-streaming/vad.go)
also contains inconsistent thresholds: a `vadThreshold` of 0.3 is stored but unused, the detector's
probability threshold is 0.5, and acceptance uses a hard-coded speech ratio above 0.2. The Dockerfile
downloads ONNX Runtime by version but downloads the Silero model from a moving `master` URL without a
checksum. Current `silero-vad-go` documentation expects newer ONNX/model versions than this snapshot.
The upstream [Silero VAD](https://github.com/snakers4/silero-vad),
[`silero-vad-go`](https://github.com/streamer45/silero-vad-go), and
[ONNX Runtime](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) are permissively licensed,
but those upstream licenses do not license the lab glue code or make its unpinned build reproducible.

### Processing is a storage scan, not a durable job

[`processor.go`](https://github.com/astrale-ai/lab/blob/89dd56d4fa3a10915d56862314300666e005dee5/apps/omi-conversation-processor/processor.go)
lists every `unprocessed/{uid}` prefix, parses identity and order from Paris-local filenames, and
declares a group closed based on the worker's current clock. The active aggregation path in
[`aggregator.go`](https://github.com/astrale-ai/lab/blob/89dd56d4fa3a10915d56862314300666e005dee5/apps/omi-conversation-processor/aggregator.go)
loads all WAV bodies into one byte slice. It then writes audio, performs the external provider request,
writes mutable metadata, and copy/deletes source objects without a durable intent/receipt protocol.

Failure after any one of those steps can leave a provider request with no metadata, an output with
source chunks still visible, partially moved chunks, duplicate provider effects, or later metadata
overwrites. GCS versioning does not supply the missing job state machine. Its conversation duration is
also the difference between first and last chunk timestamps, so it excludes the last chunk's playable
duration.

`media-processing` already models the correct seam: immutable digest-bound input, a configured
pipeline, bounded attempts and leases, explicit outcome-unknown acknowledgement, staged/committed
artifact bytes, provider provenance, and digest-bound result/page projections. Provider code must fit
that seam rather than replace it.

### The provider experiment has already drifted

The submission code in
[`transcription.go`](https://github.com/astrale-ai/lab/blob/89dd56d4fa3a10915d56862314300666e005dee5/apps/omi-conversation-processor/transcription.go)
hard-codes `scribe_v1`, `cloud_storage_url`, a one-hour signed URL, and an
`omi-conversation-processor-sa@astrale-lab.iam.gserviceaccount.com` signer. The configured
`ELEVENLABS_WEBHOOK_URL` is stored on the Go object but is never sent or otherwise used by the request.

Current official ElevenLabs documentation:

- demonstrates asynchronous speech-to-text with `scribe_v2` and workspace-configured
  [transcription webhooks](https://elevenlabs.io/docs/eleven-api/guides/how-to/speech-to-text/batch/webhooks);
- marks `cloud_storage_url` deprecated in favor of `source_url` in the
  [speech-to-text API](https://elevenlabs.io/docs/api-reference/speech-to-text/convert); and
- documents SDK-backed raw-body HMAC verification and idempotent webhook handling in the
  [webhook guide](https://elevenlabs.io/docs/eleven-api/resources/webhooks).

The branch's webhook payload envelope is directionally useful, but its operational configuration is
not self-contained: merely injecting the callback URL into the processor does not create/configure the
provider's workspace webhook.

### Callback and privacy boundary are unsafe

[`webhook.go`](https://github.com/astrale-ai/lab/blob/89dd56d4fa3a10915d56862314300666e005dee5/apps/omi-transcript-webhook/webhook.go)
fails open when `ELEVENLABS_WEBHOOK_SECRET` is absent, reads an unbounded body, logs the full raw
transcript, trusts callback metadata to form storage paths, writes mutable metadata without a
generation precondition, and has no durable replay record. It accepts a timestamp if it is not older
than 30 minutes but imposes no upper bound, so a valid signature over a future timestamp extends the
replay window.

Outbound fan-out is launched in a goroutine only after the transcript and metadata writes. The Cloud
Run module sets `cpu_idle = true`, while Google's
[Cloud Run guidance](https://docs.cloud.google.com/run/docs/tips/general#background-activity)
says request-based services should finish asynchronous operations before responding because CPU can
be disabled or severely limited afterward. Even if CPU were always allocated, an in-memory goroutine
would not be a durable delivery queue.

The infrastructure passes provider secrets through Terraform variables and plain container
environment values, grants both processing services `roles/storage.objectAdmin`, publishes ingest
and webhook services to `allUsers`, retains upload data for 365 days, and defines no lifecycle for the
versioned conversations bucket. These choices are especially unsuitable for ambient audio and
transcript data without an explicit data-region, encryption, retention/deletion, audit, and provider
privacy policy.

### Documentation is not executable evidence

The branch documents mutually incompatible behavior: two-minute versus five-minute scheduler
cadences; 256 MiB versus 512 MiB processing memory; `tag_audio_events`/`diarize` values opposite to
source; and an "immediate" webhook response even though GCS transcript and metadata operations occur
synchronously. The example webhook payload is not wrapped like the handler requires, and its local
curl example points to the Markdown document rather than a JSON fixture. The absence of tests and CI
means those statements are not qualified evidence.

## Gumi ownership mapping

| Concern exposed by the lab prototype | Gumi owner | Rule |
| --- | --- | --- |
| Device-to-cloud bytes and immutable media manifest | `cloud/apps/media-ingest` | Keep capture-scoped auth, exact digests, resume, and durable finalization; no VAD discard. |
| Batch transcription job and provider attempt | `cloud/apps/media-processing` | Provider adapters consume leased attempts and produce staged immutable artifacts; they do not create semantic recordings. |
| Device, CaptureSession, Recording, Transcript, Conversation | `cloud/apps/gumi` | Only authorized Astrale functions publish semantic graph truth from narrow immutable projections. |
| Silence/speaker segmentation suggestion | `media-processing` derived artifact first; later `gumi` acceptance | An algorithm proposes; an authorized semantic action accepts or rejects. |
| Realtime voice/AI turn | future `realtime-gateway` app | The audited lab branch has nothing reusable here. Do not force batch HTTP/webhook code into the realtime boundary. |
| Third-party transcript delivery | future notification-delivery app if demanded | Durable subscription, outbox, signature, retry, tenant policy, and receipt—not a post-response goroutine. |

## Incremental integration plan

### 0. Resolve legal and product gates

- Record who owns copyright in `astrale-ai/lab` and whether Gumi may copy or modify it. Until then,
  behavior may be studied but source must not be imported.
- Decide whether ElevenLabs is only the first adapter candidate or an M1 provider commitment. The
  provider decision needs current price, region/data-retention terms, zero-retention eligibility,
  supported languages, diarization quality, service limits, and deletion behavior.
- Keep VAD out of ingest correctness. Define whether its first product use is a UI annotation,
  processing optimization, or optional conversation proposal.

### 1. Provider evaluation corpus and pure normalizer — local boundary landed

Create a small consented corpus from synthetic and owned audio covering short utterances, silence,
overlap, background speech, speaker changes, French/English, disconnections, and the exact Gumi Ogg
Opus profile. Record word error rate, diarization error rate, timestamp monotonicity, empty/audio-event
behavior, latency, provider bytes retained, and cost.

The dependency-free docs-derived fixture, exact normalized artifact, normalizer, and failure suite now
live at these paths:

```text
cloud/apps/media-processing/
├── fixtures/providers/elevenlabs/scribe-v2-transcription-completed.json
├── fixtures/providers/elevenlabs/scribe-v2-normalized-artifact.json
├── src/integrations/elevenlabs/normalize-scribe-v2.mjs
└── tests/elevenlabs-scribe-v2-normalizer.test.mjs
```

The executable normalizer now:

- accept only the current, bounded `speech_to_text_transcription` envelope;
- require the provider request binding supplied by the adapter, never a caller identity from payload;
- preserve `word`, `spacing`, and supported audio-event semantics without inventing spaces;
- reject non-finite, negative, reversed, overlapping, or out-of-duration timestamps;
- bound record count, text code points, speaker labels, language values, and total artifact bytes;
- emit the canonical `gumi.media-processing.transcript-artifact.v1` shape with exact aggregate facts;
- treat every transcript string as inert content; and
- prove malformed, oversized, unsupported-overlap, payload-rebinding, and prompt-like text cases.

Its six focused tests prove deterministic replay, exact request/attempt binding, spacing preservation,
prompt-text inertness, bounded malformed input, unsupported overlap, and ambiguous-spacing rejection.
Replay and foreign-attempt behavior still belongs to the later durable callback boundary, not to the
pure normalizer; that adapter's tests must prove those separately. A consented evaluation corpus and
captured provider sandbox fixture remain open.

This is the exact next implementation candidate because it establishes the provider-to-Gumi trust
boundary without selecting infrastructure, executing an external effect, or handling a secret.

### 2. Add provider submission behind a narrow worker adapter

After the evaluation gate passes, add an ElevenLabs worker integration under `media-processing`, not a
new semantic app. It consumes one authenticated attempt lease, resolves the input `objectHandle`
through a least-authority media reader to a short-lived read capability, and submits `source_url`,
`scribe_v2`, and the exact options represented by the immutable pipeline configuration digest.

Persist an attempt-scoped submission intent and provider request receipt. If the network outcome is
unknown, preserve that state and use the existing explicit acknowledgement before any retry. Never log
the source URL, API key, provider body, transcript, or arbitrary provider error. Store the API key in a
deployment secret manager with a narrow provider scope and quota.

### 3. Add the callback adapter and artifact staging

The public provider endpoint must bound the raw body before parsing, require a configured secret,
verify the raw-body signature and both sides of clock skew, rate-limit, and bind the delivery to one
already-leased Gumi attempt. Deduplicate against a stable callback/provider request identity before
staging normalized bytes. A valid provider signature authenticates ElevenLabs; it does not grant
authority to choose a different job, Recording, Transcript, or caller.

Stage the canonical artifact, compute digest/length/segment facts locally, then invoke the existing
provider-callback completion boundary with the attempt-bound callback credential. Return success only
after the durable callback receipt and completion state converge. Reconciliation handles a crash at
every intent, provider request, staging, and completion boundary.

### 4. Select deployment infrastructure after the adapter passes fault tests

Whichever platform is selected must use immutable image digests, secret references, tenant/data-region
policy, least-privilege object access, durable queue/worker execution, retry/dead-letter/reconciliation,
retention/deletion across source/provider/artifact/log data, and audited observability. Lab's GCP
modules may inform the resource inventory but should not be copied.

## Explicit non-goals of this audit

- No lab, GCP, or ElevenLabs code was copied.
- No provider request, deployment, live Astrale install, infrastructure mutation, or secret access was
  performed.
- No claim is made that ElevenLabs or Silero is selected for M1.
- The branch's documented "ready to deploy" statement is not treated as verification.
