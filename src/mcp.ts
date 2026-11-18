import { readFile } from 'node:fs/promises'
import { CallToolResultSchema } from '@modelcontextprotocol/sdk/types.js'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

export interface Connection {
  endpoint: string
  tokenFile: string
}

export class MinecraftMcp {
  private client?: Client
  private connectionKey?: string

  async call(config: Connection, name: string, args: Record<string, unknown>, signal?: AbortSignal) {
    const client = await this.connect(config, signal)
    const result = CallToolResultSchema.parse(await client.callTool({ name, arguments: args }, undefined, { signal, timeout: 95_000 }))
    const content = result.content
    if (result.isError) {
      const issue = result.structuredContent?.error
      const text = content.find(entry => entry.type === 'text')
      throw new Error(typeof issue === 'string' ? issue : text?.text ?? 'Minecraft tool failed')
    }
    return {
      data: result.structuredContent ?? {},
      image: content.find(entry => entry.type === 'image'),
    }
  }

  async close() {
    const client = this.client
    this.client = undefined
    this.connectionKey = undefined
    await client?.close()
  }

  private async connect(config: Connection, signal?: AbortSignal) {
    signal?.throwIfAborted()
    const url = new URL(config.endpoint)
    if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.username || url.password || url.search || url.hash || url.pathname !== '/mcp') {
      throw new Error('Minecraft MCP endpoint must have the form http://127.0.0.1:<port>/mcp')
    }
    const token = (await readFile(config.tokenFile, { encoding: 'utf8', signal })).trim()
    if (!/^[A-Za-z0-9_-]{32,128}$/.test(token)) throw new Error('Invalid Minecraft MCP token file')
    const key = JSON.stringify([url.href, token])
    if (this.client && this.connectionKey === key) return this.client
    await this.close()
    const client = new Client({ name: 'dsh-minecraft-gametest', version: '0.1.0' })
    const transport = new StreamableHTTPClientTransport(url, {
      requestInit: { headers: { Authorization: `Bearer ${token}` }, redirect: 'error' },
      fetch: (input, init) => {
        const signals = [signal, init?.signal].filter((item): item is AbortSignal => item != null)
        return fetch(input, { ...init, signal: signals.length ? AbortSignal.any(signals) : undefined })
      },
    })
    try {
      // The transport outlives this invocation. Drop its connect-only signal after the handshake.
      await client.connect(transport, { signal, timeout: 8_000 })
      signal = undefined
      this.client = client
      this.connectionKey = key
      return client
    } catch (error) {
      await client.close()
      throw new Error(`Cannot connect to Minecraft MCP at ${url.href}: ${error instanceof Error ? error.message : String(error)}`)
    }
  }
}
