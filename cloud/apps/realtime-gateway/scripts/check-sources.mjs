import { readdir } from 'node:fs/promises'
import { extname, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

async function modules(root) {
  const result = []
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = join(root, entry.name)
    if (entry.isDirectory()) result.push(...(await modules(path)))
    else if (extname(path) === '.mjs') result.push(path)
  }
  return result
}

const sourceRoot = resolve(import.meta.dirname, '..', 'src')
const files = (await modules(sourceRoot)).sort()
if (files.length < 10) throw new Error('realtime-gateway source set is unexpectedly incomplete')
for (const file of files) await import(pathToFileURL(file))

process.stdout.write(`loaded ${files.length} ESM source modules\n`)
