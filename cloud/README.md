# Gumi cloud applications

`cloud/apps/*` is a flat collection of independently deployable applications. It can grow to many
applications without turning `cloud` into a technical-layer tree or forcing every service into one
runtime.

Each application owns:

- an `app.json` catalog entry declaring ownership, data class, tenancy, contracts, dependencies,
  deployment/region state, retention state, and runbook;
- its deployment/runtime package and operational state;
- its public API under `api/vN` when it publishes one;
- its schema, fixtures, migrations, tests, and runbook; and
- the credentials and authority boundary for effects it performs.

An application consumes another application's API through generated clients or an explicit adapter.
It does not import the publisher's storage internals or copy its schema into a root contracts folder.
Shared code enters `cloud/packages` only after two real applications need the same implementation; a
wire contract remains with its publisher even then.

Current applications:

- `gumi`: the `gumi.astrale.ai` semantic domain for fleet, capture, Recording, Transcript, and
  Conversation-membership truth today and later action contexts. It owns caller authorization and
  graph truth, not media or transcript-artifact bytes.
- `media-ingest`: the high-volume resumable byte plane. It owns ingest sessions, chunk durability,
  immutable objects, and manifests, but receives no broad Astrale mutation authority.
- `media-processing`: the provider-neutral batch derivation plane. It owns digest-bound jobs,
  attempt/lease history, immutable transcript artifacts, and provenance, but receives no Recording,
  Conversation, or action authority.

Future realtime voice applications are added only when their executable ownership and deployment
boundaries exist; names in the roadmap are not placeholder directories.

The M1 deployment and provisioning baseline is specified in the
[cloud operating model](OPERATING_MODEL.md). In particular, the Astrale instance is the semantic
isolation boundary while the current data-plane services remain dedicated deployments; shared
multi-tenant service operation is not claimed.

Every application must expose a non-mutating `verify` package script and own a boundary `README.md`.
`sh cloud/verify-apps.sh` discovers the flat collection, rejects incomplete app entries, ambiguous
package-manager state, and dependency-bearing packages without a lockfile, then invokes the selected
package manager. Dependency-free Node applications may omit an empty lockfile and use `npm`. An app's
root `verify` must include every landed workspace, client, and simulation it owns. The root
`./gumiw verifyWorkspace` gate composes this with all configured Gradle modules and repository shell
tests; adding another app does not require editing a central app list.

## Application boundary test

An application deserves its own `cloud/apps/<app-id>` directory only when it can answer all of these:

1. What durable invariant and vocabulary does it own?
2. Which caller identity may ask it to change that invariant?
3. Which API, event, or immutable projection does it publish?
4. Which external effects and secrets does its deployment need?
5. How does retry, cancellation, outcome-unknown, and recovery converge?
6. Can it be built, tested, operated, and rolled back without importing another app's storage internals?

A different framework, queue, database, or provider is not by itself an application boundary. A folder
with only DTOs or helpers belongs with its publisher until demonstrated reuse justifies a package.

## App-local shape

The exact runtime remains app-owned, but these names keep a large flat app collection navigable:

```text
cloud/apps/<app-id>/
├── app.json             # Machine-checked owner, tenancy, contracts, deployment and retention state
├── api/vN/              # Publisher-owned OpenAPI, JSON Schema, realtime or callback contract
├── src/ or runtime/     # Application use cases and state transitions
├── core/                # Pure invariant logic when the runtime benefits from it
├── integrations/        # Narrow adapters to other apps/providers
├── migrations/          # App-owned durable schema evolution
├── tests/ or simulation/# Contract, fault, authorization, and recovery witnesses
├── docs/                # Authority, operation, retention, and recovery notes
├── package/build files  # Independently executable verification entrypoint
└── README.md            # Boundary, status, commands, and explicitly open production gates
```

Not every application needs every directory. Empty symmetry is forbidden. Public schemas remain under
the publishing app even if generated clients are emitted in another language or repository later.

## Cross-application rules

- A caller presents the narrow identity intended by the callee. A general Astrale credential is never
  forwarded to a byte, provider-callback, or worker endpoint for convenience.
- Synchronous acceptance is not durable completion. APIs name the exact commit boundary they
  acknowledge; long work exposes a stable job identity and monotonic state.
- Cross-app retries use a stable caller request ID and an immutable semantic binding. A timeout becomes
  outcome-unknown and is reconciled by status/read, never converted into a fresh logical effect.
- One app never transactionally writes another app's database. Multi-app convergence uses immutable
  projections, idempotent calls, receipts, and explicit repair/reconciliation paths.
- Provider callbacks authenticate the provider and target one pre-existing job/attempt. They receive no
  authority to invent a Recording, Transcript, Conversation, VoiceTurn, or ActionInvocation.
- Raw audio, short-lived credentials, signed URLs, model secrets, and provider payloads do not enter the
  Astrale graph or ordinary application logs.
- Every derived artifact records input digest, pipeline/configuration identity, provider/model/version,
  attempt identity, timestamps, and output digest. Reprocessing creates a new version; it does not
  silently rewrite provenance.
- Transcript text is untrusted content. It can inform a model but cannot directly become an executable
  action, query, policy, tool grant, or system instruction.
- Cancellation and deletion are separate. Cancellation stops future work where possible; deletion and
  retention must also account for committed inputs, outputs, callbacks, logs, and provider copies.

## M1 ownership map

| Application | Durable truth | Published boundary | Explicitly outside its authority |
| --- | --- | --- | --- |
| `gumi` | device/capture/recording/transcript ownership and later conversation/action semantics | Astrale functions, generated client/views, narrow publisher-bound reads | raw media and transcript artifacts, transfer ledger, provider credentials, worker leases |
| `media-ingest` | scoped ingest session, immutable chunk bindings, assembled object, manifest | edge data API and digest-bound immutable manifest projection | Recording/Transcript ownership, transcription, broad graph mutation |
| `media-processing` | idempotent batch processing job, attempt/lease history, immutable derived artifact and provenance | control API, worker/callback boundary, digest-bound result and bounded content-page projections | Recording/Transcript authority, conversation grouping, realtime turn transport |
| `realtime-gateway` (later) | bounded live session/turn routing, correlated transport outcome and provider trace | edge realtime protocol and narrow result/control API | durable recording truth, arbitrary action authority, batch transcript ownership |

The `realtime-gateway` row is a boundary commitment, not a claim that the application exists today. Its
directory lands only with an executable contract, invariant core, failure tests, and one composition
entrypoint.

## When the collection reaches tens or hundreds of apps

Keep stable flat app IDs and derive catalogs from the required app metadata rather than moving source
according to today's team chart. The dependency-free catalog verifier already rejects missing apps,
contracts, tenancy declarations, retention/deployment state, and dependency cycles. CI can later build
the transitive impact set from the same declared APIs/dependencies while a scheduled acceptance lane
exercises cross-app flows.

Introduce a namespace or split repository only for a real access, compliance/data-residency, ownership,
or tooling boundary. Do not use a global `common`, `contracts`, `models`, or `utils` app. The first real
consumer asks the publisher for a stable API; the second real implementation consumer may justify a
small package with its own owner and compatibility tests.
