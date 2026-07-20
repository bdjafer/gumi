/**
 * astrale.config.ts — binds the worker-safe domain (`domain.ts`) to the managed
 * Astrale deploy adapter for the `astrale-domain` CLI. This is a NODE-only
 * module; the generated worker imports `domain.ts` directly, never this file.
 *
 * This variant ships THROUGH the Astrale platform (the managed `astrale`
 * adapter) — no Cloudflare account needed.
 *
 *   pnpm dev                       # wrangler dev → prints a local URL
 *   astrale domain install <url> --direct # mount the dev URL on an instance
 *   pnpm prod                      # managed deploy → prints a URL + installs
 */
import { astrale } from '@astrale-os/adapter-astrale'
import { deploy } from '@astrale-os/sdk'
// Have your own Cloudflare account and prefer deploying there directly? Swap
// the adapter — same bundle, shipped with wrangler under YOUR account (then
// mount it yourself: `astrale domain install <url> --direct`):
//
//   import { cloudflare } from '@astrale-os/adapter-cloudflare'
//   export default deploy(domain, cloudflare({
//     dev: { client: { dir: 'client' }, secrets: '.env.dev' },
//     prod: { client: { dir: 'client' }, route: 'gumi.astrale.ai', secrets: '.env.prod' },
//   }))

import { domain } from './domain'

export default deploy(
  domain,
  astrale({
    // Local dev: `wrangler dev`. No route → URL is http://localhost:8787.
    dev: { client: { dir: 'client' }, secrets: '.env.dev' },
    // Managed deploy: `pnpm prod` publishes the bundle through the platform and
    // installs the domain on this instance. The slug comes from
    // `astrale instance create <slug>` / `astrale instance status`.
    prod: { client: { dir: 'client' }, instance: 'my-instance-slug' },
    // Author secrets ship via `secrets: '.env.prod'` on any env (encrypted at
    // rest platform-side, re-applied on redeploys; omit = keep, `{}` = clear).
  }),
)
