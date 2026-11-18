import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { setTimeout } from 'node:timers/promises'
import { MinecraftMcp } from '../lib/mcp.js'

const fixture = process.argv.includes('--fixture')
const screenshotDir = process.env.DSH_MINECRAFT_SCREENSHOT_DIR
const tokenFile = process.env.DSH_MINECRAFT_MCP_TOKEN_FILE
if (!tokenFile) throw new Error('Set DSH_MINECRAFT_MCP_TOKEN_FILE to the Minecraft instance token file')
const endpoint = process.env.DSH_MINECRAFT_MCP_ENDPOINT ?? 'http://127.0.0.1:4723/mcp'
const connection = { tokenFile, endpoint }
const mcp = new MinecraftMcp()
const call = (name, args = {}) => mcp.call(connection, name, args, AbortSignal.timeout(25_000))

async function replay(recording) {
  await call('replay_start', { recording })
  for (let i = 0; i < 50; i++) {
    await setTimeout(200)
    const { data } = await call('replay_status')
    if (data.state !== 'running') return data
  }
  assert.fail('Replay did not finish')
}

try {
  const unauthorized = await fetch(endpoint)
  assert.equal(unauthorized.status, 401)
  const { data: status } = await call('status')
  const { image, data } = await call('screenshot')
  assert.equal(image?.mimeType, 'image/png')
  assert.equal(Buffer.from(image.data, 'base64').readUInt32BE(16), data.width)
  assert.equal(Buffer.from(image.data, 'base64').readUInt32BE(20), data.height)

  if (fixture || process.argv.includes('--in-world')) {
    assert.equal(status.in_world, true, 'Join the test world before running --in-world')
    assert.equal(status.screen, 'none', 'Close the UI before starting the smoke test')
    const { data: player } = await call('get_player')
    assert.ok(Number.isFinite(player.health))
    const { x, y, z } = player.position
    const position = { x: Math.floor(x), y: Math.floor(y) - 1, z: Math.floor(z) }
    const { data: ground } = await call('get_block', { position })
    const { data: blocks } = await call('get_blocks', {
      from: position,
      to: { ...position, y: position.y + 1 },
    })
    assert.equal(blocks.blocks.length, 2)
    const delta = { x: x + 5, y: y + 5, z: z + 5 }
    const { data: nearby } = await call('get_entities', { from: { x: x - 5, y: y - 5, z: z - 5 }, to: delta })
    assert.ok(Array.isArray(nearby.entities))
    await call('look', { target: delta })

    if (fixture) {
      const chest = (await call('get_block', { position: { x: 2, y: 80, z: 1 } })).data
      assert.equal(chest.block, 'minecraft:chest')
      assert.match(chest.block_entity_snbt, /minecraft:chest/)
      const entity = nearby.entities.find(entry => entry.type === 'minecraft:armor_stand')
      assert.ok(entity, 'The fixture armor stand is missing from the result')
      await call('look', { entity_uuid: entity.uuid })

      await call('mouse_input', { action: 'scroll', scroll: 1 })
      const scrolled = (await call('get_player')).data
      assert.notEqual(scrolled.selected_slot, player.selected_slot)
      await call('move_to', { position: { x: 0, y: 80, z: 4 } })
      await call('assert_player_position', { position: { x: 0.5, y: 80, z: 4.5 }, tolerance: 0.75 })
    }
    await call('key_input', { key: 'E' })
    const { data: ui, image: uiImage } = await call('screenshot_ui', { control_type: 'slot' })
    assert.equal(uiImage?.mimeType, 'image/png')
    assert.ok(ui.controls.length >= 9)
    if (screenshotDir) {
      await mkdir(screenshotDir, { recursive: true })
      await writeFile(join(screenshotDir, `minecraft-${status.minecraft}.png`), Buffer.from(uiImage.data, 'base64'))
    }
    const click = { control_type: 'slot', id: ui.controls.at(-1).id, screen: ui.screen, layout: ui.layout }
    await assert.rejects(call('click_ui', { ...click, layout: 'stale' }), /changed/)
    const clicked = (await call('click_ui', click)).data
    assert.equal(clicked.id, click.id)
    await call('key_input', { key: 'ESCAPE' })

    const recording = {
      version: 1,
      name: 'mcp-smoke',
      dimension: status.dimension,
      steps: [
        { tool: 'look', arguments: { yaw: player.yaw, pitch: player.pitch } },
        { tool: 'key_input', arguments: { key: 'E' } },
        { tool: 'click_ui', arguments: click },
        { tool: 'key_input', arguments: { key: 'ESCAPE' } },
        { tool: 'assert_screen', arguments: { screen: 'none' } },
        { tool: 'assert_block', arguments: { position, block: ground.block, properties: ground.properties } },
      ],
    }
    const report = await replay(recording)
    assert.equal(report.state, 'passed', JSON.stringify(report))
    assert.equal(report.completed, 6)
    const failed = await replay({
      ...recording,
      steps: [
        { tool: 'assert_screen', arguments: { screen: 'wrong' } },
        { tool: 'wait_ticks', arguments: { ticks: 1 } },
      ],
    })
    assert.equal(failed.state, 'failed')
    assert.equal(failed.completed, 1)
    console.log(`Game controls and replay passed (6/6 steps, failure stops at step 1; ${ui.controls.length} numbered slots)`)
  }

  console.log(`Minecraft ${status.minecraft} MCP smoke test passed`)
} finally {
  await mcp.close()
}
