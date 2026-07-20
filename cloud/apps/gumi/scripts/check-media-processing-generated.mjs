import { readFile } from 'node:fs/promises'
import openapiTS, { astToString, COMMENT_HEADER } from 'openapi-typescript'

const contractUrl = new URL('../../media-processing/api/v1/openapi.json', import.meta.url)
const generatedUrl = new URL(
  '../integrations/media-processing/generated/media-processing-v1.d.ts',
  import.meta.url,
)

const expected = COMMENT_HEADER + astToString(await openapiTS(contractUrl, { immutable: true }))
const actual = await readFile(generatedUrl, 'utf8')

if (actual !== expected) {
  console.error(
    'Generated media-processing types are stale. Run `pnpm generate:media-processing` in cloud/apps/gumi.',
  )
  process.exitCode = 1
}
