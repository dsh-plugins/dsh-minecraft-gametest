import { spawnSync } from 'node:child_process'
import { copyFile, mkdir, readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const targets = JSON.parse(await readFile(new URL('protocol/versions.json', root), 'utf8'))
const { version } = JSON.parse(await readFile(new URL('package.json', root), 'utf8'))
const windows = process.platform === 'win32'
const gradle = spawnSync(windows ? '.\\gradlew.bat' : './gradlew', Object.keys(targets).map(target => `:${target}:jar`), {
  cwd: new URL('mods/', root), stdio: 'inherit', shell: windows,
})
if (gradle.status !== 0) throw gradle.error ?? new Error('NeoForge mod build failed')
await mkdir(new URL('minecraft-mods/', root), { recursive: true })
for (const target of Object.keys(targets)) {
  const jar = `dsh-minecraft-gametest-${target}-${version}.jar`
  await copyFile(new URL(`mods/${target}/build/libs/${jar}`, root), new URL(`minecraft-mods/${target}.jar`, root))
}
