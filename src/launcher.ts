import { spawn, type ChildProcess } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { copyFile, mkdir, open, readFile, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { launch } from './installer-deps.js'
import type { Installation } from './installation.js'
import { MinecraftMcp, type Connection } from './mcp.js'

export interface GameAccount {
  id: string
  name: string
  accessToken: string
}

export interface GameProcess {
  connection: Connection
  stop(): Promise<void>
  exit: Promise<void>
}

export const titleScreen = 'net.minecraft.client.gui.screens.TitleScreen'

export class GameLauncher {
  private readonly children = new Set<ChildProcess>()

  constructor(private readonly root: string, private readonly resources: string) {}

  async start(installation: Installation, slot: number, account: GameAccount | undefined, signal: AbortSignal): Promise<GameProcess> {
    const directory = join(this.root, 'instances', installation.version, String(slot))
    const mods = join(directory, 'mods')
    const config = join(directory, 'config', 'dsh-minecraft-gametest')
    const portFile = join(config, 'mcp-port')
    const argsFile = join(directory, `${randomUUID()}.args`)
    const gameLog = join(directory, 'launcher.log')
    await mkdir(mods, { recursive: true, mode: 0o700 })
    await mkdir(config, { recursive: true, mode: 0o700 })
    await copyFile(join(this.resources, 'gametest.jar'), join(mods, 'dsh-minecraft-gametest.jar'))
    await rm(portFile, { force: true })
    // Skip the first-run accessibility prompt for freshly managed instances.
    await writeIfMissing(join(directory, 'options.txt'), 'onboardAccessibility:false\n')
    const log = await open(gameLog, 'w', 0o600)
    let child: ChildProcess
    let exit!: Promise<void>
    let stopped = false
    try {
      const player = account ?? offlineAccount(slot)
      child = await launch({
        gamePath: directory, resourcePath: this.resources, version: installation.loader,
        javaPath: installation.java, nativeRoot: join(directory, 'natives'),
        gameProfile: { id: player.id, name: player.name }, accessToken: player.accessToken,
        extraJVMArgs: ['-Ddsh.gametest.port=0'], maxMemory: 2048, minMemory: 256,
        resolution: { width: 854, height: 480 },
        extraExecOption: { stdio: ['ignore', log.fd, log.fd], windowsHide: true },
        spawn: (java, args, options) => {
          signal.throwIfAborted()
          // Keep multiplayer tokens out of the operating system's process command line.
          writeFileSync(argsFile, (args ?? []).map(javaArgument).join('\n') + '\n', { flag: 'wx', mode: 0o600 })
          const process = spawn(java, [`@${argsFile}`], options ?? {})
          this.children.add(process)
          exit = new Promise<void>(resolve => {
            const done = () => {
              stopped = true
              this.children.delete(process)
              resolve()
            }
            process.once('exit', done)
            process.once('error', done)
          })
          return process
        },
      })
    } catch (error) {
      await rm(argsFile, { force: true }).catch(() => {})
      throw error
    } finally {
      await log.close()
    }
    void exit.then(() => rm(argsFile, { force: true }).catch(() => {}))
    const stop = async () => {
      if (stopped) return
      child.kill()
      const escalation = setTimeout(() => child.kill('SIGKILL'), 5000)
      escalation.unref()
      try {
        await exit
      } finally {
        clearTimeout(escalation)
      }
    }

    const probe = new MinecraftMcp()
    const launchSignal = AbortSignal.any([signal, AbortSignal.timeout(90_000)])
    try {
      while (!stopped && !launchSignal.aborted) {
        const port = Number(await readFile(portFile, 'utf8').catch(() => ''))
        if (Number.isInteger(port) && port >= 1 && port <= 65535) {
          const connection = { endpoint: `http://127.0.0.1:${port}/mcp`, tokenFile: join(config, 'mcp-token') }
          const status = await probe.call(connection, 'status', {}, AbortSignal.any([launchSignal, AbortSignal.timeout(3000)])).catch(() => undefined)
          if (status?.data.minecraft === installation.version && status.data.screen === titleScreen) {
            await rm(argsFile, { force: true })
            return { connection, stop, exit }
          }
        }
        await delay(500, undefined, { signal: launchSignal }).catch(() => {})
      }
      signal.throwIfAborted()
      throw new Error(`Minecraft ${installation.version} did not reach the title screen. See ${gameLog}`)
    } catch (error) {
      await stop()
      throw error
    } finally {
      await probe.close()
    }
  }

  stopOnExit() {
    for (const child of this.children) child.kill()
  }
}

function javaArgument(arg: string) {
  return '"' + arg.replaceAll('\\', '\\\\').replaceAll('"', '\\"').replaceAll('\n', '\\n').replaceAll('\r', '\\r') + '"'
}

function offlineAccount(slot: number): GameAccount {
  const name = `DSH_Test_${slot}`
  const bytes = createHash('md5').update(`OfflinePlayer:${name}`).digest()
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  return { id: bytes.toString('hex'), name, accessToken: '0' }
}

async function writeIfMissing(path: string, text: string) {
  try {
    const handle = await open(path, 'wx', 0o600)
    try {
      await handle.writeFile(text)
    } finally {
      await handle.close()
    }
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error
  }
}
