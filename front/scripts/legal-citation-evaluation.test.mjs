import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { evaluate, readJsonl } from '../../scripts/legal-citation-evaluation.mjs'

const claim = (located, quote, support, displayed) => ({
  evidenceLocated: located, quoteMatched: quote, humanSupport: support, displayed,
})
const caseRow = (group, claims, options = {}) => ({
  group, questionId: 'q1', run: 1, modelVersion: 'frozen-model-v1', promptVersion: 'prompt-v1',
  corpusSnapshotId: 'frozen-corpus-v1', lawAsOf: '2026-01-01',
  answerable: true, refused: !claims.some(item => item.displayed), claims, ...options,
})

test('uses the same denominators for all groups and keeps zero denominators N/A', () => {
  const rows = [
    caseRow('direct', []),
    caseRow('rag', [claim(true, true, 'UNSUPPORTED', true)]),
    caseRow('verified', [claim(true, true, 'SUPPORTED', false)]),
  ]
  const [direct, rag, verified] = evaluate(rows)
  assert.equal(direct.locatedRate, null)
  assert.equal(direct.unsupportedDisplayedRate, null)
  assert.equal(rag.unsupportedDisplayedRate, 1)
  assert.equal(rag.metricCounts.unsupportedDisplayedClaims, 1)
  assert.equal(rag.metricCounts.displayedClaims, 1)
  assert.equal(verified.humanSupportRate, 1)
  assert.equal(verified.metricCounts.supportedClaims, 1)
  assert.equal(verified.metricCounts.judgedClaims, 1)
  assert.equal(verified.unsupportedDisplayedRate, null)
})

test('includes frozen provenance and paired sample sizes in each group result', () => {
  const rows = ['direct', 'rag', 'verified'].map(group =>
    caseRow(group, [], { promptVersion: 'prompt-' + group }))
  const results = evaluate(rows)
  assert.deepEqual(results.map(item => item.promptVersion),
    ['prompt-direct', 'prompt-rag', 'prompt-verified'])
  for (const result of results) {
    assert.equal(result.modelVersion, 'frozen-model-v1')
    assert.equal(result.corpusSnapshotId, 'frozen-corpus-v1')
    assert.equal(result.questions, 1)
    assert.equal(result.runsPerQuestion, 1)
  }
})

test('separates abstention and false refusal on answerable and unanswerable cases', () => {
  const rows = ['direct', 'rag', 'verified'].flatMap(group => [
    caseRow(group, [], { refused: true }),
    caseRow(group, [], { questionId: 'q2', answerable: false, refused: true }),
  ])
  const result = evaluate(rows)[0]
  assert.equal(result.refusalRate, 1)
  assert.equal(result.expectedRefusalRate, 1)
  assert.equal(result.falseRefusalRate, 1)
  assert.equal(result.metricCounts.refusedAnswerableRuns, 1)
  assert.equal(result.metricCounts.refusedUnanswerableRuns, 1)
})

test('refuses incomplete, duplicate or inconsistent input instead of publishing misleading scores', () => {
  const complete = ['direct', 'rag', 'verified'].map(group => caseRow(group, []))
  assert.throws(() => evaluate(complete.slice(1)), /三个实验组/)
  assert.throws(() => evaluate([...complete, complete[0]]), /重复/)
  assert.throws(() => evaluate(complete.map((row, index) => index === 0
    ? { ...row, claims: [claim(false, true, 'SUPPORTED', false)] } : row)), /来源不存在/)
  assert.throws(() => evaluate(complete.map((row, index) => index === 0
    ? { ...row, refused: true, claims: [claim(true, true, 'SUPPORTED', true)] } : row)), /拒答标注/)
})

test('does not treat unjudged displayed claims as supported', () => {
  const rows = ['direct', 'rag', 'verified'].map(group =>
    caseRow(group, [claim(true, true, 'UNCERTAIN', true)]))
  const result = evaluate(rows)[0]
  assert.equal(result.humanSupportRate, null)
  assert.equal(result.unsupportedDisplayedRate, null)
  assert.equal(result.unjudgedDisplayedClaims, 1)
})


test('rejects inconsistent question labels and refusal markers', () => {
  const rows = ['direct', 'rag', 'verified'].map(group => caseRow(group, []))
  assert.throws(() => evaluate(rows.map((row, index) => index === 2
    ? { ...row, answerable: false } : row)), /可回答标注不一致/)
  assert.throws(() => evaluate(rows.map((row, index) => index === 0
    ? { ...row, refused: false } : row)), /拒答标注与展示主张不一致/)
  assert.throws(() => evaluate(rows.map(row => ({ ...row, questionId: ' q1 ' }))), /问题 ID/)
})

test('rejects empty question IDs before computing metrics', () => {
  const rows = ['direct', 'rag', 'verified'].map(group => caseRow(group, [], { questionId: '' }))
  assert.throws(() => evaluate(rows), /ID/)
})

test('reports original source line numbers after empty lines', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'legal-evaluation-'))
  const file = path.join(directory, 'labels.jsonl')
  try {
    await fs.writeFile(file, '{"group":"direct"}\n\nnot-json\n')
    await assert.rejects(readJsonl(file), /第 3 行/)
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
})


test('accepts UTF-8 BOM only at the start of a JSONL file', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'legal-evaluation-'))
  const file = path.join(directory, 'labels.jsonl')
  const rows = ['direct', 'rag', 'verified'].map(group => caseRow(group, []))
  try {
    await fs.writeFile(file, String.fromCharCode(0xFEFF) + rows.map(JSON.stringify).join(String.fromCharCode(10)))
    assert.deepEqual(await readJsonl(file), rows)
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
})


test('requires the same contiguous number of rounds for every question', () => {
  const rows = ['direct', 'rag', 'verified'].flatMap(group => [
    caseRow(group, []),
    caseRow(group, [], { run: 2 }),
    caseRow(group, [], { questionId: 'q2' }),
  ])
  assert.throws(() => evaluate(rows), /相同次数/)
  assert.throws(() => evaluate(['direct', 'rag', 'verified'].flatMap(group => [
    caseRow(group, [], { run: 2 }),
    caseRow(group, [], { questionId: 'q2', run: 2 }),
  ])), /从 1 开始/)
})


test('rejects missing or inconsistent run provenance', () => {
  const rows = ['direct', 'rag', 'verified'].map(group => caseRow(group, []))
  assert.throws(() => evaluate(rows.map((row, index) => index === 0
    ? { ...row, modelVersion: '' } : row)), /缺少 modelVersion/)
  assert.throws(() => evaluate(rows.map((row, index) => index === 1
    ? { ...row, corpusSnapshotId: 'changed' } : row)), /语料快照不一致/)
  assert.throws(() => evaluate(rows.map((row, index) => index === 2
    ? { ...row, lawAsOf: '2026-02-30' } : row)), /适用法律日期/)
  assert.throws(() => evaluate(rows.map((row, index) => index === 1
    ? { ...row, lawAsOf: '2026-02-01' } : row)), /法律适用日期不一致/)
  const repeated = ['direct', 'rag', 'verified'].flatMap(group => [
    caseRow(group, []), caseRow(group, [], { run: 2, promptVersion: group === 'rag' ? 'changed' : 'prompt-v1' }),
  ])
  assert.throws(() => evaluate(repeated), /提示词版本不一致/)
})
