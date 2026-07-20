import type { Env } from '#env'
import type { MediaIngest } from '#integrations/media-ingest/media-ingest.port'
import { unavailableMediaIngest } from '#integrations/media-ingest/media-ingest.port'
import type { MediaProcessing } from '#integrations/media-processing/media-processing.port'
import { unavailableMediaProcessing } from '#integrations/media-processing/media-processing.port'

/**
 * Narrow effect capabilities available to callables. Raw environment bindings and secrets are
 * composed here and are never spread into every runtime method as ambient authority.
 */
export interface Deps {
  mediaIngest: MediaIngest
  mediaProcessing: MediaProcessing
}

/** Build long-lived adapter factories here; request-scoped capabilities are issued inside calls. */
export function deps(env: Env): Deps {
  void env
  return {
    mediaIngest: unavailableMediaIngest,
    mediaProcessing: unavailableMediaProcessing,
  }
}
