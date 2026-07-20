import { readFile, readdir, realpath, stat } from 'node:fs/promises'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'

const APP_ID = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/
const KINDS = new Set(['astrale-domain', 'http-service', 'realtime-service', 'worker'])
const DATA_CLASSES = new Set([
  'semantic-metadata',
  'raw-media',
  'derived-content',
  'operational-metadata',
])
const PROTOCOLS = new Set(['astrale-domain', 'http-openapi', 'realtime'])
const DEPLOYMENT_STATUSES = new Set(['local-only', 'selected', 'deployed'])
const RETENTION_STATUSES = new Set(['unselected', 'defined', 'enforced'])
const TENANCY_SHAPES = new Map([
  ['astrale-instance', { sharedDeployment: false, scopeSource: 'kernel-instance' }],
  ['dedicated-deployment', { sharedDeployment: false, scopeSource: 'deployment-binding' }],
  ['authenticated-instance', { sharedDeployment: true, scopeSource: 'authenticated-claim' }],
])

function fail(file, field, message) {
  throw new Error(`${file}: ${field} ${message}`)
}

function object(file, field, value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(file, field, 'must be an object')
  }
  return value
}

function exactKeys(file, field, value, expected) {
  const actual = Object.keys(value).sort()
  const wanted = [...expected].sort()
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    fail(file, field, `must contain exactly: ${wanted.join(', ')}`)
  }
}

function string(file, field, value, maxLength = 240) {
  if (typeof value !== 'string' || value.length === 0 || value.length > maxLength) {
    fail(file, field, `must be a non-empty string no longer than ${maxLength} characters`)
  }
  return value
}

function identifier(file, field, value) {
  string(file, field, value, 64)
  if (!APP_ID.test(value)) fail(file, field, 'must be a lower-kebab identifier')
  return value
}

function array(file, field, value, { min = 0, max = Number.MAX_SAFE_INTEGER } = {}) {
  if (!Array.isArray(value) || value.length < min || value.length > max) {
    fail(file, field, `must be an array with ${min}..${max} entries`)
  }
  return value
}

function unique(file, field, values) {
  if (new Set(values).size !== values.length) fail(file, field, 'must not contain duplicates')
}

async function localFile(manifestFile, appRoot, field, value) {
  const path = string(manifestFile, field, value, 160)
  if (isAbsolute(path) || path.split(/[\\/]/).includes('..')) {
    fail(manifestFile, field, 'must be an app-relative path without parent traversal')
  }
  const candidate = resolve(appRoot, path)
  let resolved
  try {
    resolved = await realpath(candidate)
  } catch {
    fail(manifestFile, field, `does not exist: ${path}`)
  }
  const escape = relative(appRoot, resolved)
  if (escape === '..' || escape.startsWith(`..${sep}`) || isAbsolute(escape)) {
    fail(manifestFile, field, 'must resolve inside the application directory')
  }
  if (!(await stat(resolved)).isFile()) fail(manifestFile, field, 'must name a file')
}

async function validateManifest(appRoot) {
  const manifestFile = join(appRoot, 'app.json')
  let manifest
  try {
    manifest = JSON.parse(await readFile(manifestFile, 'utf8'))
  } catch (error) {
    if (error?.code === 'ENOENT') throw new Error(`${manifestFile}: application manifest is required`)
    throw new Error(`${manifestFile}: invalid JSON: ${error.message}`)
  }

  object(manifestFile, 'manifest', manifest)
  exactKeys(manifestFile, 'manifest', manifest, [
    '$schema',
    'schemaVersion',
    'appId',
    'kind',
    'owner',
    'summary',
    'durableAuthority',
    'dataClasses',
    'tenancy',
    'contracts',
    'dependencies',
    'deployment',
    'retention',
    'runbook',
  ])
  if (manifest.$schema !== '../../catalog/application-manifest.schema.json') {
    fail(manifestFile, '$schema', 'must point to ../../catalog/application-manifest.schema.json')
  }
  if (manifest.schemaVersion !== 1) fail(manifestFile, 'schemaVersion', 'must equal 1')
  identifier(manifestFile, 'appId', manifest.appId)
  if (manifest.appId !== basename(appRoot)) fail(manifestFile, 'appId', 'must match its directory name')
  if (!KINDS.has(manifest.kind)) fail(manifestFile, 'kind', 'is unsupported')
  identifier(manifestFile, 'owner', manifest.owner)
  string(manifestFile, 'summary', manifest.summary)

  const authorities = array(manifestFile, 'durableAuthority', manifest.durableAuthority, {
    min: 1,
    max: 12,
  })
  authorities.forEach((value, index) => string(manifestFile, `durableAuthority[${index}]`, value, 160))
  unique(manifestFile, 'durableAuthority', authorities)

  const dataClasses = array(manifestFile, 'dataClasses', manifest.dataClasses, { min: 1, max: 4 })
  dataClasses.forEach((value) => {
    if (!DATA_CLASSES.has(value)) fail(manifestFile, 'dataClasses', `contains unsupported value ${value}`)
  })
  unique(manifestFile, 'dataClasses', dataClasses)

  const tenancy = object(manifestFile, 'tenancy', manifest.tenancy)
  exactKeys(manifestFile, 'tenancy', tenancy, ['mode', 'sharedDeployment', 'scopeSource'])
  const tenancyShape = TENANCY_SHAPES.get(tenancy.mode)
  if (!tenancyShape) fail(manifestFile, 'tenancy.mode', 'is unsupported')
  if (
    tenancy.sharedDeployment !== tenancyShape.sharedDeployment ||
    tenancy.scopeSource !== tenancyShape.scopeSource
  ) {
    fail(manifestFile, 'tenancy', `does not match the ${tenancy.mode} isolation contract`)
  }

  const contracts = array(manifestFile, 'contracts', manifest.contracts, { min: 1, max: 16 })
  const contractIds = []
  for (const [index, rawContract] of contracts.entries()) {
    const contract = object(manifestFile, `contracts[${index}]`, rawContract)
    exactKeys(manifestFile, `contracts[${index}]`, contract, ['id', 'protocol', 'version', 'path'])
    contractIds.push(identifier(manifestFile, `contracts[${index}].id`, contract.id))
    if (!PROTOCOLS.has(contract.protocol)) {
      fail(manifestFile, `contracts[${index}].protocol`, 'is unsupported')
    }
    string(manifestFile, `contracts[${index}].version`, contract.version, 32)
    await localFile(manifestFile, appRoot, `contracts[${index}].path`, contract.path)
  }
  unique(manifestFile, 'contracts[].id', contractIds)

  const dependencies = array(manifestFile, 'dependencies', manifest.dependencies, { max: 64 })
  const dependencyKeys = []
  for (const [index, rawDependency] of dependencies.entries()) {
    const dependency = object(manifestFile, `dependencies[${index}]`, rawDependency)
    exactKeys(manifestFile, `dependencies[${index}]`, dependency, ['appId', 'contractId', 'purpose'])
    identifier(manifestFile, `dependencies[${index}].appId`, dependency.appId)
    identifier(manifestFile, `dependencies[${index}].contractId`, dependency.contractId)
    string(manifestFile, `dependencies[${index}].purpose`, dependency.purpose, 160)
    if (dependency.appId === manifest.appId) fail(manifestFile, `dependencies[${index}]`, 'cannot target itself')
    dependencyKeys.push(`${dependency.appId}:${dependency.contractId}`)
  }
  unique(manifestFile, 'dependencies', dependencyKeys)

  const deployment = object(manifestFile, 'deployment', manifest.deployment)
  exactKeys(manifestFile, 'deployment', deployment, ['status', 'target', 'regions'])
  if (!DEPLOYMENT_STATUSES.has(deployment.status)) fail(manifestFile, 'deployment.status', 'is unsupported')
  if (deployment.target !== null) string(manifestFile, 'deployment.target', deployment.target, 160)
  const regions = array(manifestFile, 'deployment.regions', deployment.regions, { max: 32 })
  regions.forEach((value, index) => string(manifestFile, `deployment.regions[${index}]`, value, 64))
  unique(manifestFile, 'deployment.regions', regions)
  if (deployment.status === 'local-only' && (deployment.target !== null || regions.length !== 0)) {
    fail(manifestFile, 'deployment', 'local-only applications must have a null target and no regions')
  }
  if (deployment.status !== 'local-only' && (deployment.target === null || regions.length === 0)) {
    fail(manifestFile, 'deployment', 'selected/deployed applications must name a target and region')
  }

  const retention = object(manifestFile, 'retention', manifest.retention)
  exactKeys(manifestFile, 'retention', retention, ['status', 'class', 'policyId'])
  if (!RETENTION_STATUSES.has(retention.status)) fail(manifestFile, 'retention.status', 'is unsupported')
  identifier(manifestFile, 'retention.class', retention.class)
  if (retention.policyId !== null) identifier(manifestFile, 'retention.policyId', retention.policyId)
  if (retention.status === 'unselected' && retention.policyId !== null) {
    fail(manifestFile, 'retention', 'unselected retention must have a null policyId')
  }
  if (retention.status !== 'unselected' && retention.policyId === null) {
    fail(manifestFile, 'retention', 'defined/enforced retention must name a policyId')
  }

  await localFile(manifestFile, appRoot, 'runbook', manifest.runbook)
  return { file: manifestFile, manifest, contractIds: new Set(contractIds) }
}

function findCycle(entries) {
  const byId = new Map(entries.map((entry) => [entry.manifest.appId, entry]))
  const visiting = new Set()
  const visited = new Set()
  const path = []

  function visit(appId) {
    if (visiting.has(appId)) return [...path.slice(path.indexOf(appId)), appId]
    if (visited.has(appId)) return null
    visiting.add(appId)
    path.push(appId)
    for (const dependency of byId.get(appId).manifest.dependencies) {
      const cycle = visit(dependency.appId)
      if (cycle) return cycle
    }
    path.pop()
    visiting.delete(appId)
    visited.add(appId)
    return null
  }

  for (const appId of [...byId.keys()].sort()) {
    const cycle = visit(appId)
    if (cycle) return cycle
  }
  return null
}

export async function verifyApplicationManifests(appsRoot) {
  const resolvedRoot = await realpath(resolve(appsRoot))
  const dirs = (await readdir(resolvedRoot, { withFileTypes: true }))
    .filter((entry) => entry.isDirectory() && !entry.name.startsWith('.'))
    .map((entry) => join(resolvedRoot, entry.name))
    .sort()
  if (dirs.length === 0) throw new Error(`${resolvedRoot}: no applications discovered`)

  const entries = []
  for (const dir of dirs) entries.push(await validateManifest(dir))
  const byId = new Map(entries.map((entry) => [entry.manifest.appId, entry]))
  for (const entry of entries) {
    for (const dependency of entry.manifest.dependencies) {
      const target = byId.get(dependency.appId)
      if (!target) fail(entry.file, 'dependencies', `references missing application ${dependency.appId}`)
      if (!target.contractIds.has(dependency.contractId)) {
        fail(
          entry.file,
          'dependencies',
          `references missing contract ${dependency.appId}:${dependency.contractId}`,
        )
      }
    }
  }

  const cycle = findCycle(entries)
  if (cycle) throw new Error(`${resolvedRoot}: application dependency cycle: ${cycle.join(' -> ')}`)
  return entries.map((entry) => entry.manifest)
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  const appsRoot = process.argv[2] ?? resolve(dirname(import.meta.filename), '..', 'apps')
  try {
    const manifests = await verifyApplicationManifests(appsRoot)
    process.stdout.write(`Verified ${manifests.length} cloud application manifest(s).\n`)
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 1
  }
}
