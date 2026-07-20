# Astrale domain

Load the `astrale-domain` skill before changing schema, runtime, views, integrations, security, or
simulation code. Install it user-level if needed:

```bash
npx skills add astrale-os/cli -g
```

This scaffold is intentionally empty. Organize schema, runtime, core, simulation, and client code by
real bounded contexts. Keep I/O out of `core/`, use one explicit file per method or standalone function,
wrap nondeterministic handler work in `step`, derive graph keys and paths from the compiled schema, and
batch graph reads or mutations where possible.
