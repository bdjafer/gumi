# Core

Pure, deterministic domain logic only. Organize it by bounded context and keep kernel calls, network
I/O, clocks, randomness, and environment access in `runtime/` or `integrations/`.

Paths, domain errors, defaults, validation, and projections belong with the bounded context that owns
them. Delete this file after adding the first context.
