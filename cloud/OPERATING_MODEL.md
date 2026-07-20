# Gumi cloud operating model

Status: M1 architecture baseline, 2026-07-19. This document defines ownership and deployment
boundaries; it does not claim that a cloud application is deployed or that an Astrale domain is
installed live.

## Decisions made now

1. `cloud/apps/*` stays a flat collection of independently operable applications. Framework, database,
   provider, and team names do not create application boundaries.
2. An application owns one durable invariant, its public contract, effects, secrets, migrations,
   retention obligations, and recovery procedure. Another app consumes its published boundary and
   never imports its storage internals.
3. The Astrale kernel **instance** is Gumi's M1 semantic isolation boundary. The same
   `gumi.astrale.ai` domain definition may be installed on multiple instances, but each installation
   has separate graph data, grants, schema version, and lifecycle.
4. `media-ingest` and `media-processing` do not yet authenticate an Astrale-instance scope. M1 must
   therefore run them in a deployment cell dedicated to one Astrale instance. A shared multi-tenant
   deployment is forbidden until every control, data, worker, and callback credential carries one
   verified, revocable instance scope and every durable key is partitioned by it.
5. `Tenant` is not added to the Gumi graph merely as a folder or command bus. Organization, billing,
   residency, and collaboration tenancy are not yet one proven concept. Provisioning first needs a
   stable operator-owned deployment binding between an Astrale instance and its dedicated cloud cell.
6. Raw audio and provider artifacts stay outside Astrale. The graph owns authorized meaning and exact
   immutable references; the byte and derivation planes own their own durable records.
7. The audited `astrale-ai/lab` services are evidence, not a deployment baseline. No lab service or
   storage workflow is part of this operating model.

These decisions preserve a migration path: a dedicated cell can later join a shared pool by changing
its authenticated tenancy contract, not by rewriting semantic Recording or Conversation ownership.

## Source evidence behind the isolation decision

- The Gumi worker's [dependency composition](apps/gumi/deps.ts) deliberately supplies unavailable
  media adapters, so no production instance-to-service binding exists.
- The [manifest-reader scope](apps/gumi/integrations/media-ingest/media-ingest.port.ts) binds caller,
  capture, device, and edge-host facts, while the
  [processing-reader scope](apps/gumi/integrations/media-processing/media-processing.port.ts) binds
  caller, Recording, manifest, and digest facts. Neither port carries an authenticated instance or
  deployment-binding scope.
- The media-ingest [HTTP authorization adapter](apps/media-ingest/src/http/auth.mjs) verifies principal,
  audience, credential kind, session, and stream. The media-processing
  [HTTP authorization adapter](apps/media-processing/src/http/auth.mjs) verifies control, worker, and
  callback principals. Neither adapter derives an instance partition.
- [`Device.register`](apps/gumi/runtime/fleet/register-device.ts) accepts a caller-supplied UUID beneath
  a caller-selected parent. [`Device.openCapture`](apps/gumi/runtime/capture/open-capture.ts) accepts a
  caller-supplied EdgeHost UUID. Their local authorization/replay behavior is useful, but those values
  are not provisioning evidence.
- The [reuse audit](astrale-lab-prod-reuse-audit.md) found no legacy service with a compatible identity,
  durability, retention, or callback boundary, so there is no safe inherited deployment topology.

## M1 topology

```text
person / edge shell
       |
       | authenticated Astrale call
       v
+---------------- Astrale instance A ----------------+
| installed gumi.astrale.ai domain                   |
| Device / CaptureSession / Recording / Transcript   |
| Conversation, caller grants, semantic audit edges  |
+----------------------+-----------------------------+
                       |
                       | instance-bound control identity
                       v
+------------ deployment cell A (not shared) ----------------+
| media-ingest                 media-processing               |
| session/chunks/object        job/attempt/lease/artifact      |
| manifest projection          digest-bound result/pages      |
|      |                              |                        |
| encrypted media store        encrypted artifact store       |
|                                     |                        |
|                              selected provider adapter       |
+--------------------------------------------------------------+
                       ^
                       |
             capture-scoped edge bearer
```

The graph domain and both service deployments may use different runtimes and stores. “Cell” is an
operational grouping, not a source directory or a new application. It says that their audiences,
trust roots, data region, retention policy, and lifecycle are bound to the same Astrale instance.

## Application ownership

| Application | Owns durably | Accepts authority from | Publishes | Must never do |
| --- | --- | --- | --- | --- |
| `gumi` | semantic Device, CaptureSession, Recording, Transcript, Conversation, membership, and their authorization edges/receipts | actual Astrale caller, checked explicitly in each callable | Astrale schema, functions/methods, and authorized views | store audio/artifact bytes; trust asserted device/edge IDs as attestation; forward a general graph credential |
| `media-ingest` | ingest session, credential binding, exact chunks/ranges, immutable object, manifest | one control principal or one capture-scoped data bearer | `media-ingest-v1` HTTP contract and immutable manifest projection | create Recording/Transcript; transcribe; accept public `uid`; mutate graph state |
| `media-processing` | request/job, attempt generation, lease, callback receipt, immutable artifact, provenance | distinct control, worker, or attempt-bound callback principals | `media-processing-v1` HTTP contract, result projection, bounded pages | invent Recording/Conversation; execute transcript text; treat provider authentication as semantic authority |
| future realtime gateway | live session/turn routing, transport outcome, correlated provider trace | one authorized voice-turn scope | bounded realtime/control protocol | become batch ingest, retain arbitrary history, or hold general action authority |
| future provisioning operator | instance-to-cell binding, deployment/install revision, trust roots, revocation and rotation status | platform administrator or reviewed automation identity | narrow operator API and edge bootstrap profile | become semantic application data; embed provider/API secrets in graph nodes |

The future provisioning operator becomes an application only when it has a durable store, callable
boundary, lifecycle, tests, and an operator. Until then it is an explicit missing control-system
capability, not an empty `cloud/apps/provisioning` directory.

## Cross-application M1 flow

1. An authenticated caller invokes `Device.openCapture` on an active Gumi Device. Today this proves
   graph authority only; production must also resolve a provisioned Device/EdgeHost binding.
2. The Gumi domain asks `media-ingest` to create one ingest session using an instance-bound control
   identity. The returned edge credential is scoped to the exact session, capture, device, edge host,
   streams, sequence policy, audience, and expiry.
3. The edge uploads already-durable chunks. Only `media-ingest` acknowledges byte durability. A lost
   response is outcome-unknown and is resolved by replay/status using the same identity.
4. `media-ingest` commits the immutable object and digest-bound manifest. It does not call Astrale.
5. The edge or reconciler invokes `CaptureSession.finalizeRecording`. Gumi obtains only the narrow
   immutable manifest projection and commits Recording semantic truth.
6. An authorized caller requests processing for that exact Recording. `media-processing` resolves the
   immutable input behind its own adapter, leases one attempt, and commits one provenance-rich artifact.
7. A provider callback may complete only its pre-existing attempt. It never receives Recording,
   Conversation, or graph authority.
8. Gumi imports exact digest-bound pages, publishes the Transcript, and explicitly relates the ready
   Recording/Transcript to a caller-owned Conversation.

No step spans two application databases transactionally. Stable request identities, immutable facts,
atomic app-local commits, receipts, status reads, and reconciliation make the sequence converge.

## Identity taxonomy

These identities are independent and must never be substituted for one another:

| Identity | Meaning | Current state |
| --- | --- | --- |
| Astrale principal | human/application actor attributed to one call | local domain simulations exercise caller gates |
| Astrale instance | isolated kernel graph and installed-domain lifecycle | platform concept exists; no Gumi deployment binding is composed |
| deployment binding | operator record binding one instance to one cloud cell, policy, and trust roots | missing |
| physical Device | provisioned product identity proven by enrollment/challenge | current UUID is caller asserted and path-local |
| EdgeHost | provisioned local-computer identity with revocation and device relationship | current UUID is caller asserted on CaptureSession |
| cloud app principal | narrow identity used by Gumi to call one service audience | production issuer/verifier missing |
| ingest bearer | short-lived capability for one capture/session/stream policy | contract/core only |
| worker principal | processing worker plus pipeline allowlist | contract/core only |
| provider callback principal | verified provider delivery bound to one job/attempt | candidate boundary only |
| correlation ID | observability join key | never authority |

The current `Device.register` callable is semantic registration beneath a caller-selected parent. It
does not prove global uniqueness, hardware possession, attestation, edge-host binding, or revocation
propagation. Production documentation and UI must not call that operation “provisioning” until those
gates exist.

## Provisioning control system

### Deployment binding

The first operator-owned durable record should bind, without storing secrets:

- a stable binding ID and revision;
- the Astrale instance's stable platform identifier and verified issuer/audience facts;
- the Gumi domain origin, serving issuer, installed schema hash, and install revision;
- the media-ingest and media-processing endpoints, audiences, accepted key-set revisions, and service
  deployment revisions;
- selected data region and retention/deletion policy IDs;
- allowed pipeline profile IDs and provider policy IDs;
- lifecycle state `staged | active | suspended | revoked | retired` plus timestamps and actor; and
- references to secret-manager/key resources, never secret values.

Do not use a mutable display name, URL alone, graph parent path, bearer token, or provider account ID as
the binding identity. The record belongs in the future operator store because it controls deployments
and install lifecycle across applications; it is not ordinary user graph data.

### Activation sequence

A binding becomes `active` only after all of these converge:

1. create or select the Astrale instance and establish its external trust/provisioning policy;
2. select region, encryption keys, retention class, provider policy, and deletion obligations;
3. deploy a dedicated media-ingest/processing cell with unique audiences and no public broad ingress;
4. configure key sets and exact control/data/worker/callback verification and revocation;
5. deploy and install the Gumi domain, then record and re-read the installed schema hash;
6. provision the owner's initial grants without handing ordinary callers `SHARE` or root authority;
7. enroll an EdgeHost and Device using possession/challenge evidence, and record revocable bindings;
8. issue an edge bootstrap profile containing only endpoints, public trust roots, binding ID/revision,
   and an enrollment capability; and
9. run a no-media smoke witness across auth, service status, domain call, suspension, and revocation.

Suspension stops new sessions/jobs while preserving historical reads allowed by policy. Revocation
invalidates credentials and enrollment for the affected binding. Retirement additionally reconciles
domain install state, objects/artifacts, provider copies, logs, and required audit/tombstone evidence.

### Why no tenant field was added yet

Adding an unverified `tenantId` string to every JSON body would only create attacker-controlled
partition labels. The safe transition to a shared service requires a credential verifier to derive an
authenticated instance scope, then requires the service core and storage keys to carry that scope on
every session, request, job, attempt, artifact, lookup, quota, log, and deletion operation. Until that
vertical exists, dedicated deployments are a security boundary rather than an optimization choice.

## Machine-readable application catalog

Every current application now owns `app.json`, validated by
[`catalog/verify-manifests.mjs`](catalog/verify-manifests.mjs) against the policy represented by
[`application-manifest.schema.json`](catalog/application-manifest.schema.json). The manifest records:

- stable app ID, kind, capability owner, summary, and durable authority;
- sensitive data classes and one explicit tenancy mode;
- publisher-owned contracts and contract-level dependency edges;
- deployment target/regions and retention policy state; and
- the app-owned runbook.

The catalog is intentionally metadata, not a global contract package. Public API files remain inside
their publishing applications. Verification rejects missing manifests/contracts/apps, directory-ID
drift, invalid tenancy combinations, dependency cycles, local path escapes, and undeclared deployment
or retention states.

The three supported tenancy declarations are:

| Mode | Meaning | Promotion requirement |
| --- | --- | --- |
| `astrale-instance` | application data is isolated by the kernel instance | live install and grant verification |
| `dedicated-deployment` | one service deployment belongs to one deployment binding | operator-owned binding plus deployed auth/storage policy |
| `authenticated-instance` | one deployment may serve several instance scopes | end-to-end authenticated scope, partition, quota, retention, deletion, and adversarial isolation proof |

CI can later derive an impact graph and inventory from these manifests without reorganizing source by
team or deployment platform. A new app cannot hide as an unverified directory.

## Executable backlog to a provisioned M1 cloud

| Order | Work item and owner | Required artifact/test | Current blocker |
| --- | --- | --- | --- |
| 1 | `CLOUD-BIND-01`: provisioning operator owns deployment-binding contract and state machine | versioned contract, fake store, replay/rotation/suspend/revoke tests | Astrale stable instance identifier and trust-policy integration must be selected |
| 2 | `SEM-PROV-01`: Gumi Fleet owns `EdgeHost` plus Device provisioning receipt/bindings | schema/method ownership table, caller/function gates, challenge-evidence adapter, global-within-binding uniqueness races | hardware application challenge and edge enrollment protocol not implemented |
| 3 | `CLOUD-AUTH-01`: each service owns production credential verification | issuer/audience/kind/scope/key-revision/revocation negative suite; no body-supplied identity | deployment target/key service unselected |
| 4 | `INGEST-DUR-02`: media-ingest owns real metadata/object adapters | crash/concurrency tests at every named durable boundary, encryption and exact digest assembly | store/region/retention selection |
| 5 | `PROC-DUR-02`: media-processing owns real job/queue/artifact adapters | serializable claim/lease tests, dead-letter/reconciliation, immutable artifact crash suite | queue/store/region selection |
| 6 | `CLOUD-INSTALL-01`: operator reconciles deploy versus install state | deployed worker probes, signed bundle, install/reinstall, schema-hash and grant read-back | binding/operator not present |
| 7 | `CLOUD-RET-01`: cell policy owns cross-plane retention/deletion | policy IDs, state machine, source/artifact/provider/log deletion and tombstone evidence | product/legal retention decision and provider selection |
| 8 | `CLOUD-OBS-01`: app owners publish one redacted telemetry vocabulary | induced gap/duplicate/stuck-job/provider-failure trace across correlation IDs | collector/region/SLO selection |
| 9 | `CLOUD-E2E-01`: qualification owns the real installed witness | one owned Recording -> immutable Transcript -> multi-recording Conversation view, plus foreign-owner/revocation failures | all earlier items and physical edge path |

Items 1 and 2 can be modeled behind fakes once the stable instance identifier and device challenge
contract are known. Implementing either today would invent the security evidence it is meant to bind.
The application catalog is safe now because it describes and verifies existing source boundaries
without granting runtime authority.

## Scaling beyond the dedicated M1 cell

Moving a service from `dedicated-deployment` to `authenticated-instance` requires all of the following,
not merely adding a request field:

- the verified credential derives one immutable binding/instance scope and exact audience;
- every durable primary and secondary key includes that scope;
- every lookup, idempotency record, callback, queue message, signed media capability, cache, limiter,
  metric dimension, and operator command is scope-bound;
- object encryption, region, retention, deletion, provider account/policy, and quota are enforceable per
  scope;
- a caller cannot infer another scope's existence through status, timing, conflict, error, or billing
  behavior;
- rotation, suspension, revocation, backup/restore, migration, and disaster recovery preserve scope;
  and
- adversarial cross-scope tests pass against the deployed stores and ingress.

At tens or hundreds of applications, keep stable flat IDs and generate catalogs, dependency impact,
deployment inventories, ownership pages, retention reviews, and acceptance lanes from `app.json`.
Introduce repository or namespace boundaries only for real compliance, access, data-residency, or
tooling separation. Never introduce global `common`, `models`, `contracts`, or `utils` applications.

## Explicit non-claims

- No cloud application was deployed and no Astrale domain was installed.
- No tenant, instance, Device, or EdgeHost provisioning was implemented.
- No credential, key, secret, provider, queue, database, object store, region, or retention policy was
  selected.
- The manifest catalog validates repository intent; it is not runtime isolation evidence.
- Local service/domain tests remain source/local evidence, not deployed or end-to-end qualification.
