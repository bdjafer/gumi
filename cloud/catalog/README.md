# Cloud application catalog

Every direct child of `cloud/apps` must own an `app.json` conforming to
[`application-manifest.schema.json`](application-manifest.schema.json). The manifest describes the
application's durable authority, sensitive data classes, tenancy posture, published contracts,
application dependencies, deployment status, retention status, and runbook.

Run the verifier directly with:

```bash
node cloud/catalog/verify-manifests.mjs cloud/apps
```

The root [`verify-apps.sh`](../verify-apps.sh) runs it before invoking any application package, and
[`install-apps.sh`](../install-apps.sh) runs it before installing dependencies in an application
directory. The validator is dependency-free and additionally checks local contract/runbook paths,
app-directory ID agreement, referenced app/contract existence, and an acyclic dependency graph.

The catalog never becomes a root contract store. Publisher-owned OpenAPI, Astrale schema, and
realtime contracts remain inside the publishing application.
