# Gumi Omi CV1 legacy-storage reclaimer GATT profile

This profile describes the read-only evidence surface exposed by the bounded
`legacy-storage-reclaimer-0002` firmware. The firmware has no caller-controlled
filesystem path and never formats the volume. It may unlink only
`/SD:/audio/a01.txt`, and only after observing that it is a regular file whose
size is exactly 505,118,720 bytes.

The Android Flash Lab must accept the destructive operation as successful only
when the terminal status is `reclaimed` or `already_absent`, the exact
state-specific flags are present, `last_error` is zero, the target is absent,
at least 4 MiB is free, the microphone is verified off, and follow-up mutation
has been admitted after storage shutdown. Version 0002 keeps SD power asserted
after unmount/suspend because the SD card and MCUboot secondary flash share
SPI3; this is required for a real follow-up external-flash erase/write.

`refused` and `failed` are evidence-bearing terminal states, but they must never
authorize installation of functional firmware. They exist so the operator can
return to the separately qualified recovery image.
