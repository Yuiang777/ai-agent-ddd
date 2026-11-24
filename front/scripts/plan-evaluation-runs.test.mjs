import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { planRuns } from '../../scripts/plan-evaluation-runs.mjs'
import { evaluate } from '../../scripts/legal-citation-evaluation.mjs'

test('planned questions and manifest hash use the same single-read snapshot', async context => {
  const initial = Buffer.from(JSON.stringify({ questionId: 'initial', lawAsOf: '2026-01-01', answerable: true }))
  const changed = Buffer.from(JSON.stringify({ questionId: 'changed', lawAsOf: '2026-02-01', answerable: false }))
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
      { questionId: 'case-a', lawAsOf: '2024-01-01', answerable: true },
      { questionId: 'case-b', lawAsOf: '2025-01-01', answerable: false },
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
      assert.equal(Object.hasOwn(item, 'answerable'), false)
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
    await fs.writeFile(file, JSON.stringify({ questionId: 'case-a', lawAsOf: '2026-02-30', answerable: true }))
    await assert.rejects(planRuns(file, 1, 'seed'), /lawAsOf/)
    await fs.writeFile(file, [1, 2].map(() =>
      JSON.stringify({ questionId: 'case-a', lawAsOf: '2026-01-01', answerable: true })).join('\n'))
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

test('CLI rejects missing or extra positional arguments without emitting a plan', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'legal-plan-cli-'))
  const file = path.join(directory, 'questions.jsonl')
  const script = fileURLToPath(new URL('../../scripts/plan-evaluation-runs.mjs', import.meta.url))
  try {
    await fs.writeFile(file, JSON.stringify({ questionId: 'case-a', lawAsOf: '2026-01-01', answerable: true }))
    for (const args of [[], [file], [file, '1'], [file, '1', 'seed', 'extra'], [file, '1', 'seed', '--unknown']]) {
      const result = spawnSync(process.execPath, [script, ...args], { encoding: 'utf8', timeout: 10000 })
      assert.ifError(result.error)
      assert.equal(result.status, 2, JSON.stringify(args) + ': ' + result.stderr)
      assert.equal(result.stdout, '')
      assert.match(result.stderr, /用法/)
    }
    const valid = spawnSync(process.execPath, [script, file, '1', 'seed'], { encoding: 'utf8', timeout: 10000 })
    assert.ifError(valid.error)
    assert.equal(valid.status, 0, valid.stderr)
    assert.deepEqual(JSON.parse(valid.stdout).order.slice().sort(), ['direct', 'rag', 'verified'])
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
})

test('planning and evaluation reject the same missing or malformed frozen gold labels', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'legal-plan-gold-'))
  const file = path.join(directory, 'questions.jsonl')
  const question = { questionId: 'case-a', lawAsOf: '2026-01-01' }
  const records = ['direct', 'rag', 'verified'].map(group => ({
    ...question, group, run: 1, answerable: false, refused: true, claims: [],
    modelVersion: 'synthetic-model', promptVersion: 'synthetic-prompt', corpusSnapshotId: 'synthetic-corpus',
  }))
  try {
    for (const answerable of [undefined, null, 'false', 0, {}, []]) {
      const manifest = [{ ...question, answerable }]
      await fs.writeFile(file, manifest.map(JSON.stringify).join('\n'))
      await assert.rejects(planRuns(file, 1, 'seed'), /answerable/)
      assert.throws(() => evaluate(records, manifest), /answerable/)
    }
    const script = fileURLToPath(new URL('../../scripts/plan-evaluation-runs.mjs', import.meta.url))
    const rejected = spawnSync(process.execPath, [script, file, '1', 'seed'],
      { encoding: 'utf8', timeout: 10000 })
    assert.ifError(rejected.error)
    assert.equal(rejected.status, 1)
    assert.equal(rejected.stdout, '')
    assert.match(rejected.stderr, /answerable/)
    const valid = [{ ...question, answerable: false }]
    await fs.writeFile(file, valid.map(JSON.stringify).join('\n'))
    assert.equal((await planRuns(file, 1, 'seed')).length, 1)
    assert.ok(evaluate(records, valid).every(result => result.frozenQuestionCoverageChecked))
    const originalHash = (await planRuns(file, 1, 'seed'))[0].manifestSha256
    await fs.writeFile(file, JSON.stringify({ ...question, answerable: true }))
    assert.notEqual((await planRuns(file, 1, 'seed'))[0].manifestSha256, originalHash)
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
})
