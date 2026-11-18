import { setTimeout as delay } from 'node:timers/promises'
import { GameInstallation, describeError, type Installation } from './installation.js'
import { titleScreen, type GameProcess } from './launcher.js'
import { MinecraftMcp, McpConnectionError, type Connection } from './mcp.js'
import { Recordings } from './recordings.js'

export interface PoolSettings {
  enabled: boolean
  version: string
  java: string
  minimum: number
  maximum: number
  connections: Connection[]
}

type StartedGame = GameProcess & { account?: string }
type Notify = (version: string) => void

export interface Lease {
  mcp: MinecraftMcp
  connection: Connection
  recordings: Recordings
  recordedTask?: { id: number, args: Record<string, unknown> }
}

interface Game {
  slot: number
  external?: Connection
  process?: StartedGame
  mcp: MinecraftMcp
  connection?: Connection
  state: 'starting' | 'idle' | 'assigned' | 'resetting' | 'stopping'
  owner?: string
  lease?: Lease
  tail: Promise<unknown>
  pending: number
  lastUse: number
  nextProbe: number
  work?: Promise<void>
  closed: boolean
}

const idleTimeout = 10 * 60_000
const reconnectDelay = 15_000

export class GamePool {
  private desired?: PoolSettings
  private active?: PoolSettings
  private installed?: Installation
  private installing?: Promise<void>
  private installAbort?: AbortController
  private installError = ''
  private lastError = ''
  private nextLaunch = 0
  private readonly waiting = new Map<string, Notify>()
  private readonly games = new Set<Game>()
  private readonly retryExternal = new Map<string, number>()
  private readonly abort = new AbortController()
  private readonly timer: ReturnType<typeof setInterval>

  constructor(
    private readonly installation: GameInstallation,
    private readonly start: (installation: Installation, slot: number, signal: AbortSignal) => Promise<StartedGame>,
    private readonly now = () => Date.now(),
  ) {
    this.timer = setInterval(() => this.drive(), 2000)
    this.timer.unref()
  }

  configure(settings: PoolSettings) {
    const changed = !this.desired || changedInstallation(this.desired, settings)
    this.desired = settings
    if (changed || !settings.enabled) {
      this.installAbort?.abort()
      this.installError = ''
      this.lastError = ''
      this.nextLaunch = 0
      this.retryExternal.clear()
    }
    if (!settings.enabled || !settings.version) this.waiting.clear()
    this.drive()
  }

  retry() {
    this.installError = ''
    this.lastError = ''
    this.nextLaunch = 0
    this.retryExternal.clear()
    this.drive()
  }

  status() {
    return {
      installation: this.installation.status(),
      desiredVersion: this.desired?.version ?? '',
      activeVersion: this.active?.version ?? '',
      switching: !!(this.active && this.desired && changedInstallation(this.active, this.desired)),
      minimum: this.desired?.minimum ?? 0,
      maximum: this.desired?.maximum ?? 0,
      waiting: this.waiting.size,
      lastError: this.lastError,
      games: [...this.games].map(game => ({
        slot: game.slot, endpoint: game.external?.endpoint ?? '', state: game.state, source: game.external ? 'external' : 'managed',
        account: game.process?.account ?? (game.external || game.state === 'starting' ? '' : 'offline'),
      })),
    }
  }

  async use<T>(owner: string, notify: Notify, signal: AbortSignal, execute: (lease: Lease) => Promise<T>): Promise<T> {
    signal.throwIfAborted()
    if (!this.desired?.enabled || this.abort.signal.aborted) throw new Error('Minecraft GameTest is disabled')
    if (!this.desired.version) throw new Error('Choose a Minecraft version in the plugin details')
    let game = [...this.games].find(slot => slot.owner === owner && slot.state === 'assigned')
    if (!game) {
      // Map iteration preserves insertion order. Another call puts this session at the end.
      this.waiting.delete(owner)
      this.waiting.set(owner, notify)
      game = this.allocate(owner)
      this.drive()
    }
    if (!game) {
      if (this.installError) throw new Error(`Minecraft installation failed. Open the plugin details for the log. ${this.installError}`)
      if (this.active && changedInstallation(this.active, this.desired)) {
        throw new Error('Minecraft is switching versions. This session will be notified when a game is assigned.')
      }
      if (!this.installed) throw new Error('Minecraft is being installed. This session will be notified when a game is assigned.')
      if (this.games.size >= this.desired.maximum) {
        throw new Error(`Minecraft is at its limit of ${this.desired.maximum} active games. This session is queued and will be notified when a game is available.`)
      }
      if (this.lastError) throw new Error(`Minecraft could not start a game. ${this.lastError}`)
      throw new Error('A Minecraft game is starting. This session will be notified when it is assigned.')
    }

    const slot = game
    const lease = slot.lease!
    slot.lastUse = this.now()
    slot.pending++
    const result = slot.tail.then(async () => {
      signal.throwIfAborted()
      if (slot.state !== 'assigned' || slot.lease !== lease) throw new Error('The Minecraft instance is no longer connected')
      return execute(lease)
    })
    slot.tail = result.catch(() => undefined)
    try {
      return await result
    } catch (error) {
      if (error instanceof McpConnectionError && !signal.aborted) this.lost(slot, error.message)
      throw error
    } finally {
      slot.pending--
      slot.lastUse = this.now()
    }
  }

  private allocate(caller?: string): Game | undefined {
    if (!this.active || !this.desired || changedInstallation(this.active, this.desired) || this.abort.signal.aborted) return
    let ownGame: Game | undefined
    for (const game of this.games) {
      if (!this.waiting.size) break
      if (game.state !== 'idle' || !game.connection) continue
      if (game.external && !this.desired.connections.some(item => sameConnection(game.external!, item))) continue
      const [owner, notify] = this.waiting.entries().next().value!
      this.waiting.delete(owner)
      game.owner = owner
      game.state = 'assigned'
      game.lastUse = this.now()
      game.lease = { mcp: game.mcp, connection: game.connection, recordings: new Recordings() }
      if (owner === caller) ownGame = game
      else {
        try {
          notify(this.active.version)
        } catch {
          this.release(game)
        }
      }
    }
    return ownGame
  }

  private drive() {
    if (!this.desired || this.abort.signal.aborted) return
    const now = this.now()
    const settings = this.desired
    const retiring = !settings.enabled || !settings.version || (this.active && changedInstallation(this.active, settings))
    for (const game of this.games) {
      if (game.state === 'assigned' && game.pending === 0 && now - game.lastUse >= idleTimeout) this.release(game)
      const removedExternal = game.external && !settings.connections.some(item => sameConnection(game.external!, item))
      if (game.state !== 'idle') continue
      if (retiring || removedExternal || this.liveCount() > settings.maximum) void this.drop(game)
      else if (game.external && game.nextProbe <= now && !this.waiting.size) this.probe(game)
    }
    if (!settings.enabled || !settings.version) return
    if (!this.active || changedInstallation(this.active, settings)) {
      if (this.games.size > 0 || this.installing) return
      this.active = settings
      this.installed = undefined
    }

    if (!this.installed && !this.installing && !this.installError) this.prepare(settings)
    this.allocate()
    // Keep the selected version enforced for configured external instances as well.
    for (const connection of settings.connections) {
      if (this.games.size >= settings.maximum) break
      if ((this.retryExternal.get(connection.endpoint) ?? 0) > now) continue
      if ([...this.games].some(game => game.external?.endpoint === connection.endpoint)) continue
      this.boot(connection)
    }

    if (!this.installed || this.nextLaunch > now) return
    const available = [...this.games].filter(game => ['starting', 'idle', 'resetting'].includes(game.state)).length
    let needed = Math.max(settings.minimum - this.games.size, this.waiting.size - available)
    while (needed-- > 0 && this.games.size < settings.maximum) this.boot()
  }

  private prepare(settings: PoolSettings) {
    const controller = new AbortController()
    this.installAbort = controller
    const signal = AbortSignal.any([controller.signal, this.abort.signal])
    this.installing = this.installation.install(settings.version, settings.java, signal)
      .then(result => {
        if (!signal.aborted) this.installed = result
      }).catch(error => {
        if (!signal.aborted) this.installError = describeError(error)
      }).finally(() => {
        this.installing = undefined
        this.installAbort = undefined
        this.drive()
      })
  }

  private boot(external?: Connection) {
    let slot = 1
    while ([...this.games].some(game => !game.external && game.slot === slot)) slot++
    const game: Game = {
      slot: external ? 0 : slot, external, mcp: new MinecraftMcp(), state: 'starting',
      tail: Promise.resolve(), pending: 0, lastUse: this.now(), nextProbe: this.now() + 30_000, closed: false,
    }
    this.games.add(game)
    const { version } = this.active!
    const settings = this.desired!
    game.work = (async () => {
      try {
        if (external) {
          const signal = AbortSignal.any([this.abort.signal, AbortSignal.timeout(4000)])
          const { data } = await game.mcp.call(external, 'status', {}, signal)
          if (data.minecraft !== version) throw new Error(`External Minecraft at ${external.endpoint} reported ${String(data.minecraft)} instead of ${version}`)
          await this.toTitle(game, external, signal)
          game.connection = external
        } else {
          const process = await this.start(this.installed!, slot, this.abort.signal)
          game.process = process
          game.connection = process.connection
          void process.exit.then(() => this.lost(game))
        }
        if (this.desired && (changedInstallation(settings, this.desired) || !this.desired.enabled || this.abort.signal.aborted)) {
          await this.drop(game)
          return
        }
        game.state = 'idle'
        this.nextLaunch = 0
        this.lastError = ''
        this.drive()
      } catch (error) {
        this.lastError = describeError(error)
        if (external) this.retryExternal.set(external.endpoint, this.now() + reconnectDelay)
        else this.nextLaunch = this.now() + reconnectDelay
        await this.drop(game)
      }
    })()
  }

  private release(game: Game) {
    const owner = game.owner!
    const lease = game.lease!
    game.state = 'resetting'
    game.owner = undefined
    game.lease = undefined
    game.work = (async () => {
      try {
        if (lease.recordings.status().recording) {
          await settleRecordedTask(lease).catch(error => { this.lastError = describeError(error) })
          await lease.recordings.stop(owner).catch(error => { this.lastError = describeError(error) })
        }
        await this.toTitle(game, lease.connection, AbortSignal.timeout(45_000))
        if (game.closed) return
        game.state = 'idle'
        this.allocate()
        if (game.state === 'idle' && !game.external
          && (this.abort.signal.aborted || !this.desired?.enabled || this.liveCount() > this.desired.minimum)) await this.drop(game)
        this.drive()
      } catch (error) {
        this.lastError = describeError(error)
        if (game.external) this.retryExternal.set(game.external.endpoint, this.now() + reconnectDelay)
        await this.drop(game)
      }
    })()
  }

  private async toTitle(game: Game, connection: Connection, signal: AbortSignal) {
    for (;;) {
      try {
        const { data } = await game.mcp.call(connection, 'return_to_title', {}, signal)
        if (data.in_world === false && data.screen === titleScreen) return
      } catch (error) {
        if (describeError(error) !== 'Minecraft is still connecting to a server') throw error
      }
      await delay(500, undefined, { signal })
    }
  }

  private probe(game: Game) {
    game.state = 'resetting'
    game.nextProbe = this.now() + 30_000
    game.work = game.mcp.call(game.connection!, 'status', {}, AbortSignal.any([this.abort.signal, AbortSignal.timeout(4000)])).then(({ data }) => {
      if (data.minecraft !== this.active?.version) throw new Error('An external Minecraft instance changed versions')
      if (data.in_world !== false || data.screen !== titleScreen) {
        return this.toTitle(game, game.connection!, AbortSignal.any([this.abort.signal, AbortSignal.timeout(15_000)]))
      }
    }).then(() => {
      if (!game.closed) game.state = 'idle'
      this.drive()
    }).catch(error => this.lost(game, describeError(error)))
  }

  private liveCount() {
    return [...this.games].filter(game => game.connection && game.state !== 'stopping').length
  }

  private lost(game: Game, message = 'A Minecraft game exited') {
    if (game.closed || game.state === 'stopping') return
    this.lastError = message
    if (game.external) this.retryExternal.set(game.external.endpoint, this.now() + reconnectDelay)
    else this.nextLaunch = this.now() + reconnectDelay
    void this.drop(game)
  }

  private async drop(game: Game) {
    if (game.closed) return
    game.closed = true
    game.state = 'stopping'
    try {
      await Promise.allSettled([game.mcp.close(), game.process?.stop()])
    } finally {
      this.games.delete(game)
      this.drive()
    }
  }

  async close() {
    this.abort.abort()
    clearInterval(this.timer)
    this.waiting.clear()
    await Promise.allSettled([...this.games].map(async game => {
      if (game.state === 'starting' || game.state === 'resetting') await game.work
      if (game.state === 'assigned') {
        await game.tail
        if (!game.closed) this.release(game)
        await game.work
      }
      await this.drop(game)
    }))
    await this.installing
  }
}

function changedInstallation(a: PoolSettings, b: PoolSettings) {
  return a.version !== b.version || a.java !== b.java
}

function sameConnection(a: Connection, b: Connection) {
  return a.endpoint === b.endpoint && a.tokenFile === b.tokenFile
}

export async function settleRecordedTask(lease: Lease, status?: Record<string, unknown>, requireCompletion = false) {
  const task = lease.recordedTask
  if (!task) return
  const data = status ?? (await lease.mcp.call(lease.connection, 'task', { action: 'status' }, AbortSignal.timeout(8000))).data
  if (data.task_id !== task.id) {
    lease.recordedTask = undefined
    throw new Error('The Minecraft task was replaced before its outcome could be recorded')
  }
  if (data.state === 'running' || data.state === 'paused') {
    if (requireCompletion) throw new Error('Wait for the recorded Minecraft task to finish or cancel it first')
    return
  }
  lease.recordedTask = undefined
  if (data.state === 'succeeded') await lease.recordings.append('task', task.args)
}
