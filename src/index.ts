import { readFileSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import { isAbsolute, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Context } from '@deepseek-ai/cordis'
import type {} from '@deepseek-ai/dsh-attachment'
import type { ToolDefinition, ToolRunContext } from '@deepseek-ai/dsh-tools'
import { detectProfileDir, type HostAPI } from '@dsh-plugin/dsh-loader'
import { ConfigError, defineConfig, type ConfigValues } from '@dsh-plugin/dsh-loader/config'
import { Accounts } from './accounts.js'
import { GameInstallation, describeError, versions } from './installation.js'
import { GameLauncher } from './launcher.js'
import type { Connection } from './mcp.js'
import { GamePool, settleRecordedTask, type Lease, type PoolSettings } from './pool.js'

export const name = '@dsh-plugin/dsh-minecraft-gametest'
export const inject = ['dshLoader', 'tools', 'attachments']

const CONFIG_ID = 'minecraft-gametest'
const template = defineConfig({
  id: CONFIG_ID,
  packageName: name,
  title: 'Minecraft GameTest',
  fields: [{
    type: 'object', key: 'settings', label: 'Minecraft settings', hidden: true,
    fields: [
      { type: 'boolean', key: 'enabled', label: 'Enable Minecraft tools', default: true },
      {
        type: 'select', key: 'minecraftVersion', label: 'Minecraft version', default: '',
        options: [{ label: 'Unselected', value: '' }, ...Object.keys(versions).map(version => ({ label: version, value: version }))],
      },
      { type: 'text', key: 'javaExecutable', label: 'Java executable', default: '' },
      { type: 'number', key: 'minimum', label: 'Minimum active games', default: 0, min: 0, max: 32 },
      { type: 'number', key: 'maximum', label: 'Maximum active games', default: 2, min: 1, max: 32 },
      { type: 'array', key: 'connections', label: 'External Minecraft MCP connections', default: [], item: {
        type: 'object', label: 'MCP connection', fields: [
          { type: 'text', key: 'endpoint', label: 'MCP endpoint', required: true },
          { type: 'text', key: 'tokenFile', label: 'MCP token file', required: true },
        ],
      } },
    ],
  }],
  panels: [{
    id: 'connection',
    title: 'Minecraft installation and games',
    custom: true,
    field: ['settings'],
    fields: [
      { type: 'text', key: 'id', label: 'Account or sign-in ID', required: true },
      { type: 'password', key: 'callback', label: 'Microsoft redirect URL', required: true },
    ],
    operations: [
      { id: 'status', readOnly: true, input: [] },
      { id: 'retry-install', input: [] },
      { id: 'account-begin', input: [] },
      { id: 'account-finish', input: ['id', 'callback'] },
      { id: 'account-remove', input: ['id'] },
    ],
  }],
})

type JsonSchema = ToolDefinition['parameters']
interface McpSchema {
  type: JsonSchema['type']
  description?: string
  required?: string[]
  enum?: string[]
  properties?: Record<string, McpSchema>
  additionalProperties?: boolean | McpSchema
}

interface ToolSpec {
  name: string
  description: string
  inputSchema: McpSchema
  record: boolean
  recordActions?: string[]
}

const toolSpecs = JSON.parse(readFileSync(new URL('../protocol/tools.json', import.meta.url), 'utf8')) as ToolSpec[]

// DSH's raw schema subset does not include bounds or typed additional properties.
// The mod validates these constraints against the complete MCP declaration.
function dshSchema(schema: McpSchema): JsonSchema {
  const { type, description, required, properties, additionalProperties, enum: literals } = schema
  return {
    type,
    ...(description !== undefined && { description }),
    ...(required && { required }),
    ...(literals && { enum: literals }),
    ...(properties && { properties: Object.fromEntries(Object.entries(properties).map(([key, value]) => [key, dshSchema(value)])) }),
    ...(additionalProperties !== undefined && { additionalProperties: additionalProperties !== false }),
  } as JsonSchema
}

function owner(exec: ToolRunContext) {
  if (!exec.agent) throw new Error('This operation requires a DSH session')
  return exec.agent.session.header.id
}

function profileData(ctx: Context) {
  const loader = ctx.get('loader') as { ctx?: { baseUrl?: string }, config?: { baseUrl?: string } } | undefined
  for (const url of [ctx.baseUrl, loader?.ctx?.baseUrl, loader?.config?.baseUrl]) {
    if (!url?.startsWith('file:')) continue
    const folder = fileURLToPath(new URL('.', url))
    try {
      if (JSON.parse(readFileSync(join(folder, 'package.json'), 'utf8')).dsh?.profile !== undefined) {
        return join(folder, 'data', 'dsh-minecraft-gametest')
      }
    } catch { /* Try the active loader's base URL or the installed package location. */ }
  }
  const folder = detectProfileDir({ moduleUrl: import.meta.url })
  if (!folder) throw new Error('Cannot locate the active DSH profile for Minecraft GameTest')
  return join(folder, 'data', 'dsh-minecraft-gametest')
}

function poolSettings(value: ConfigValues): PoolSettings {
  const version = String(value.minecraftVersion || '')
  if (version && !Object.hasOwn(versions, version)) throw new Error('Unsupported Minecraft version')
  const java = String(value.javaExecutable || '').trim()
  if (java && !isAbsolute(java)) throw new Error('Java executable must be an absolute path')
  const minimum = Number(value.minimum)
  const maximum = Number(value.maximum)
  if (!Number.isInteger(minimum) || !Number.isInteger(maximum) || minimum < 0 || maximum < 1 || maximum > 32 || minimum > maximum) {
    throw new Error('Minimum and maximum active games must be whole numbers, and the minimum cannot exceed the maximum')
  }
  const connections: Connection[] = []
  const endpoints = new Set<string>()
  function add(endpoint: string, tokenFile: string) {
    let url: URL
    try { url = new URL(endpoint) } catch { throw new Error('Invalid Minecraft MCP endpoint') }
    if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.pathname !== '/mcp'
      || url.username || url.password || url.search || url.hash) {
      throw new Error('Minecraft MCP endpoints must use http://127.0.0.1:<port>/mcp')
    }
    if (!isAbsolute(tokenFile)) throw new Error('Minecraft MCP token files must use absolute paths')
    if (endpoints.has(url.href)) throw new Error('Minecraft MCP endpoints must be unique')
    endpoints.add(url.href)
    connections.push({ endpoint: url.href, tokenFile })
  }
  for (const entry of value.connections as { endpoint: string, tokenFile: string }[]) {
    add(entry.endpoint.trim(), entry.tokenFile.trim())
  }
  if (process.env.DSH_MINECRAFT_MCP_TOKEN_FILE) {
    const endpoint = process.env.DSH_MINECRAFT_MCP_ENDPOINT || 'http://127.0.0.1:4723/mcp'
    if (!endpoints.has(new URL(endpoint).href)) add(endpoint, process.env.DSH_MINECRAFT_MCP_TOKEN_FILE)
  }
  return { enabled: value.enabled === true, version, java, minimum, maximum, connections }
}

export async function apply(ctx: Context & { dshLoader: HostAPI }) {
  const lifecycle = { effect: (activate: () => void | (() => void)) => ctx.effect(() => activate() ?? (() => {})) }
  const config = await ctx.dshLoader.config.register(lifecycle, template)
  const root = profileData(ctx)
  await mkdir(root, { recursive: true, mode: 0o700 })
  const directory = join(root, 'recordings')
  const installation = new GameInstallation(root)
  const accounts = new Accounts(join(root, 'accounts'))
  const launcher = new GameLauncher(root, installation.directory)
  const pool = new GamePool(installation, async (installed, slot, signal) => {
    const checkedOut = await accounts.checkout(signal)
    try {
      const process = await launcher.start(installed, slot, checkedOut?.account, signal)
      if (checkedOut) void process.exit.then(checkedOut.release)
      return { ...process, account: checkedOut?.account.name }
    } catch (error) {
      checkedOut?.release()
      throw error
    }
  })

  let configError = ''
  function configure(value: ConfigValues) {
    try {
      pool.configure(poolSettings(value))
      configError = ''
    } catch (error) {
      configError = describeError(error)
    }
  }
  const settings = (value: ConfigValues) => value.settings as ConfigValues
  configure(settings(config.get().value))
  const unwatch = config.watch(({ value }) => configure(settings(value)))
  const onExit = () => launcher.stopOnExit()
  process.once('exit', onExit)
  ctx.effect(() => () => {
    unwatch()
    void pool.close().finally(() => process.off('exit', onExit))
  })

  const registry = ctx.dshLoader.services.get('tools') as {
    register(definition: ToolDefinition, options: { when: () => boolean }): () => void
  }
  const when = () => settings(config.get().value).enabled === true
  const namedRecording = {
    type: 'object',
    additionalProperties: false,
    properties: { name: { type: 'string', description: 'Portable recording filename without .json' } },
    required: ['name'],
  } as const

  function register(
    tool: string,
    description: string,
    parameters: JsonSchema,
    execute: (args: Record<string, unknown>, exec: ToolRunContext, lease: Lease) => Promise<unknown>,
  ) {
    const dispose = registry.register({
      name: `mc_${tool}`,
      description,
      parameters,
      timeoutMs: 110_000,
      output: {
        schema: { type: 'object', additionalProperties: true },
        render(_args, value) {
          const output = value as unknown as { data: unknown, attachment?: Awaited<ReturnType<typeof ctx.attachments.saveImage>> }
          const text = { type: 'text' as const, text: JSON.stringify(output.data) }
          return output.attachment ? [text, { type: 'image', attachment: output.attachment }] : [text]
        },
      },
      execute(args, exec) {
        const session = owner(exec)
        if (configError) throw new Error(configError)
        return pool.use(session, version => {
          const agent = exec.agent!
          const summary = `Minecraft ${version} is ready`
          const message = ctx.dshLoader.llm.createUserMessage({
            source: { kind: 'minecraft-gametest', form: 'notice', summary },
            content: [{ type: 'text', text: `${summary}. A game has been assigned to this session at the title screen. You can resume using the mc_ tools.` }],
          }) as Parameters<typeof agent.followup>[0]
          agent.followup(message)
        }, exec.signal, lease => execute(args as Record<string, unknown>, exec, lease))
      },
    }, { when })
    ctx.effect(() => dispose)
  }

  for (const tool of toolSpecs) {
    if (tool.name === 'replay_start' || tool.name === 'return_to_title') continue
    register(tool.name, tool.description, dshSchema(tool.inputSchema), async (args, exec, lease) => {
      const { recordings, mcp, connection } = lease
      const action = typeof args.action === 'string' ? args.action.replace(/^#/, '') : ''
      const taskStarts = tool.recordActions?.includes(action) === true
      const taskMutates = tool.name === 'task' && !['status', 'paused'].includes(action)
      if (tool.record || taskStarts) {
        await settleRecordedTask(lease, undefined, true)
        recordings.checkAction(owner(exec))
      }
      // Read an already dispatched action's outcome even if the caller cancels its turn.
      const result = await mcp.call(connection, tool.name, args, tool.record || taskMutates ? undefined : exec.signal)
      if (tool.record) await recordings.append(tool.name, args)
      if (taskStarts && recordings.status().recording) {
        if (typeof result.data.task_id !== 'number') throw new Error('Minecraft did not return an automation task ID')
        lease.recordedTask = { id: result.data.task_id, args }
      } else if (tool.name === 'task') await settleRecordedTask(lease, result.data)
      if (!result.image) return { data: result.data }
      const attachment = await ctx.attachments.saveImage({
        data: new Uint8Array(Buffer.from(result.image.data, 'base64')),
        mediaType: 'image/png',
        name: `${tool.name}.png`,
      })
      return { data: result.data, attachment }
    })
  }

  register('recording_start', 'Start saving successful Minecraft actions and explicit assertions to a new named test recording. Join the test world first.', structuredClone(namedRecording), async (args, exec, { mcp, connection, recordings }) => {
    const { data } = await mcp.call(connection, 'status', {}, exec.signal)
    if (!data.in_world || typeof data.minecraft !== 'string' || typeof data.dimension !== 'string') throw new Error('Join a Minecraft world first')
    const { data: task } = await mcp.call(connection, 'task', { action: 'status' }, exec.signal)
    if (task.state === 'running' || task.state === 'paused') throw new Error('Finish the active Minecraft task before recording')
    exec.signal.throwIfAborted()
    return { data: await recordings.start(owner(exec), directory, String(args.name), data.minecraft, data.dimension) }
  })
  const noArgs = { type: 'object', additionalProperties: false, properties: {} } as const
  register('recording_status', 'Read the active Minecraft recording and number of saved steps.', noArgs, async (_args, _exec, { recordings }) => ({ data: recordings.status() }))
  register('recording_stop', 'Finish the active Minecraft recording. Returns the path of the saved JSON test.', noArgs, async (_args, exec, lease) => {
    await settleRecordedTask(lease, undefined, true)
    return { data: await lease.recordings.stop(owner(exec)) }
  })
  register('replay', 'Start replaying a saved named Minecraft recording in the mod. Poll mc_replay_status for the report.', structuredClone(namedRecording), async (args, exec, { recordings, mcp, connection }) => {
    const recording = await recordings.load(directory, String(args.name), exec.signal)
    exec.signal.throwIfAborted()
    const { data } = await mcp.call(connection, 'replay_start', { recording })
    return { data }
  })

  config.registerOperation('status', async () => ({
    value: { ...pool.status(), configError, accounts: await accounts.list() },
  }))
  config.registerOperation('retry-install', () => {
    pool.retry()
    return { value: null }
  })
  config.registerOperation('account-begin', ({ signal }) => accountOperation(() => accounts.begin(signal)))
  config.registerOperation('account-finish', ({ input, signal }) => accountOperation(() => accounts.finish(String(input.id), String(input.callback), signal)))
  config.registerOperation('account-remove', ({ input }) => accountOperation(async () => {
    await accounts.remove(String(input.id))
    return null
  }))
}

async function accountOperation<T extends ConfigValues | null>(operation: () => Promise<T>) {
  try {
    return { value: await operation() }
  } catch (error) {
    throw new ConfigError('validation', describeError(error))
  }
}
