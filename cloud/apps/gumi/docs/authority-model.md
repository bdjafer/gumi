# Gumi domain authority model

Status: local executable M1 fleet/capture, transcript-publication, and Conversation-membership slices.
Assistant contexts are added only with their first real callable; production delegation and
provisioning remain gated below.

## Boundary

`gumi.astrale.ai` owns semantic device, capture, recording, transcription, transcript, Conversation,
and membership truth. It
does not own BLE addresses, audio chunks, upload cursors, object-store keys, provider secrets, raw
transcript artifacts, or realtime packets. A Recording and Transcript contain opaque immutable
handles and provenance, never source-media or artifact bytes.

## Callable ownership

| Callable | Receiver | Required standing authority of actual caller | Effects |
| --- | --- | --- | --- |
| `Device.register` | `Device` class | `EDIT` on requested parent | create Device and owner edge |
| `Device.openCapture` | active Device | `EDIT` on Device | create CaptureSession, requester edge, and asserted edge-host binding |
| `CaptureSession.close` | CaptureSession | `EDIT` on CaptureSession | atomically create one closure receipt and terminal projection |
| `CaptureSession.finalizeRecording` | CaptureSession | `EDIT` on CaptureSession | verify scoped immutable manifest; atomically create one Recording finalization receipt and projection |
| `Recording.publishTranscript` | ready Recording | `EDIT` on Recording | bind one processing job and output digest; import exact immutable pages; atomically publish terminal Transcript state |
| `Conversation.create` | `Conversation` class | `EDIT` on requested parent | create one caller-owned Conversation at a stable ID-derived path |
| `Conversation.addRecording` | Conversation | `EDIT` on Conversation plus `READ` on exact Recording and Transcript | atomically create one actor-bound membership receipt and its typed source edges |

Every requirement is enforced in the callable's `authorize` hook against `auth.principal`. The
composed handler credential is not treated as evidence that the caller held the permission.

The media-ingest port requires the actual caller and the exact graph-derived capture path/ID, device
path/ID, and capture-bound edge-host ID when issuing a fresh `CaptureBoundManifestReader`. The reader
captures that scope, and its read-only lookup cannot substitute another caller or binding. The returned
projection must repeat the capture, device, and edge-host IDs in addition to the manifest ID, SHA-256
digest, a non-empty ordered sequence range, and the primary object's exact content digest, canonical
decimal u64 byte length, and content type. Gumi stores those immutable object facts on the ready
Recording, but never the bytes, object-store identity, or a signed URL. Gumi rejects a projection whose
IDs differ from the current graph binding; globally duplicated or unprovisioned IDs remain explicit
gates below.

The production adapter factory must exchange that per-call scope for a short-lived provider delegation; a
long-lived service credential must never itself authorize an arbitrary manifest read. Its identity gets
no `SHARE`, root, or general graph authority. Secrets remain inside composition adapters and are not
ambient callable authority. The adapter currently fails closed until this exchange is implemented and
reviewed; passing scope values to the unavailable port is not itself cryptographic proof.

Transcript publication uses a separate `RecordingBoundProcessingReader`. Its issuance captures the
actual caller, Recording path, manifest ID/digest, and object content digest from the ready Recording.
The caller supplies only the processing job and expected output digest. Result resolution therefore
cannot substitute the input digest; each page additionally binds the expected artifact ID and exact
start index. Gumi revalidates publisher types, job/artifact/digests, language, duration, aggregate
counts, page ranges, contiguous indices, timing, Unicode bounds, and cross-page timing before graph
publication. Segment text and provider speaker labels are stored as inert evidence: neither becomes a
prompt, policy, action, tool grant, or resolved person identity.

## Retry and conflict behavior

- Device, CaptureSession, and Recording paths derive from caller-stable UUIDs.
- Each CaptureSession stores the UUIDv7 edge host asserted when it opens. Finalization additionally
  resolves exactly one incoming `device_has_capture` edge and checks all three publisher identities.
- Recording finalization requires the CaptureSession to be terminal, so an immutable projection cannot
  race a still-open capture.
- Repeating an operation with identical immutable facts returns the existing semantic identity.
- Reusing a stable ID with different facts fails with `StableIdentityConflictError`.
- A Recording is created as `pending-manifest` before the external manifest lookup. A retry can
  safely resume that lookup.
- `CaptureSession.close` creates a fixed-path `CaptureClosure` and updates the session in one atomic
  mutation. Path uniqueness selects one concurrent timestamp; a loser reloads that receipt.
- Ready publication similarly creates one fixed-path `RecordingFinalization` receipt and updates the
  Recording in the same atomic mutation. Replay validates every stored projection field, the exact
  receipt including `finalizedAt`, and the current capture/device/edge-host binding without another
  media-ingest call.
- `step.run` names effect stages, but the current inline step executor is not a durability boundary;
  graph path uniqueness, atomic mutations, graph convergence, and the provider's idempotent GET provide
  retry safety.
- Transcript publication first creates a fixed-path `Transcription` intent bound to the Recording,
  processing job, input facts, and expected output digest. Identity reuse with changed facts fails
  before another processing read.
- The verified result creates one publishing `Transcript`. Every digest-bound page commits at most four
  fixed-path segments plus one page receipt atomically. A retry refetches the immutable page and compares
  exact stored segments before accepting an existing receipt, so a crash can resume without duplicating
  semantic nodes.
- The final mutation creates one fixed-path `TranscriptPublication` receipt, including the exact page
  count, and changes both Transcription and Transcript to `ready`. Ready replay reconstructs and
  validates the complete stored publisher projection, all Recording/job/artifact/class/edge bindings,
  the terminal receipt, and every expected fixed-path page receipt without another media-processing
  call or loading transcript text. Atomic page commits plus restricted live graph mutation are the
  segment-completeness boundary. `publishing` remains explicit after an outage; it is never projected
  as a completed transcript.
- Conversation creation binds its stable identity to its original parent, immutable title, and actual
  owner. `addRecording` accepts only a ready Gumi Recording and a ready Gumi Transcript whose exact
  `transcribes` edge resolves back to that Recording.
- Each Conversation membership lives at a Recording-ID-derived fixed path. The membership node and its
  Conversation, Recording, Transcript, and actor edges commit in one mutation. Replay validates the
  complete receipt and all four bindings; concurrent path conflict converges only when they are exact.
  A second immutable Transcript for the same Recording is a conflict, not a silent replacement.

## Remaining authority and identity gates

- `device_owned_by` and `capture_requested_by` now put cardinality `1` on the Device and CaptureSession
  endpoints, respectively. Handler creation commits each new node and its single edge atomically, but
  the current general mutation layer does not enforce endpoint cardinality for every external writer.
  Register/open replay therefore requires exactly one edge and the original owner/requester identity;
  live installation must still restrict graph mutation to reviewed callables.
- `edgeHostId` is currently a capture-scoped asserted UUID, not a provisioned or attested EdgeHost
  identity. Device challenge/attestation and edge-host revocation must make that fact trustworthy.
- Device UUIDs are path-local beneath the selected parent. A tenant-owned global device registry or
  equivalent uniqueness gate is still required before a physical device ID can be security-sensitive.
- The publisher projection has no tenant identifier. Tenant binding must be added before multiple
  tenant namespaces can share one media-ingest authority boundary.
- The media-processing control-credential adapter, pipeline profile allowlist, durable job store,
  provider worker/callback adapters, deployment, retention/deletion, and live Astrale install are not
  implemented. Local publication simulations do not claim a real transcription provider or live data.
