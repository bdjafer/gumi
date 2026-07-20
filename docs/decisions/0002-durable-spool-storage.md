# Decision 0002: durable edge spool storage boundary

Status: accepted boundary with one locally executable Android implementation candidate and API 36
emulator instrumentation. Owned-handset/OEM execution, forced-death/reboot, migrations, durable
rotation, and production composition remain open. Evidence refreshed 2026-07-19.

## Decision

Keep durability semantics in `edge/runtime` and make persistence a host adapter. Gumi will not require
Android and Raspberry Pi to use the same database or key store. They must implement the same
`SpoolStore` and `DurablePayloadStore` contracts and pass the same failure suite.

The durable boundary has two stores with one ordering rule:

1. `DurablePayloadStore.writeAndFlush` encrypts, writes, integrity-checks, and durably flushes an
   immutable chunk body.
2. `SpoolStore.commit` atomically binds that opaque payload reference to the chunk metadata and
   advances the absolute source checkpoint.
3. A source cursor may advance only after both boundaries succeed. A payload left behind by a later
   metadata failure is an orphan eligible for reconciliation; a referenced payload is never garbage.

Media bytes remain bounded encrypted files rather than database BLOBs. The metadata store owns IDs,
descriptors, sequence ranges, checkpoints, remote acknowledgements, terminal ranges, and payload
references. Neither store is allowed to return success before its durability boundary.

## Android candidate

`edge/platforms/android` now contains a production-shaped implementation candidate for both portable
storage ports. It uses only Android platform primitives already needed by the application:

- Android Keystore AES-256-GCM envelope keys with explicit positive versions;
- a separate Keystore HMAC-SHA-256 key for non-identifying payload locators and metadata-revision
  tokens, with distinct input domains;
- one Android SQLite WAL row containing a strictly encoded, encrypted metadata snapshot; and
- immutable logical encrypted payload files under `Context.noBackupFilesDir`, installed by atomic
  rename after temporary-file `fsync` while the lifetime process/OS ownership lease excludes another
  writer, followed by directory `fsync`.

This is not Room or SQLCipher. SQLite can observe its small schema, keyed revision token, envelope
framing, and ciphertext length, but not semantic metadata; media never enters a database BLOB. Room,
SQLCipher, or another store may be reconsidered only as a separate migration/packaging decision that
beats this boundary under the same crash suite.

Primary references:

- [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

Opening takes both a process-wide canonical-path claim and an OS advisory lock for the storage
lifetime. Only an absent data root is considered new and may receive one empty-ledger bootstrap;
an existing database with a missing singleton row fails closed. Startup reconciliation runs under that
exclusive ownership before an operational port can escape. `Ready` exposes storage only after metadata
and every referenced payload verify; `Degraded` exposes a redacted report, never usable storage.

## Linux/JVM candidate

The Raspberry Pi adapter may use SQLDelight or a direct SQLite/JDBC implementation plus a qualified
SQLCipher build, and a system key store or provisioned sealed secret for file keys. Selection waits for
the Linux deployment and threat model; a desktop fallback key file is not production key protection.

[SQLDelight](https://cashapp.github.io/sqldelight/) remains useful for compile-time SQL and migration
checks across Android/JVM, but it is adopted only if its concrete encrypted drivers reduce total
complexity. Sharing a generated query layer is optional. Sharing runtime invariants and conformance
tests is mandatory.

## Encrypted payload envelope

The Android candidate produces a small versioned envelope with:

- format and key versions;
- a randomly generated GCM nonce that is never reused with the same key;
- ciphertext and authentication tag;
- authenticated binding to capture, stream, chunk identity, declared length, and SHA-256 digest; and
- no plaintext media, device address, credential, owner identity, or provider storage key in its path.

The candidate reader is versioned and supports old-version reads, but production composition is fixed
to key version 1. Rotation is deliberately not exposed because no durable active-key policy or
interruption protocol exists. A custom cipher construction remains forbidden; the adapter uses
platform cryptographic primitives and a standard AEAD mode.

AEAD proves integrity and descriptor binding, not freshness. A privileged replay of a complete older
valid metadata row can remain cryptographically valid. Backup and device transfer are disabled and the
payload store uses `noBackupFilesDir`, but those are not an anti-rollback freshness anchor. Production
must decide whether privileged full-snapshot rollback is in scope before claiming rollback resistance.

## Current executable evidence

Local JVM tests cover strict encoding, AEAD tamper and cross-descriptor replay, fresh nonces, immutable
idempotency, compare-and-swap/unknown outcomes, one-shot bootstrap, complete-row rollback semantics,
exclusive ownership, startup verification, quota mapping, and `Ready` versus `Degraded` opening. These
tests do not execute Android Keystore, SQLite, or `Os.fsync`.

The original five `AndroidEncryptedSpoolInstrumentationTest` cases pass on an API 36 ARM64 emulator. They exercise the real
no-backup path, Keystore AES/HMAC, SQLite WAL/CAS with FULL synchronous behavior, temporary-file flush
plus atomic rename and directory flush, exclusive concurrent-open rejection, release/reopen recovery,
active-key loss, missing-row fail-closed behavior, degraded missing-payload opening, and an on-disk
plaintext-marker scan. This is local Android-framework evidence, not owned-handset/OEM evidence; the
forced-death/reboot and production qualification matrix below remains open. Two later cases covering
cancellation immediately after ownership and operational-adapter exclusivity/reopen compile but have
not yet run on an Android target; the next target invocation executes all seven.

## Required acceptance witnesses

The adapter is not production-qualified until all of these pass on the actual target:

1. Kill the process before payload creation, during encryption, before and after file flush, before and
   after metadata commit, and after an unknown commit outcome.
2. Reboot the host at the same boundaries and recover the exact absolute source checkpoint.
3. Reject truncated, bit-flipped, swapped, replayed-under-another-descriptor, and wrong-key bodies
   before any upload or source advance.
4. Prove duplicate immutable writes converge and conflicting bytes never replace the first binding.
5. Design and then rotate keys while old chunks remain readable, new chunks use the new key, and
   interruption is recoverable without rewriting source truth; the current adapter intentionally
   exposes no production rotation operation.
6. Upgrade and roll back metadata schema through checked migrations without silently deleting a
   capture, chunk, acknowledgement, terminal range, or payload reference.
7. Exercise quota pressure, orphan collection, application uninstall/reinstall, backup/restore policy,
   device-lock/reboot key availability, and Keystore key invalidation.
8. Show that diagnostics, crashes, paths, database journals, and telemetry contain no raw media or key
   material.

The common in-memory stores are deterministic test infrastructure only. Passing their tests proves the
state machine, not filesystem durability, encryption, or platform recovery.

## Rejected shortcuts

- Passing a caller-created "durable" receipt into the runtime.
- Storing raw media in Room/SQLite BLOBs for convenience.
- SharedPreferences, DataStore, or a JSON snapshot as the source/checkpoint transaction ledger.
- One unversioned application password embedded in code or stored beside the encrypted database.
- Treating Android file-private permissions, `allowBackup=false`, or full-disk encryption alone as the
  media encryption boundary.
- Advancing the Omi ring cursor after a file write but before the metadata/checkpoint commit.
