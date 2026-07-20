# Gumi Astrale domain

`gumi.astrale.ai` is Gumi's semantic cloud application. It currently implements executable
fleet/capture, transcript-publication, and Conversation-membership slices: semantically registered Devices,
terminal CaptureSessions, manifest-backed Recordings, recording-owned Transcriptions, immutable
Transcripts and inert segments, plus caller-owned Conversations with explicit membership receipts.
A Recording can be finalized only after its capture closes and the digest-bound media-ingest
projection matches the graph's capture, device, and edge-host binding. Fixed-path terminal receipts
arbitrate concurrent close/finalize calls. The ready Recording retains the opaque object handle plus
the primary object's exact content digest, u64 byte length, and content type so a later processing app
can bind its input without importing bytes or storage URLs. Media bytes and high-frequency transfer
state remain in `cloud/apps/media-ingest`. Transcript bytes remain in `cloud/apps/media-processing`;
Gumi imports them only through owner-scoped, digest-bound pages of at most four segments. A
Conversation membership atomically binds one ready Recording, its exact ready Transcript, and the
actual actor. Its Recording-derived receipt path makes retry and concurrent insertion converge without
turning a mutable list or provider gap heuristic into semantic truth.

```bash
pnpm install
pnpm generate:media-ingest
pnpm generate:media-processing
pnpm check:media-ingest
pnpm check:media-processing
pnpm dev
pnpm typecheck
pnpm test
pnpm verify
pnpm prod
```

Load the `astrale-domain` skill before authoring the domain:

```bash
npx skills add astrale-os/cli -g
```

Generated declarations under `integrations/media-ingest/generated/` and
`integrations/media-processing/generated/` come directly from each publisher-owned OpenAPI document.
`pnpm test` fails if either drifts, and the domain still applies strict runtime schemas at both graph
trust boundaries. Production dependencies deliberately remain unavailable until scoped credential
adapters are implemented. Tests inject explicit fakes; runtime never falls back to sample data. See
[the authority model](docs/authority-model.md).

The ingest port requires the actual caller plus capture/device/edge-host scope on every lookup. The
processing port separately captures the actual caller, Recording path, manifest binding, and exact
input content digest; a page call cannot substitute another input, output, artifact, job, or start.
Those shapes are ready for short-lived delegated adapters, but production credential exchange,
edge-host provisioning/attestation, tenant binding, and a globally unique device registry remain gates.
`Device.register` is therefore not yet a hardware-provisioning claim; it creates caller-owned graph
meaning from an asserted UUID and must be preceded by a future possession/binding workflow in production.

## Shape

```text
schema/        vocabulary, split by bounded context
runtime/       method implementations, split by bounded context and one file per method
core/          pure deterministic logic, paths, defaults, and domain errors
integrations/  external-system ports and adapters
simulation/    samples and business scenarios
client/        React views built on @astrale-os/shell-react
icons.ts       shared SVG wrapper; named icons stay with their context
env.ts         worker bindings and secrets
deps.ts        env-to-runtime dependency composition
domain.ts      worker-safe domain assembly
```

Keep `schema/index.ts` and `runtime/index.ts` as composition roots. Put standalone functions in the
bounded context that owns them, then wire them from `domain.ts`; do not create a generic functions
junk drawer. The same rule applies to views and client code.

Cross-layer imports use the package's `#` aliases (`#schema`, `#runtime`, `#deps`, `#core/*`,
`#integrations/*`, `#functions/*`, `#simulation/*`, and `#views`). Keep relative imports for nearby
files inside the same bounded context.

The client includes Tailwind CSS v4 and an empty shadcn `radix-nova` setup. Add only the components a
real view needs with `pnpm exec shadcn add <component>`; component source stays in the project and can
be adapted to the domain's visual language.

The adapter generates worker plumbing under `.astrale/`. Do not edit it. The signing identity in
`.astrale/identity.ts` is gitignored; back it up before deploying from more than one machine.
