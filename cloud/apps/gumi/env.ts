/** Cloudflare bindings and secrets available to the domain worker. */
export interface Env {
  WORKER_URL?: string
  ASSETS?: { fetch(request: Request): Promise<Response> }
  SELF?: { fetch(request: Request): Promise<Response> }
  VIEW_DEV_URL?: string
  [key: string]: unknown
}
