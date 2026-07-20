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

function sourceFiles(root) {
  return files(root).filter((path) => extname(path) === '.mjs')
}

test('media-ingest remains dependency-light and never imports another cloud application internals', () => {
  const packageJson = JSON.parse(readFileSync(join(APP, 'package.json'), 'utf8'))
  assert.deepEqual(packageJson.dependencies ?? {}, {})
  assert.deepEqual(packageJson.devDependencies ?? {}, {})

  for (const file of sourceFiles(join(APP, 'src'))) {
    const source = readFileSync(file, 'utf8')
    assert.doesNotMatch(source, /media-processing|cloud\/apps\//, relative(APP, file))
    for (const match of source.matchAll(/from\s+['"]([^'"]+)['"]/g)) {
      const specifier = match[1]
      assert.ok(specifier.startsWith('.') || specifier.startsWith('node:'), `${relative(APP, file)}: ${specifier}`)
    }
  }
})

test('the provider-neutral core has no HTTP, network, process, filesystem, database, or provider dependency', () => {
  const source = sourceFiles(join(APP, 'src', 'core'))
    .map((path) => readFileSync(path, 'utf8'))
    .join('\n')
  assert.doesNotMatch(source, /node:(?:fs|http|https|net|tls|child_process|worker_threads)|\bfetch\s*\(|process\.env/)
  assert.doesNotMatch(source, /@aws-sdk|@google-cloud|postgres|sqlite|redis|kafka|amqp/i)
})

test('the HTTP adapter never composes test persistence or a permissive default authorizer', () => {
  const source = sourceFiles(join(APP, 'src', 'http'))
    .map((path) => readFileSync(path, 'utf8'))
    .join('\n')
  assert.doesNotMatch(source, /testing\/|InMemoryStorage|InMemoryHttpAuthorizer|DeterministicTokenPort/)
  assert.doesNotMatch(source, /process\.env|node:fs|@aws-sdk|@google-cloud|postgres|sqlite|redis/i)
  assert.match(source, /authorizer\.authenticate/)
  assert.match(source, /media-ingest:control/)
  assert.match(source, /media-ingest:data/)
})

test('every published OpenAPI operation is explicitly routed by the HTTP adapter', () => {
  const openapi = JSON.parse(readFileSync(join(APP, 'api', 'v1', 'openapi.json'), 'utf8'))
  const server = readFileSync(join(APP, 'src', 'http', 'server.mjs'), 'utf8')
  const operations = Object.values(openapi.paths).flatMap((path) =>
    Object.values(path).map((operation) => operation.operationId),
  )
  assert.equal(new Set(operations).size, 6)
  for (const operationId of operations) {
    assert.match(server, new RegExp(`['"]${operationId}['"]`), operationId)
  }
})

test('ordinary HTTP logs are constructed from an allowlist and never receive raw request objects or errors', () => {
  const server = readFileSync(join(APP, 'src', 'http', 'server.mjs'), 'utf8')
  const logBuilder = /function requestLog[\s\S]*?return record\n}/.exec(server)?.[0]
  assert.ok(logBuilder)
  assert.doesNotMatch(logBuilder, /headers|authorization|bearer|token|body|payload|response|error\b/i)
  assert.doesNotMatch(server, /logger\?\.\w+\?\.\((?:request|response|error)\)/)
})
