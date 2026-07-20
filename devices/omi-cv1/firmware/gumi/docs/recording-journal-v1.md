# Gumi recording journal v1

Status: portable framing and recovery kernel implemented and host-qualified. The PSA Crypto and Zephyr
SD adapters are not implemented or target-qualified yet. This format is not a firmware or flash
candidate by itself.

## Boundary

The journal is the device-local durable representation of one capture session. It does not own button
policy, microphone state, Opus encoding, key provisioning, filesystem mounting, BLE transfer, deletion,
or retention. Its inputs are one session identity, one recording identity, exact codec metadata, and
AES-256-GCM protected record payloads.

The implementation deliberately reuses the platform primitives that fit:

- upstream Omi's proven SPI SD slot, power GPIO, and board device tree;
- Zephyr's filesystem, FATFS, `fs_sync`, `fs_statvfs`, and disk-control APIs;
- Nordic's PSA Crypto AES-GCM implementation; and
- the bundled upstream Opus encoder already used by the codec port.

Gumi does not reuse the stock Omi application storage layer. In v3.0.12 it explicitly calls `fs_mkfs`
after a mount failure, allocates filenames on the heap, ignores short/error writes in the transport
path, has no final partial-batch commit, and exposes destructive storage commands through an
unencrypted GATT characteristic.

## Binary format

Every integer is unsigned little-endian. Writers emit canonical values; readers reject unknown record
kinds, sequence gaps, noncanonical sizes, counter overflow, and trailing bytes. CRC32C detects torn or
accidentally corrupted structures but is never treated as authentication.

The 64-byte file header is:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | `GUMIJNL1` magic |
| 8 | 2 | format version, `1` |
| 10 | 2 | header size, `64` |
| 12 | 2 | codec, `1` = Opus |
| 14 | 2 | protection, `1` = AES-256-GCM v1 |
| 16 | 8 | nonzero session ID |
| 24 | 8 | nonzero recording ID |
| 32 | 4 | sample rate |
| 36 | 4 | PCM samples per frame |
| 40 | 2 | maximum plaintext codec payload |
| 42 | 2 | GCM tag size, `16` |
| 44 | 4 | nonzero device-local key ID/version |
| 48 | 12 | per-session random nonce base |
| 60 | 4 | CRC32C of bytes 0 through 59 |

Each record starts with this 48-byte header:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | `GMR1` magic |
| 4 | 1 | record version, `1` |
| 5 | 1 | kind: `1` audio, `2` commit |
| 6 | 2 | record-header size, `48` |
| 8 | 8 | session ID |
| 16 | 8 | monotonically increasing journal ordinal |
| 24 | 8 | source Opus packet sequence, or final source sequence for commit |
| 32 | 4 | PCM sample count; zero for commit |
| 36 | 4 | protected payload size including the 16-byte GCM tag |
| 40 | 4 | CRC32C of the protected payload |
| 44 | 4 | CRC32C of bytes 0 through 43 |

Audio plaintext is one Opus packet. It is never passed to the journal commit function. The caller first
asks for a plan, encrypts the packet through PSA Crypto using the plan's nonce and authenticated data,
then commits only the resulting ciphertext and tag. An ordinal cannot advance on crypto failure, short
output, queue exhaustion, a short filesystem write, or a stale plan.

The 100-byte authenticated-data value is the canonical 60-byte file-header prefix followed by the
canonical first 40 bytes of the record header. The 96-bit per-record nonce is the random file nonce base
with the 64-bit ordinal, encoded big-endian, XORed into its final eight bytes. A key and nonce base may
never be reused. Session/nonce generation therefore requires the platform CSPRNG and must fail closed.

The commit plaintext is 32 bytes: audio-record count, PCM-sample count, final source sequence, the
CRC32C chain over all protected audio records, and four zero reserved bytes. The commit is itself
AES-GCM protected with its own ordinal. It authenticates the complete ordered prefix without exposing
recording counts in plaintext.

## FATFS lifecycle

The Zephyr adapter must be one fixed-allocation writer thread. Codec callbacks copy bounded packets into
its queue and return; they never block on SD, crypto, sync, or lifecycle work. Queue exhaustion is a
durability fault and triggers a safe capture stop instead of dropping audio silently.

The intended on-card layout stays compatible with FAT 8.3 names:

```text
/SD:/GUMI/
  XXXXXXXX.PRT   open or interrupted session
  XXXXXXXX.GMR   committed immutable session
```

`XXXXXXXX` is a CSPRNG-derived name token. Both names must be absent before creation. The header, not
the filename, is identity truth. Collisions cause a new token; rename is never allowed to replace an
existing destination.

Preparation is successful only after the adapter has:

1. powered and initialized the existing SD controller;
2. mounted with `FS_MOUNT_FLAG_NO_FORMAT` and without any explicit formatting fallback;
3. authenticated every retained `.GMR` and recoverable `.PRT` prefix needed for admission;
4. checked free capacity with `fs_statvfs` against the qualified warning and finalization reserve;
5. acquired the recording key and fresh nonce/session material;
6. created a new `.PRT`, written the exact 64-byte header, called `fs_sync`, and issued
   `DISK_IOCTL_CTRL_SYNC`.

For every write, a negative or short `fs_write` is fatal. Periodic sync cadence is a measured power,
latency, and loss-window policy; it is not hard-coded by the format. Normal finalization drains the
codec and storage queues, appends and authenticates the commit record, syncs the file, syncs the disk,
closes the file, verifies that the `.GMR` destination is absent, renames `.PRT` to `.GMR`, and syncs the
disk again. Only then may the capture supervisor receive `LOCAL_RECORDING_FINALIZED`.

If durability fails during active capture, the adapter gates new packets, syncs the last complete
authenticated record prefix if possible, leaves the file as `.PRT`, and reports
`LAST_DURABLE_FRAME_COMMITTED`. Recovery can later expose the exact authenticated prefix without
pretending the interrupted recording has a commit record.

Mount, structural, or AES-GCM failure is `CORRUPT`, not “empty.” `LOW` and `FULL` are based on a
qualified remaining recording/stop budget rather than a guessed percentage. No boot path formats,
deletes, truncates, or renames a recovered file automatically. Destructive retention is a separate,
locally authorized maintenance operation and is never an unauthenticated GATT command.

## Executable gate

Run:

```sh
devices/omi-cv1/firmware/gumi/recording-journal.test.sh
```

The current gate covers the CRC32C standard vector, canonical header round-trip, mandatory encryption
metadata, nonce/AAD separation, two-frame recovery and encrypted commit, sequence and codec bounds,
transactional failure, structural corruption, truncated-tail recovery, authentication failure, commit
summary mismatch, empty recordings, counter exhaustion, and overlapping caller buffers. Its fake
authenticated transform exists only to exercise the crypto-port contract; it is explicitly not a
cryptographic implementation.
