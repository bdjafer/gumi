import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, extname, join, relative, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const APP = resolve(dirname(fileURLToPath(import.meta.url)), '..')

function files(root) {
  const result = []
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name)
    if (entry.isDirectory()) result.push(...files(path))
    else result.push(path)
  }
  return result
}

test('media-processing is dependency-light and never imports another cloud application internals', () => {
  const packageJson = JSON.parse(readFileSync(join(APP, 'package.json'), 'utf8'))
  assert.deepEqual(packageJson.dependencies ?? {}, {})
  assert.deepEqual(packageJson.devDependencies ?? {}, {})

  for (const file of files(join(APP, 'src')).filter((path) => extname(path) === '.mjs')) {
    const source = readFileSync(file, 'utf8')
    assert.doesNotMatch(source, /media-ingest|cloud\/apps\//, relative(APP, file))
    for (const match of source.matchAll(/from\s+['"]([^'"]+)['"]/g)) {
      const specifier = match[1]
      assert.ok(specifier.startsWith('.') || specifier.startsWith('node:'), `${relative(APP, file)}: ${specifier}`)
    }
  }
})

test('the core has no network, process, filesystem, database, queue, or provider SDK dependency', () => {
  const source = files(join(APP, 'src', 'core'))
    .filter((path) => extname(path) === '.mjs')
    .map((path) => readFileSync(path, 'utf8'))
    .join('\n')
  assert.doesNotMatch(source, /node:(?:fs|http|https|net|tls|child_process|worker_threads)|\bfetch\s*\(|process\.env/)
  assert.doesNotMatch(source, /@aws-sdk|@google-cloud|openai|deepgram|assemblyai|postgres|sqlite|redis|kafka|amqp/i)
})
