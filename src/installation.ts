import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { appendFileSync, readFileSync } from 'node:fs'
import { mkdir, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises'
import { isAbsolute, join, relative, sep } from 'node:path'
import { promisify } from 'node:util'
import {
  createDefaultNodeInstallRuntime, createModernForgeInstallWorkflow, executeInstallManifest, executeInstallWorkflow,
  getVersionList, MinecraftFolder, Version, parseJavaVersionOutput, ProgressTrackerMultiple, resolveAssetMetadataInstallManifest, resolveAssetObjectInstallFiles,
  resolveLibraryInstallFiles, resolveMinecraftJarInstallFile, resolveMinecraftVersionJsonInstallFile, resolveNeoForgedInstallerFile,
  type InstallFile,
} from './installer-deps.js'
import { EnvHttpProxyAgent, interceptors } from 'undici'

const exec = promisify(execFile)
const manifestUrl = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
const redistributable = new URL('../minecraft-mods/', import.meta.url)
export const versions: Record<string, { neoForge: string, javaRelease: number }> =
  JSON.parse(readFileSync(new URL('../protocol/versions.json', import.meta.url), 'utf8'))

export interface Installation {
  version: string
  loader: string
  java: string
}

export class GameInstallation {
  readonly directory: string
  private state = 'idle'
  private version = ''
  private logs: string[] = []
  private completed = 0
  private total = 0
  private tracker?: ProgressTrackerMultiple

  constructor(private readonly root: string) {
    this.directory = join(root, 'game')
  }

  status() {
    return {
      state: this.state, version: this.version, logs: [...this.logs],
      completed: Math.max(this.completed, this.tracker?.trackers.filter(file => file.done).length ?? 0), total: this.total,
      downloaded: this.tracker?.progress ?? 0, downloadSize: this.tracker?.total ?? 0,
    }
  }

  private log(message: string) {
    this.logs.push(message)
    if (this.logs.length > 100) this.logs.shift()
    try { appendFileSync(join(this.root, 'installation.log'), `${new Date().toISOString()} ${message}\n`, { mode: 0o600 }) }
    catch { /* Progress remains available in memory if the log file cannot be written. */ }
  }

  async install(version: string, configuredJava: string, signal: AbortSignal): Promise<Installation> {
    this.state = 'installing'
    this.version = version
    this.logs = []
    this.completed = 0
    this.total = 0
    let proxy: EnvHttpProxyAgent | undefined
    try {
      await writeFile(join(this.root, 'installation.log'), '', { mode: 0o600 })
      if (!Object.hasOwn(versions, version)) throw new Error('Unsupported Minecraft version')
      this.log(`Preparing Minecraft ${version}`)
      const { neoForge, javaRelease } = versions[version]
      const java = await findJava(configuredJava, javaRelease, signal)
      const mod = await readFile(new URL(`${version}.jar`, redistributable), { signal }).catch(() => {
        signal.throwIfAborted()
        throw new Error('The plugin package is missing its Minecraft mod. For a source checkout, run pnpm build:mods.')
      })
      const marker = join(this.directory, 'installation.json')
      const current = await readFile(marker, 'utf8').then(value => JSON.parse(value) as Installation).catch(() => undefined)
      if (current?.version === version && current.loader === `neoforge-${neoForge}`
        && await Version.parse(new MinecraftFolder(this.directory), current.loader).then(() => true, () => false)) {
        await writeFile(join(this.directory, 'gametest.jar'), mod, { mode: 0o600 })
        this.state = 'ready'
        this.log('Using the installed Minecraft and NeoForge')
        return { version, loader: current.loader, java }
      }

      const staging = join(this.root, `.install-${version}`)
      for (const entry of await readdir(this.root)) {
        if (entry !== `.install-${version}` && entry.startsWith('.install-') && Object.hasOwn(versions, entry.slice(9))) {
          await rm(join(this.root, entry), { recursive: true, force: true })
        }
      }
      await mkdir(staging, { recursive: true, mode: 0o700 })
      proxy = new EnvHttpProxyAgent({ connections: 12 })
      // The library's downloader defaults to direct sockets unless given a dispatcher.
      const dispatcher = proxy.compose(interceptors.redirect({ maxRedirections: 5 }))
      const folder = new MinecraftFolder(staging)
      const { versions: catalog } = await getVersionList({ remote: manifestUrl, signal: AbortSignal.any([signal, AbortSignal.timeout(60_000)]) })
      const minecraft = catalog.find(entry => entry.id === version)
      if (!minecraft) throw new Error(`Minecraft ${version} is missing from the Mojang manifest`)
      const runtime = createDefaultNodeInstallRuntime({ dispatcher, signal, maxConcurrency: 12 })
      runtime.download = async files => {
        this.completed = 0
        this.total = files.length
        const tracker = new ProgressTrackerMultiple()
        this.tracker = tracker
        // Run in batches so file-count progress still advances when a server omits its Content-Length.
        for (let index = 0; index < files.length; index += 96) {
          signal.throwIfAborted()
          const batch = files.slice(index, index + 96)
          const progress = createDefaultNodeInstallRuntime({ dispatcher, signal, tracker, maxConcurrency: 12 })
          await progress.download(batch)
          this.completed += batch.length
        }
      }
      const files = (id: string, entries: InstallFile[]) =>
        executeInstallManifest({ schemaVersion: 1, tasks: [{ id, type: 'files', files: entries }] }, runtime, { signal })

      this.log('Downloading Minecraft version metadata')
      await files('manifest', [resolveMinecraftVersionJsonInstallFile(minecraft, folder)])
      const base = await Version.parse(folder, version)
      const client = resolveMinecraftJarInstallFile(base)
      if (!client) throw new Error('Mojang version does not contain a client download')
      this.log('Downloading the Minecraft client and libraries')
      await files('minecraft', [client, ...resolveLibraryInstallFiles(base.libraries, folder)])
      this.log('Downloading the Minecraft asset index')
      await executeInstallManifest(resolveAssetMetadataInstallManifest(base, folder), runtime, { signal })
      this.log('Downloading Minecraft assets')
      await files('assets', await resolveAssetObjectInstallFiles(base, folder))

      this.log(`Installing NeoForge ${neoForge}`)
      const options = {
        versionId: `neoforge-${neoForge}`, inheritsFrom: version, side: 'client' as const,
        // Mojang and NeoForge publish HTTPS Maven coordinates.
        mavenHost: ['https://maven.neoforged.net/releases', 'https://repo.maven.apache.org/maven2'],
      }
      const { file } = await resolveNeoForgedInstallerFile('neoforge', neoForge, folder, options)
      const { version: loader } = await executeInstallWorkflow(createModernForgeInstallWorkflow({
        id: 'neoforge', minecraft: folder, minecraftVersion: version, installer: file,
        artifactVersion: neoForge, installOptions: options, java,
        resolveProcessor: async processor => {
          if (!processor.args.includes('DOWNLOAD_MOJMAPS')) return { handled: false }
          const position = processor.args.indexOf('--output')
          const output = position >= 0 && processor.args[position + 1]
          const mappings = base.downloads.client_mappings
          const destination = output ? relative(folder.root, output) : ''
          if (!output || !isAbsolute(output) || destination === '..' || destination.startsWith(`..${sep}`)
            || isAbsolute(destination) || !mappings) {
            throw new Error('The NeoForge installer requested an unsupported Minecraft mappings download')
          }
          return { handled: true, files: [{ path: output, urls: [mappings.url], size: mappings.size,
            checksum: { algorithm: 'sha1', value: mappings.sha1 } }] }
        },
      }), runtime, {
        signal,
        onEvent: event => {
          if (event.type === 'task-start') this.log(event.task.id)
          if (event.type === 'file-retry') this.log(`Retrying ${event.pending} downloads for ${event.task.id}`)
        },
      })
      this.log('Installing the bundled GameTest mod')
      const installed = { version, loader, java }
      await writeFile(join(staging, 'gametest.jar'), mod, { mode: 0o600 })
      await writeFile(join(staging, 'installation.json'), JSON.stringify(installed), { mode: 0o600 })
      signal.throwIfAborted()
      await this.replace(staging)
      this.state = 'ready'
      this.log(`Minecraft ${version} and NeoForge ${neoForge} are ready`)
      return installed
    } catch (error) {
      this.state = 'failed'
      this.log(describeError(error))
      throw error
    } finally {
      this.tracker = undefined
      await proxy?.destroy()
    }
  }

  private async replace(staging: string) {
    const prior = `${staging}-previous-${randomUUID()}`
    const existed = await rename(this.directory, prior).then(() => true, error => {
      if (error.code === 'ENOENT') return false
      throw error
    })
    try {
      await rename(staging, this.directory)
    } catch (error) {
      if (existed) await rename(prior, this.directory)
      throw error
    }
    if (existed) await rm(prior, { recursive: true, force: true })
  }
}

async function findJava(configured: string, minimum: number, signal: AbortSignal) {
  if (configured && !isAbsolute(configured)) throw new Error('Java executable must be an absolute path')
  const filename = process.platform === 'win32' ? 'java.exe' : 'java'
  const candidates = configured ? [configured] : [
    ...(process.env.JAVA_HOME ? [join(process.env.JAVA_HOME, 'bin', filename)] : []), filename,
  ]
  for (const candidate of candidates) {
    const result = await exec(candidate, ['-version'], {
      signal: AbortSignal.any([signal, AbortSignal.timeout(5_000)]), windowsHide: true,
    }).catch(() => undefined)
    signal.throwIfAborted()
    const info = result && parseJavaVersionOutput(result.stdout, result.stderr)
    if (info && info.majorVersion >= minimum) return candidate
  }
  throw new Error(`Minecraft requires Java ${minimum} or later. Set the Java executable in the plugin details.`)
}

export function describeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
