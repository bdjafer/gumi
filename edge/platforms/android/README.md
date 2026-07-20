# Android edge adapters

## Encrypted spool adapter

`dev.gumi.edge.platforms.android.spool` is the Android implementation candidate for the portable
`SpoolStore` and `DurablePayloadStore` ports. It is production-shaped, but it is not yet qualified
under Decision 0002's forced-death, reboot, migration, key-rotation, and key-loss matrix.

The candidate uses Android platform primitives already in the application:

- an Android Keystore AES-256-GCM key with an explicit positive envelope-key version;
- a separate Keystore HMAC-SHA-256 locator key, with distinct payload-locator and metadata-revision
  input domains;
- an Android SQLite WAL database containing one encrypted, strictly encoded metadata snapshot;
- logically immutable encrypted payload files below `Context.noBackupFilesDir`; and
- `android.system.Os` file/directory `fsync` plus crash-atomic rename publication under the lifetime
  single-writer lease.

This is not Room or SQLCipher. Semantic metadata is encrypted before SQLite sees it; SQLite can
observe only its schema, a keyed revision token, envelope framing, and ciphertext length. Media never
enters a database BLOB. Adopting another database remains a separate migration, packaging,
attribution, performance, and forced-death decision.

### Ownership, opening, and recovery

Opening acquires two layers of exclusive ownership before inspecting storage artifacts or keys:

1. a process-wide canonical-path claim; and
2. an OS advisory file lock held until `AndroidEncryptedSpoolStorage.close()`.

The lock file is outside the data root, so creating it cannot turn a new store into an existing one.
Only an absent data root is a provably new store. A new store receives exactly one empty-ledger
bootstrap attempt; an existing store with a missing singleton row always fails closed, even when no
payload file survives. SQLite construction closes its handle on every setup failure.

Reconciliation runs once under exclusive ownership before any operational port can escape. There is
no live/public orphan-sweep API. This avoids deleting an old idempotent payload between its verified
write and the metadata transaction that binds it. A later WorkManager collector requires a separate
runtime operation with a durable candidate/lease protocol and is intentionally not implemented here.

`openAndReconcile` returns a sealed result:

- `Ready` exposes storage only after metadata and every referenced payload verify. An orphan cleanup
  failure remains a warning because it does not invalidate referenced durability.
- `Degraded` exposes only a redacted reconciliation report when metadata is unavailable or any
  referenced payload is missing, corrupt, or unreadable. Operational ports cannot be accidentally
  composed in that state.

Close invalidates the payload port, closes SQLite, releases both ownership layers, and surfaces only a
stable redacted close code. Callers must still quiesce in-flight coordinators before close.

### Write and transaction ordering

1. A payload write checks declared length and SHA-256, derives a non-identifying keyed locator,
   encrypts with provider-generated GCM randomness and the complete descriptor as AAD, flushes the
   temporary inode, publishes the final name with atomic rename while the exclusive storage lease and
   payload mutex exclude conforming competitors, and flushes the directory. Android SELinux rejects
   app-data hard links, so the lifetime ownership invariant is required for no-overwrite semantics.
2. A metadata commit authenticates/decrypts the current row, compares the portable `ULong` revision,
   encrypts the complete next state, and replaces it in one SQLite transaction only if the keyed
   revision token still matches.
3. An exception after SQLite enters its commit boundary is `OutcomeUnknown`; callers reload. Payload
   retries verify the immutable winner rather than replacing it.
4. Startup reconciliation authenticates metadata and every referenced payload before considering only
   recognized, old, unreferenced final or temporary files for collection.

### Key and replay limits

Envelope framing and readers carry explicit key versions, and old-version reads are supported by the
crypto layer. Production runtime key rotation is deliberately **not exposed**: a durable active-key
policy and interruption protocol do not exist yet. Production composition is fixed to envelope-key
version 1, and an existing store will not recreate that key if it is lost. Do not present the
versioned envelope reader as rotation support.

AEAD proves integrity and descriptor binding, not freshness. Replaying a complete older valid
`(revision token, encrypted snapshot)` pair remains cryptographically valid. Auto Backup/device
transfer are disabled by the Android shell, and the store uses `noBackupFilesDir`, but those policies
are not anti-rollback cryptography. The deterministic suite records this limitation explicitly. A
production threat model that includes privileged filesystem rollback needs a trusted freshness anchor.

All public operation failures and reports expose stable codes/counts only. The adapter contains no
logging call and returns no filesystem path, key alias, payload reference, identifier, or raw media in
diagnostics.

### Executable evidence

Local deterministic evidence:

```sh
./gumiw :edge:platforms:android:check \
  :edge:platforms:android:assembleDebugAndroidTest --console=plain
```

The JVM suite covers strict/canonical metadata encoding, AEAD tamper and descriptor replay, fresh
nonces, immutable payload idempotency, payload-port close, CAS races and unknown outcomes, one-shot
bootstrap, complete-row rollback semantics, process ownership exclusion/release, restart verification,
quota/error mapping, and cleanup-warning versus degraded-readiness policy. JVM tests do not execute
Android Keystore, SQLite, or `Os.fsync`.

The same witness runs on an attached Android target:

```sh
./gumiw :edge:platforms:android:connectedDebugAndroidTest --console=plain
```

`AndroidEncryptedSpoolInstrumentationTest` exercises the public composition, real no-backup path,
Keystore AES/HMAC operations, SQLite WAL/CAS, immutable file installation and directory flushes,
exclusive concurrent-open rejection, release/reopen recovery, active-key loss, missing-row fail-closed
behavior, degraded missing-payload opening, cancellation immediately after ownership, operational
adapter exclusivity/reopen, and an on-disk plaintext-marker scan. Compiling its APK is not device-runtime
evidence. On 2026-07-19, the original five instrumentation cases passed on a clean API 36 ARM64
emulator. The two later operational/cancellation cases compile but have not run on Android; the next
target invocation executes all seven. Existing results are Android-framework evidence, not proof of the
owned phone's hardware-backed Keystore, OEM filesystem, forced-death, reboot, or power behavior.

### Qualification still required

- Run the passing instrumentation witness on the owned target.
- Kill and reboot at every payload/file/metadata/ownership boundary, including unknown SQLite commit.
- Add checked forward and rollback migrations; schema version 1 fails closed on any other version.
- Exercise real ENOSPC, WAL pressure, separate Android processes, uninstall/reinstall, device lock,
  Keystore invalidation, and OEM filesystem behavior.
- Design and prove durable, interruption-safe multi-version key rotation before exposing rotation.
- Decide whether privileged full-snapshot rollback is in scope and add a trusted freshness mechanism if
  it is.
- Confirm crash collection, SQLite sidecars, and device telemetry remain content-free on target.
