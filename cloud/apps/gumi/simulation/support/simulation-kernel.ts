import { AbsolutePath, rawOf, type PathLike } from '@astrale-os/kernel-core'

import type { Kernel } from '@astrale-os/sdk'

export interface SimulationNode {
  id: string
  class: { raw: string }
  path: AbsolutePath
  props: Record<string, unknown>
}

export interface SimulationEdge {
  source: string
  edge: string
  target: string
}

/** Small domain-owned graph double that preserves atomic path and edge conflicts. */
export class SimulationKernel {
  readonly nodes = new Map<string, SimulationNode>()
  readonly edges: SimulationEdge[] = []
  readonly authorizationChecks: Array<Record<string, unknown>> = []
  private nextId = 1

  constructor(private readonly allow = true) {}

  seed(
    path: string,
    className = 'kernel:class.Folder',
    props: Record<string, unknown> = {},
  ): SimulationNode {
    if (this.nodes.has(path)) throw new Error(`PATH_CONFLICT: ${path}`)
    const node: SimulationNode = {
      id: `simulation-node-${this.nextId++}`,
      class: { raw: className },
      path: AbsolutePath.parse(path),
      props: { ...props },
    }
    this.nodes.set(path, node)
    return node
  }

  link(source: unknown, edge: string, target: unknown): void {
    const candidate = { source: refOf(source), edge, target: refOf(target) }
    if (this.hasEdge(candidate)) throw new Error(`EDGE_CONFLICT: ${edge}`)
    this.edges.push(candidate)
  }

  init(): Kernel {
    return {
      auth: {
        require: async (input: Record<string, unknown>) => {
          this.authorizationChecks.push(input)
          if (!this.allow) throw new Error('PERMISSION_DENIED')
        },
      },
      get: async (path: PathLike) => this.resolve(path),
      getOrThrow: async (path: PathLike, context?: string) => {
        const node = this.resolve(path)
        if (!node) throw new Error(context ?? `Missing node ${rawOf(path)}`)
        return node
      },
      neighbors: async (path: PathLike, edge: string, direction: 'in' | 'out' | 'both') => {
        const node = this.resolve(path)
        const reference = node?.path.raw ?? rawOf(path)
        const refs = this.edges.flatMap((candidate) => {
          if (candidate.edge !== edge) return []
          if (direction !== 'in' && candidate.source === reference) return [candidate.target]
          if (direction !== 'out' && candidate.target === reference) return [candidate.source]
          return []
        })
        return {
          all: async () => refs.map((ref) => {
            const linked = this.resolve(ref)
            if (!linked) throw new Error(`Missing linked node ${ref}`)
            return linked
          }),
        }
      },
      mutate: async (
        build: (mutation: {
          createNode(className: string, path: PathLike, props: Record<string, unknown>): SimulationNode
          link(source: unknown, edge: string, target: unknown): void
          updateNode(className: string, path: PathLike, props: Record<string, unknown>): void
        }) => void,
      ) => {
        const created: SimulationNode[] = []
        const linked: SimulationEdge[] = []
        const updated: Array<{ node: SimulationNode; props: Record<string, unknown> }> = []
        build({
          createNode: (className, path, props) => {
            const value = rawOf(path)
            if (this.nodes.has(value) || created.some((candidate) => candidate.path.raw === value)) {
              throw new Error(`PATH_CONFLICT: ${value}`)
            }
            const node: SimulationNode = {
              id: `simulation-node-${this.nextId++}`,
              class: { raw: className },
              path: AbsolutePath.parse(value),
              props: { ...props },
            }
            created.push(node)
            return node
          },
          link: (source, edge, target) => {
            const candidate = { source: refOf(source), edge, target: refOf(target) }
            if (
              this.hasEdge(candidate) ||
              linked.some((existing) => sameEdge(existing, candidate))
            ) {
              throw new Error(`EDGE_CONFLICT: ${edge}`)
            }
            linked.push(candidate)
          },
          updateNode: (className, path, props) => {
            const node = this.resolve(path)
            if (!node || node.class.raw !== className) {
              throw new Error(`Missing update target ${rawOf(path)}`)
            }
            updated.push({ node, props: { ...props } })
          },
        })
        for (const node of created) this.nodes.set(node.path.raw, node)
        for (const update of updated) update.node.props = { ...update.node.props, ...update.props }
        this.edges.push(...linked)
      },
      updateNode: async (className: string, path: PathLike, props: Record<string, unknown>) => {
        const node = this.resolve(path)
        if (!node || node.class.raw !== className) {
          throw new Error(`Missing update target ${rawOf(path)}`)
        }
        node.props = { ...node.props, ...props }
      },
    } as unknown as Kernel
  }

  private resolve(path: PathLike): SimulationNode | null {
    const value = rawOf(path)
    if (value.startsWith('@')) {
      return [...this.nodes.values()].find(({ id }) => `@${id}` === value) ?? null
    }
    return this.nodes.get(value) ?? null
  }

  private hasEdge(candidate: SimulationEdge): boolean {
    return this.edges.some((existing) => sameEdge(existing, candidate))
  }
}

function sameEdge(left: SimulationEdge, right: SimulationEdge): boolean {
  return left.source === right.source && left.edge === right.edge && left.target === right.target
}

function refOf(value: unknown): string {
  if (typeof value === 'string') return value
  if (value && typeof value === 'object') {
    const candidate = value as { path?: { raw?: string }; raw?: string; id?: string }
    return candidate.path?.raw ?? candidate.raw ?? candidate.id ?? 'unknown'
  }
  return String(value)
}
