import { randomUUID } from 'node:crypto'
import { link, mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

const MAX_STEPS = 500
const MAX_BYTES = 1024 * 1024
const NAME = /^[A-Za-z0-9_-]{1,64}$/

interface Step {
  tool: string
  arguments: Record<string, unknown>
}

interface Recording {
  version: 1
  name: string
  created_at: string
  minecraft: string
  dimension: string
  steps: Step[]
}

function recordingPath(dir: string, name: string) {
  if (!NAME.test(name) || /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])$/i.test(name)) {
    throw new Error('Recording name must be a portable filename with 1 to 64 letters, digits, hyphens, or underscores')
  }
  return join(dir, `${name}.json`)
}

async function save(path: string, recording: Recording, create: boolean) {
  const bytes = JSON.stringify(recording, null, 2) + '\n'
  if (Buffer.byteLength(bytes) > MAX_BYTES) throw new Error('Recording exceeds the 1 MiB limit')
  const temporary = `${path}.${randomUUID()}.tmp`
  try {
    await writeFile(temporary, bytes, { flag: 'wx', mode: 0o600 })
    if (create) await link(temporary, path)
    else await rename(temporary, path)
  } catch (error) {
    if (create && (error as NodeJS.ErrnoException).code === 'EEXIST') {
      throw new Error('A recording with that name already exists')
    }
    throw error
  } finally {
    await rm(temporary, { force: true }).catch(() => {})
  }
}

export class Recordings {
  private active?: { owner: string, path: string, value: Recording, unsaved: boolean }

  status() {
    const active = this.active
    return active
      ? { recording: true, name: active.value.name, steps: active.value.steps.length, unsaved: active.unsaved }
      : { recording: false }
  }

  async start(owner: string, dir: string, name: string, minecraft: string, dimension: string) {
    if (this.active) throw new Error('A recording is already active. Stop it first.')
    const path = recordingPath(dir, name)
    const value: Recording = { version: 1, name, created_at: new Date().toISOString(), minecraft, dimension, steps: [] }
    await mkdir(dir, { recursive: true })
    await save(path, value, true)
    this.active = { owner, path, value, unsaved: false }
    return this.status()
  }

  checkAction(owner: string) {
    if (!this.active) return
    if (this.active.owner !== owner) throw new Error('Another DSH session is recording Minecraft')
    if (this.active.unsaved) throw new Error('A step could not be saved. Use mc_recording_stop to retry saving.')
    if (this.active.value.steps.length >= MAX_STEPS) throw new Error('Recording is full. Stop it before running more actions.')
  }

  async append(tool: string, args: Record<string, unknown>) {
    const active = this.active
    if (!active) return
    active.value.steps.push({ tool, arguments: args })
    active.unsaved = true
    try {
      await save(active.path, active.value, false)
      active.unsaved = false
    } catch (error) {
      throw new Error(`Minecraft executed the action but writing the recording failed: ${(error as Error).message}`)
    }
  }

  async stop(owner: string) {
    const active = this.active
    if (!active) throw new Error('No recording is active')
    if (active.owner !== owner) throw new Error('Only the DSH session that started this recording can stop it')
    if (active.unsaved) await save(active.path, active.value, false)
    this.active = undefined
    return { path: active.path, name: active.value.name, steps: active.value.steps.length }
  }

  async load(dir: string, name: string, signal: AbortSignal): Promise<Recording> {
    if (this.active) throw new Error('Stop the active recording before replaying')
    const path = recordingPath(dir, name)
    if ((await stat(path)).size > MAX_BYTES) throw new Error('Recording exceeds the 1 MiB limit')
    return JSON.parse(await readFile(path, { encoding: 'utf8', signal })) as Recording
  }
}
