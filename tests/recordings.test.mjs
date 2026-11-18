import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { Recordings } from '../lib/recordings.js'

test('commits each successful action and keeps recording ownership scoped to a session', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-gametest-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const recordings = new Recordings()

  await recordings.start('session-a', dir, 'inventory', '26.3', 'minecraft:overworld')
  assert.throws(() => recordings.checkAction('session-b'), /Another DSH session/)
  await assert.rejects(recordings.stop('session-b'), /Only the DSH session/)
  await assert.rejects(recordings.load(dir, 'inventory', AbortSignal.timeout(1000)), /Stop the active recording/)

  recordings.checkAction('session-a')
  await recordings.append('key_input', { key: 'E' })
  await recordings.append('wait_ticks', { ticks: 4 })
  const file = JSON.parse(await readFile(join(dir, 'inventory.json'), 'utf8'))
  assert.deepEqual(file.steps, [
    { tool: 'key_input', arguments: { key: 'E' } },
    { tool: 'wait_ticks', arguments: { ticks: 4 } },
  ])
  assert.equal(file.version, 1)
  assert.equal((await recordings.stop('session-a')).steps, 2)
  assert.deepEqual(await recordings.load(dir, 'inventory', AbortSignal.timeout(1000)), file)

  await assert.rejects(recordings.start('session-a', dir, 'inventory', '26.3', 'minecraft:overworld'), /already exists/)
  await assert.rejects(recordings.start('session-a', dir, '../outside', '26.3', 'minecraft:overworld'), /portable filename/)
  assert.deepEqual(recordings.status(), { recording: false })
})
