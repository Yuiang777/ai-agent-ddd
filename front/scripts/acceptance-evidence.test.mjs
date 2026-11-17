import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { latestResult } from '../../scripts/acceptance-evidence.mjs'

async function fixture(t) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'acceptance-evidence-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  return directory
}
async function write(directory, name, result) {
  await fs.mkdir(path.join(directory, name))
  if (result !== undefined) await fs.writeFile(path.join(directory, name, 'result.json'), JSON.stringify(result))
}
const passed = { success: true, steps: ['verified'], pageErrors: [] }
test('latest failed attempt cannot fall back to an older success', async t => {
  const directory = await fixture(t)
  await write(directory, 'smoke-001', passed)
  await write(directory, 'smoke-002', { success: false })
  await assert.rejects(latestResult(directory, 'smoke-'), /Latest browser attempt failed/)
})
test('latest incomplete attempt cannot fall back to an older success', async t => {
  const directory = await fixture(t)
  await write(directory, 'smoke-001', passed)
  await write(directory, 'smoke-002')
  await assert.rejects(latestResult(directory, 'smoke-'), /incomplete or unreadable/)
})
test('latest evidence must contain checks and no page errors', async t => {
  const directory = await fixture(t)
  await write(directory, 'smoke-001', { ...passed, pageErrors: ['render failed'] })
  await assert.rejects(latestResult(directory, 'smoke-'), /invalid/)
})
test('latest valid attempt is selected within the requested suite', async t => {
  const directory = await fixture(t)
  await write(directory, 'smoke-001', passed)
  await write(directory, 'smoke-002', passed)
  await write(directory, 'ui-failures-999', { success: false })
  assert.equal((await latestResult(directory, 'smoke-')).name, 'smoke-002')
})
