# Gumi realtime gateway

`realtime-gateway` is the provider-neutral live transport boundary for one admitted VoiceTurn. It owns
stable session/turn/retry identities, per-connection fencing, ordered digest-bound audio delivery,
cancellation, explicit provider outcome ambiguity, correlated provider trace, and one bounded inert
display result. It owns neither the semantic VoiceTurn nor permission to perform an action.

## Current executable boundary

- The public v1 WebSocket and binary-audio contract lives in
  [`api/v1/protocol.schema.json`](api/v1/protocol.schema.json) with normative lifecycle text in
  [`api/v1/protocol.md`](api/v1/protocol.md).
- `src/core` contains provider-neutral invariants. Every provider effect has a stable idempotency key,
  an intent state, and a known/unknown outcome boundary.
- `src/ports.mjs` names the session-state, provider, clock, and upgrade-authorizer ports.
- `src/ws/server.mjs` is a real HTTP Upgrade/WebSocket adapter built on the maintained `ws` library.
- `src/runtime/local-composition.mjs` composes the core with bounded in-memory session/auth ports and a
  metadata-only loopback provider. It discards audio bytes after each call and retains only bounded
  sequence/digest/count metadata.

The in-memory adapters are deterministic test/local ports. They are not durable across process death,
not a production credential verifier, not a selected AI provider, and not deployed infrastructure.
The manifest's durable authority describes the application boundary that a production state port must
eventually uphold; this source does not claim that port exists.

## Verify

```bash
npm install
npm run verify
```

Verification loads every source module, checks the manifest/protocol contract, exercises scope and
identity substitution, stable replay, concurrent/fenced reconnect, ordered digest validation, bounded
buffers/results/retention, cancellation, all provider outcome-unknown phases, reconciliation, redacted
logging, and a real local WebSocket loopback.

## Run the local loopback

The CLI refuses to invent identities or print a credential. Supply six UUIDv7 values and an opaque
32–512 character local token through environment variables:

```bash
GUMI_RT_LOCAL_TOKEN='replace-with-a-long-local-only-token' \
GUMI_RT_BINDING_ID='0190c6f0-7b21-7a40-8b11-000000000001' \
GUMI_RT_DEVICE_ID='0190c6f0-7b21-7a40-8b11-000000000002' \
GUMI_RT_EDGE_HOST_ID='0190c6f0-7b21-7a40-8b11-000000000003' \
GUMI_RT_ADMISSION_ID='0190c6f0-7b21-7a40-8b11-000000000004' \
GUMI_RT_SESSION_ID='0190c6f0-7b21-7a40-8b11-000000000005' \
npm run dev
```

The server listens on loopback port `8789` by default, exposes only `GET /healthz` and the authenticated
`/v1/realtime` upgrade, and uses the fake metadata-only provider. This path proves actual socket and
composition behavior, not external-provider compatibility or durability.

## Authority and privacy boundary

- Upgrade authentication derives scope from a credential verifier; frames cannot supply or widen it.
- The edge principal must equal the scoped EdgeHost. Session, admission, Device, binding, and audience
  are exact and immutable.
- One admission/session owns one turn and one retry identity. A new connection only replaces the
  connection-generation lease.
- Audio frames are bounded and digest checked before any provider call. Result text is byte bounded and
  labeled `untrusted-provider-content`.
- Logs are allowlisted to operational IDs, state/error codes, sequence, and byte counts. They contain no
  bearer, audio, display text, provider payload, or general Astrale credential.
- The gateway has no action/tool/grant/query API. Transcript- or model-shaped text cannot become an
  executable request here.

## Open production gates

1. Choose the production runtime/region and implement a durable, encrypted, capacity- and TTL-enforced
   session port with crash tests at every intent/effect/result boundary.
2. Implement issuer, audience, scope, key-revision, expiry, revocation, Device, EdgeHost, admission, and
   deployment-binding verification. The local token map is test evidence only.
3. Select and evaluate a realtime provider, then implement exactly one adapter with provider-native
   idempotency/status/cancellation and latency/failure conformance. No provider is selected here.
4. Define/enforce retention and deletion for session metadata, provider copies, traces, and logs. The
   manifest intentionally remains `retention.status = unselected`.
5. Add deployed TLS ingress, overload control, telemetry/SLOs, key rotation, suspension/revocation, and
   cross-scope adversarial tests.
6. Connect an admitted edge VoiceTurn and a separately authorized Astrale semantic action/result slice;
   prove that neither side receives the other's general credential or authority.
7. Run physical Android/Omi latency, disconnect, cancellation, process-death, and visible-result
   qualification. Local sockets and mocks cannot close `RT-TURN-01` or M1.
