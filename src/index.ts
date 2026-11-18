import { readFileSync } from 'node:fs'
import { isAbsolute, resolve } from 'node:path'
import type { Context } from '@deepseek-ai/cordis'
import type {} from '@deepseek-ai/dsh-attachment'
import type { ToolDefinition, ToolRunContext } from '@deepseek-ai/dsh-tools'
import type { HostAPI } from '@dsh-plugin/dsh-loader'
import { ConfigError, defineConfig } from '@dsh-plugin/dsh-loader/config'
import { MinecraftMcp } from './mcp.js'
import { Recordings } from './recordings.js'

export const name = '@dsh-plugin/dsh-minecraft-gametest'
export const inject = ['dshLoader', 'tools', 'attachments']

const CONFIG_ID = 'minecraft-gametest'
const template = defineConfig({
  id: CONFIG_ID,
  packageName: name,
  title: 'Minecraft GameTest',
  fields: [
    { type: 'boolean', key: 'enabled', label: 'Enable Minecraft tools', default: true },
    { type: 'text', key: 'endpoint', label: 'Minecraft MCP endpoint', default: 'http://127.0.0.1:4723/mcp', required: true },
    {
      type: 'text', key: 'tokenFile', label: 'Minecraft MCP token file', default: '',
      description: 'Absolute path to config/dsh-minecraft-gametest/mcp-token in the Minecraft instance.',
    },
    {
      type: 'text', key: 'recordingsDirectory', label: 'Recordings directory', default: 'minecraft-tests',
      description: 'Absolute path or a directory relative to the DSH session workspace.',
    },
  ],
  panels: [{
    id: 'connection',
    title: 'Minecraft connection',
    custom: true,
    fields: [],
    operations: [{ id: 'probe', readOnly: true, input: [] }, { id: 'replay-state', readOnly: true, input: [] }],
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

export async function apply(ctx: Context & { dshLoader: HostAPI }) {
  const lifecycle = { effect: (activate: () => void | (() => void)) => ctx.effect(() => activate() ?? (() => {})) }
  const config = await ctx.dshLoader.config.register(lifecycle, template)
  const recordings = new Recordings()
  const mcp = new MinecraftMcp()
  let tail = Promise.resolve<unknown>(undefined)

  // Keep model tools and Web diagnostics in the same order at the game connection.
  function serial<T>(task: () => Promise<T>) {
    const result = tail.then(task)
    tail = result.catch(() => undefined)
    return result
  }

  const settings = () => config.get().value
  const connection = () => {
    const value = settings()
    const tokenFile = String(value.tokenFile || process.env.DSH_MINECRAFT_MCP_TOKEN_FILE || '')
    if (!isAbsolute(tokenFile)) throw new Error('Configure the absolute Minecraft MCP token file path first')
    return { endpoint: String(process.env.DSH_MINECRAFT_MCP_ENDPOINT || value.endpoint), tokenFile }
  }
  const directory = (exec: ToolRunContext) => {
    const cwd = exec.agent?.session.header.cwd ?? process.cwd()
    return resolve(cwd, String(settings().recordingsDirectory || 'minecraft-tests'))
  }

  const registry = ctx.dshLoader.services.get('tools') as {
    register(definition: ToolDefinition, options: { when: () => boolean }): () => void
  }
  const when = () => settings().enabled === true
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
    execute: (args: Record<string, unknown>, exec: ToolRunContext) => Promise<unknown>,
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
        return serial(async () => {
          exec.signal.throwIfAborted()
          return execute(args as Record<string, unknown>, exec)
        })
      },
    }, { when })
    ctx.effect(() => dispose)
  }

  for (const tool of toolSpecs) {
    if (tool.name === 'replay_start') continue
    register(tool.name, tool.description, dshSchema(tool.inputSchema), async (args, exec) => {
      if (tool.record) recordings.checkAction(owner(exec))
      // Once a replayable action is dispatched, read its outcome and persist it before
      // returning. Aborting the local HTTP request cannot roll a Minecraft action back.
      const result = await mcp.call(connection(), tool.name, args, tool.record ? undefined : exec.signal)
      if (tool.record) await recordings.append(tool.name, args)
      if (!result.image) return { data: result.data }
      const attachment = await ctx.attachments.saveImage({
        data: new Uint8Array(Buffer.from(result.image.data, 'base64')),
        mediaType: 'image/png',
        name: `${tool.name}.png`,
      })
      return { data: result.data, attachment }
    })
  }

  register('recording_start', 'Start saving successful Minecraft actions and explicit assertions to a new named test recording. Join the test world first.', structuredClone(namedRecording), async (args, exec) => {
    const { data } = await mcp.call(connection(), 'status', {}, exec.signal)
    if (!data.in_world || typeof data.minecraft !== 'string' || typeof data.dimension !== 'string') throw new Error('Join a Minecraft world first')
    exec.signal.throwIfAborted()
    return { data: await recordings.start(owner(exec), directory(exec), String(args.name), data.minecraft, data.dimension) }
  })
  const noArgs = { type: 'object', additionalProperties: false, properties: {} } as const
  register('recording_status', 'Read the active Minecraft recording and number of saved steps.', noArgs, async () => ({ data: recordings.status() }))
  register('recording_stop', 'Finish the active Minecraft recording. Returns the path of the saved JSON test.', noArgs, async (_args, exec) => ({ data: await recordings.stop(owner(exec)) }))
  register('replay', 'Start replaying a saved named Minecraft recording in the mod. Poll mc_replay_status for the report.', structuredClone(namedRecording), async (args, exec) => {
    const recording = await recordings.load(directory(exec), String(args.name), exec.signal)
    exec.signal.throwIfAborted()
    const { data } = await mcp.call(connection(), 'replay_start', { recording })
    return { data }
  })

  for (const [operation, tool] of [['probe', 'status'], ['replay-state', 'replay_status']] as const) {
    config.registerOperation(operation, ({ signal }) => serial(async () => {
      try {
        const { data } = await mcp.call(connection(), tool, {}, signal)
        return { value: JSON.parse(JSON.stringify(data)) }
      } catch (error) {
        throw new ConfigError('validation', error instanceof Error ? error.message : String(error))
      }
    }))
  }
  ctx.effect(() => () => { void tail.finally(() => mcp.close()) })
}
