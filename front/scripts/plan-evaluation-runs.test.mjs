import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { planRuns } from '../../scripts/plan-evaluation-runs.mjs'

test('planned questions and manifest hash use the same single-read snapshot', async context => {
  const initial = Buffer.from(JSON.stringify({ questionId: 'initial', lawAsOf: '2026-01-01' }))
  const changed = Buffer.from(JSON.stringify({ questionId: 'changed', lawAsOf: '2026-02-01' }))
  let reads = 0
  context.mock.method(fs, 'readFile', async () => ++reads === 1 ? initial : changed)
  const plan = await planRuns('changing-manifest.jsonl', 1, 'seed')
  assert.equal(plan[0].questionId, 'initial')
  assert.equal(plan[0].manifestSha256, createHash('sha256').update(initial).digest('hex'))
  assert.equal(reads, 1)
})

test('pre-registers deterministic paired order without inventing answers', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'legal-run-plan-'))
  const file = path.join(directory, 'questions.jsonl')
  try {
    await fs.writeFile(file, [
      { questionId: 'case-a', lawAsOf: '2024-01-01' },
      { questionId: 'case-b', lawAsOf: '2025-01-01' },
    ].map(JSON.stringify).join('\n'))
    const plan = await planRuns(file, 2, 'published-seed')
    assert.equal(plan.length, 4)
    assert.deepEqual(plan, await planRuns(file, 2, 'published-seed'))
    assert.deepEqual(plan.map(item => [item.questionId, item.run]),
      [['case-a', 1], ['case-a', 2], ['case-b', 1], ['case-b', 2]])
    for (const item of plan) {
      assert.deepEqual([...item.order].sort(), ['direct', 'rag', 'verified'])
      assert.match(item.manifestSha256, /^[a-f0-9]{64}$/)
      assert.equal(Object.hasOwn(item, 'claims'), false)
    }
    const script = fileURLToPath(new URL('../../scripts/plan-evaluation-runs.mjs', import.meta.url))
    const output = spawnSync(process.execPath, [script, file, '2', 'published-seed'],
      { encoding: 'utf8', timeout: 10000 })
    assert.equal(output.status, 0, output.stderr)
    assert.deepEqual(output.stdout.trim().split('\n').map(JSON.parse), plan)
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
})

test('rejects missing, duplicate, malformed or corrupted manifests without output', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'legal-run-plan-'))
  const file = path.join(directory, 'questions.jsonl')
  try {
    await fs.writeFile(file, '')
    await assert.rejects(planRuns(file, 1, 'seed'), /不能为空/)
    await fs.writeFile(file, JSON.stringify({ questionId: 'case-a', lawAsOf: '2026-02-30' }))
    await assert.rejects(planRuns(file, 1, 'seed'), /lawAsOf/)
    await fs.writeFile(file, [1, 2].map(() =>
      JSON.stringify({ questionId: 'case-a', lawAsOf: '2026-01-01' })).join('\n'))
    await assert.rejects(planRuns(file, 1, 'seed'), /重复/)
    await fs.writeFile(file, Buffer.from([0x80]))
    await assert.rejects(planRuns(file, 1, 'seed'), /UTF-8/)
    const script = fileURLToPath(new URL('../../scripts/plan-evaluation-runs.mjs', import.meta.url))
    const result = spawnSync(process.execPath, [script, file, '1', 'seed'],
      { encoding: 'utf8', timeout: 10000 })
    assert.equal(result.status, 1)
    assert.equal(result.stdout, '')
    assert.match(result.stderr, /UTF-8/)
    await assert.rejects(planRuns(file, 0, 'seed'), /轮次数/)
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
})
