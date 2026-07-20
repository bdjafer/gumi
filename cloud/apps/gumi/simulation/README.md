# Simulation

Keep domain-level samples and real business scenarios here, organized by bounded context:

```text
simulation/
  samples/<context>/
  scenarios/<business-flow>.test.ts
```

Use Vitest for scenarios. Prefer the real schema, handlers, and domain-owned invariant-enforcing kernel
double; fake only external systems through their narrow ports. Include denial, replay, concurrent path
conflict, malformed publisher data, and interrupted multi-stage flows. Demo data must never leak into
core data, production dependencies, or install hooks.
