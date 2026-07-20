# Integrations

External-system ports and adapters live here, grouped by the system they integrate with. Runtime code
depends on narrow capabilities; adapters own network I/O, credentials, and vendor SDKs.

Each publisher keeps its OpenAPI/JSON Schema contract inside its own cloud application. Gumi checks in
only generated consumer declarations, strict runtime trust-boundary schemas, and explicit test fakes.
The media-ingest capability is capture/device/edge-host bound. The media-processing capability is
Recording/input-digest bound and separately checks output digest, artifact identity, and page start.
Unavailable production adapters fail closed; fixture fakes are never selected by `deps.ts`.
