import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { checkRounds } from '../../scripts/check-evaluation-rounds.mjs'
import { summarizeLatency } from '../../scripts/summarize-evaluation-latency.mjs'
import { evaluate, pairedQuestionBootstrap, readJsonl } from '../../scripts/legal-citation-evaluation.mjs'

const claim = (located, quote, support, displayed) => ({
  evidenceLocated: located, quoteMatched: quote, humanSupport: support, displayed,
})
const caseRow = (group, claims, options = {}) => ({
  group, questionId: 'q1', run: 1, modelVersion: 'frozen-model-v1', promptVersion: 'prompt-v1',
  corpusSnapshotId: 'frozen-corpus-v1', lawAsOf: '2026-01-01',
  answerable: true, refused: !claims.some(item => item.displayed), claims, ...options,
})

test('latency percentiles use complete planned groups, preserve zero and reject malformed durations', () => {
  const questions = [{ questionId: 'q1', lawAsOf: '2026-01-01', answerable: true }]
  const rows = [30, 0, 10, 20].flatMap((elapsedMs, index) => ['direct', 'rag', 'verified'].map(group =>
    caseRow(group, [], { run: index + 1, elapsedMs: group === 'rag' && !index ? null : elapsedMs })))
  const result = summarizeLatency(rows, questions, 4)
  assert.deepEqual(result.groups[0], { group: 'direct', measured: 4, missing: 0, p50Ms: 10, p95Ms: 30 })
  assert.deepEqual(result.groups[1], { group: 'rag', measured: 3, missing: 1, p50Ms: null, p95Ms: null })
  assert.equal(result.groups[2].p95Ms, 30)
  assert.throws(() => summarizeLatency(rows, questions, 5), /预定轮数/)
  for (const elapsedMs of ['20', -1, 1.5, Infinity]) {
    assert.throws(() => summarizeLatency([{ ...rows[0], elapsedMs }, ...rows.slice(1)], questions, 4), /elapsedMs/)
  }
})

test('latency counts each run once regardless of claim count, including completed refusals', () => {
  const questions = [{ questionId: 'q1', lawAsOf: '2026-01-01', answerable: true }]
  const rows = [[], [claim(true, true, 'SUPPORTED', true)], Array.from({ length: 8 }, () => claim(true, true, 'SUPPORTED', true))]
    .flatMap((claims, index) => ['direct', 'rag', 'verified'].map(group => caseRow(group, claims,
      { run: index + 1, elapsedMs: [10, 20, 90][index] })))
  for (const group of summarizeLatency(rows, questions, 3).groups) {
    assert.equal(group.measured, 3)
    assert.equal(group.p50Ms, 20)
    assert.equal(group.p95Ms, 90)
  }
})

test('latency CLI reports missing time and rejects invalid inputs without publishing scores', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'latency-summary-'))
  const recordFile = path.join(directory, 'records.jsonl')
  const questionFile = path.join(directory, 'questions.jsonl')
  const script = fileURLToPath(new URL('../../scripts/summarize-evaluation-latency.mjs', import.meta.url))
  const invoke = (rounds, ...flags) => spawnSync(process.execPath, [script, recordFile, questionFile, rounds, ...flags], {
    encoding: 'utf8', timeout: 10000,
  })
  const rows = [caseRow('direct', [], { elapsedMs: 0 }), caseRow('rag', []),
    caseRow('verified', [], { elapsedMs: 32 })]
  try {
    await fs.writeFile(questionFile, JSON.stringify({ questionId: 'q1', lawAsOf: '2026-01-01', answerable: true }))
    await fs.writeFile(recordFile, rows.map(JSON.stringify).join('\n'))
    const complete = invoke('1')
    assert.equal(complete.status, 0, complete.stderr)
    const result = JSON.parse(complete.stdout)
    assert.deepEqual(result.groups, [
      { group: 'direct', measured: 1, missing: 0, p50Ms: 0, p95Ms: 0 },
      { group: 'rag', measured: 0, missing: 1, p50Ms: null, p95Ms: null },
      { group: 'verified', measured: 1, missing: 0, p50Ms: 32, p95Ms: 32 },
    ])
    const hash = bytes => createHash('sha256').update(bytes).digest('hex')
    assert.deepEqual(result.inputSha256, {
      records: hash(await fs.readFile(recordFile)),
      frozenQuestions: hash(await fs.readFile(questionFile)),
    })
    await fs.appendFile(recordFile, '\n')
    const reformatted = invoke('1')
    assert.equal(reformatted.status, 0, reformatted.stderr)
    const reformattedResult = JSON.parse(reformatted.stdout)
    assert.deepEqual(reformattedResult.groups, result.groups)
    assert.notEqual(reformattedResult.inputSha256.records, result.inputSha256.records)
    assert.equal(reformattedResult.inputSha256.frozenQuestions, result.inputSha256.frozenQuestions)
    const manifestFlag = '--manifest-sha256=' + result.inputSha256.frozenQuestions.toUpperCase()
    const pinned = invoke('1', manifestFlag)
    assert.equal(pinned.status, 0, pinned.stderr)
    const pinnedResult = JSON.parse(pinned.stdout)
    assert.equal(pinnedResult.expectedManifestSha256, result.inputSha256.frozenQuestions)
    const evaluationScript = fileURLToPath(new URL('../../scripts/legal-citation-evaluation.mjs', import.meta.url))
    const audited = spawnSync(process.execPath,
      [evaluationScript, recordFile, questionFile, '--rounds=1', manifestFlag, '--audit'],
      { encoding: 'utf8', timeout: 10000 })
    assert.equal(audited.status, 0, audited.stderr)
    const evaluation = JSON.parse(audited.stdout)
    assert.deepEqual(pinnedResult.inputSha256, evaluation.inputSha256)
    assert.equal(pinnedResult.expectedManifestSha256, evaluation.expectedManifestSha256)
    assert.equal(pinnedResult.coverage.roundsPerQuestion, evaluation.checkedRoundsPerQuestion)
    await fs.appendFile(questionFile, '\n')
    const changedManifest = invoke('1', manifestFlag)
    assert.equal(changedManifest.status, 1, changedManifest.stderr)
    assert.match(changedManifest.stderr, /预登记 SHA-256 不一致/)
    assert.equal(changedManifest.stdout, '')
    for (const flags of [['--manifest-sha256=bad'], [manifestFlag, manifestFlag], ['--unknown']]) {
      const invalid = invoke('1', ...flags)
      assert.equal(invalid.status, 1, invalid.stderr)
      assert.equal(invalid.stdout, '')
    }
    for (const rounds of ['01', '101', '2']) {
      const invalid = invoke(rounds)
      assert.equal(invalid.status, 1, invalid.stderr)
      assert.equal(invalid.stdout, '')
    }
    await fs.writeFile(recordFile, [...rows.slice(0, 2), { ...rows[2], elapsedMs: '32' }].map(JSON.stringify).join('\n'))
    const invalid = invoke('1')
    assert.equal(invalid.status, 1, invalid.stderr)
    assert.equal(invalid.stdout, '')
    assert.match(invalid.stderr, /verified.*elapsedMs/)
  } finally {
    await fs.rm(recordFile, { force: true })
    await fs.rm(questionFile, { force: true })
    await fs.rmdir(directory)
  }
})

test('preflight checks planned rounds even when three groups omit the same round', () => {
  const rows = ['q1', 'q2'].flatMap(questionId => [1, 2].flatMap(run =>
    ['direct', 'rag', 'verified'].map(group => caseRow(group, [], { questionId, run }))))
  const questions = ['q1', 'q2'].map(questionId => ({ questionId, lawAsOf: '2026-01-01', answerable: true }))
  assert.deepEqual(checkRounds(rows, questions, 2), { questions: 2, roundsPerQuestion: 2, records: 12 })
  assert.throws(() => checkRounds(rows.filter(row => row.run === 1), questions, 2), /预定轮数/)
  assert.throws(() => checkRounds(rows, questions, 1), /预定轮数/)
  assert.throws(() => checkRounds(rows, questions, 101), /1–100/)
})

test('round-preflight CLI emits a result only for complete planned runs', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'round-preflight-'))
  const recordFile = path.join(directory, 'records.jsonl')
  const questionFile = path.join(directory, 'questions.jsonl')
  const script = fileURLToPath(new URL('../../scripts/check-evaluation-rounds.mjs', import.meta.url))
  const invoke = rounds => spawnSync(process.execPath, [script, recordFile, questionFile, String(rounds)], {
    encoding: 'utf8', timeout: 10000,
  })
  const questions = [{ questionId: 'q1', lawAsOf: '2026-01-01', answerable: true }]
  const rows = [1, 2].flatMap(run => ['direct', 'rag', 'verified'].map(group =>
    caseRow(group, [], { run })))
  try {
    await fs.writeFile(questionFile, questions.map(JSON.stringify).join('\n'))
    await fs.writeFile(recordFile, rows.map(JSON.stringify).join('\n'))
    const complete = invoke(2)
    assert.equal(complete.status, 0, complete.stderr)
    assert.deepEqual(JSON.parse(complete.stdout), { questions: 1, roundsPerQuestion: 2, records: 6 })
    await fs.writeFile(recordFile, rows.filter(record => record.run === 1).map(JSON.stringify).join('\n'))
    const incomplete = invoke(2)
    assert.notEqual(incomplete.status, 0)
    assert.equal(incomplete.stdout, '')
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
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

test('unsupported breakdown partitions only displayed, adjudicated unsupported claims', () => {
  const claims = [claim(false, false, 'UNSUPPORTED', true), claim(true, false, 'UNSUPPORTED', true),
    claim(true, true, 'UNSUPPORTED', true), claim(true, true, 'UNSUPPORTED', false),
    claim(true, true, 'UNCERTAIN', true), claim(true, true, 'SUPPORTED', true)]
  const rows = ['direct', 'rag', 'verified'].map(group => caseRow(group, claims))
  for (const result of evaluate(rows)) {
    assert.deepEqual(result.unsupportedDisplayedBreakdown,
      { sourceNotLocated: 1, locatedQuoteNotMatched: 1, matchedQuoteNotSupporting: 1 })
    assert.equal(result.metricCounts.unsupportedDisplayedClaims, 3)
    assert.equal(result.unsupportedDisplayedRate, null)
  }
})

test('counts displayed conclusions without located citations as answers rather than refusals', () => {
  const rows = ['direct', 'rag', 'verified'].map(group =>
    caseRow(group, [claim(false, false, 'UNSUPPORTED', true)]))
  for (const result of evaluate(rows)) {
    assert.equal(result.metricCounts.generatedClaims, 1)
    assert.equal(result.metricCounts.displayedClaims, 1)
    assert.equal(result.locatedRate, 0)
    assert.equal(result.quoteAccuracy, 0)
    assert.equal(result.humanSupportRate, 0)
    assert.equal(result.unsupportedDisplayedRate, 1)
    assert.equal(result.refusalRate, 0)
    assert.equal(result.falseRefusalRate, 0)
  }
})

test('keeps hidden unsupported claims in generation metrics but outside displayed risk', () => {
  const rows = ['direct', 'rag', 'verified'].map(group =>
    caseRow(group, [
      claim(true, true, 'SUPPORTED', true),
      claim(false, false, 'UNSUPPORTED', false),
    ]))
  for (const result of evaluate(rows)) {
    assert.equal(result.metricCounts.generatedClaims, 2)
    assert.equal(result.metricCounts.displayedClaims, 1)
    assert.equal(result.metricCounts.unsupportedDisplayedClaims, 0)
    assert.equal(result.locatedRate, 0.5)
    assert.equal(result.humanSupportRate, 0.5)
    assert.equal(result.unsupportedDisplayedRate, 0)
    assert.equal(result.refusalRate, 0)
  }
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

test('rejects human support when the cited source is not located', () => {
  const rows = ['direct', 'rag', 'verified'].map(group =>
    caseRow(group, [claim(false, false, 'SUPPORTED', true)]))
  assert.throws(() => evaluate(rows), /来源未定位不能标记为人工支持/)
  const supportedWithoutMatchingQuote = rows.map(row =>
    ({ ...row, claims: [claim(true, false, 'SUPPORTED', true)] }))
  assert.equal(evaluate(supportedWithoutMatchingQuote)[0].humanSupportRate, 1)
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

test('CLI evaluates UTF-8 files outside the project and emits no scores on invalid input', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'legal-evaluation-cli-'))
  const file = path.join(directory, '人工标注.jsonl')
  const script = fileURLToPath(new URL('../../scripts/legal-citation-evaluation.mjs', import.meta.url))
  const rows = ['direct', 'rag', 'verified'].map(group => caseRow(group, []))
  try {
    await fs.writeFile(file, rows.map(JSON.stringify).join('\n'))
    const success = spawnSync(process.execPath, [script, file], {
      cwd: directory, encoding: 'utf8', timeout: 10000,
    })
    assert.ifError(success.error)
    assert.equal(success.status, 0, success.stderr)
    assert.equal(success.stderr, '')
    assert.deepEqual(JSON.parse(success.stdout), evaluate(rows))
    await fs.writeFile(file, rows.slice(1).map(JSON.stringify).join('\n'))
    const failure = spawnSync(process.execPath, [script, file], {
      cwd: directory, encoding: 'utf8', timeout: 10000,
    })
    assert.ifError(failure.error)
    assert.equal(failure.status, 1)
    assert.equal(failure.stdout, '')
    assert.match(failure.stderr, /三个实验组/)
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
})

test('checks complete frozen question coverage even when all groups omit the same question', () => {
  const rows = ['direct', 'rag', 'verified'].map(group => caseRow(group, []))
  const frozen = [{ questionId: 'q1', lawAsOf: '2026-01-01', answerable: true }]
  assert.equal(evaluate(rows)[0].frozenQuestionCoverageChecked, false)
  assert.ok(evaluate(rows, frozen).every(result => result.frozenQuestionCoverageChecked))
  assert.throws(() => evaluate(rows, [...frozen, { questionId: 'q2', lawAsOf: '2026-01-01', answerable: true }]), /完整覆盖/)
  assert.throws(() => evaluate(rows, [{ questionId: 'other-question', lawAsOf: '2026-01-01', answerable: true }]), /完整覆盖/)
  assert.throws(() => evaluate(rows, [{ questionId: 'q1', lawAsOf: '2026-01-02', answerable: true }]), /日期不一致/)
  assert.throws(() => evaluate(rows, [{ questionId: 'q1', lawAsOf: '2026-01-01', answerable: false }]), /可回答标注.*冻结题目/)
  assert.throws(() => evaluate(rows, [{ questionId: 'q1', lawAsOf: '2026-01-01' }]), /缺少.*answerable/)
  assert.throws(() => evaluate(rows, []), /不能为空/)
  assert.throws(() => evaluate(rows, [...frozen, ...frozen]), /ID 重复/)
  assert.throws(() => evaluate(rows, [{ questionId: ' q1 ', lawAsOf: '2026-01-01', answerable: true }]), /ID 无效/)
  assert.throws(() => evaluate(rows, [{ questionId: 'q1', lawAsOf: '2026-02-30', answerable: true }]), /日期无效/)
})

test('CLI checks the supplied frozen manifest and emits no scores on omitted questions', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'legal-frozen-coverage-'))
  const file = path.join(directory, 'labels.jsonl')
  const manifest = path.join(directory, 'questions.jsonl')
  const script = fileURLToPath(new URL('../../scripts/legal-citation-evaluation.mjs', import.meta.url))
  const rows = ['direct', 'rag', 'verified'].map(group => caseRow(group, []))
  try {
    await fs.writeFile(file, rows.map(JSON.stringify).join('\n'))
    await fs.writeFile(manifest, JSON.stringify({ questionId: 'q1', lawAsOf: '2026-01-01', answerable: true }))
    const success = spawnSync(process.execPath, [script, file, manifest], {
      cwd: directory, encoding: 'utf8', timeout: 10000,
    })
    assert.ifError(success.error)
    assert.equal(success.status, 0, success.stderr)
    assert.ok(JSON.parse(success.stdout).every(result => result.frozenQuestionCoverageChecked))
    await fs.writeFile(manifest, JSON.stringify({ questionId: 'q1', lawAsOf: '2026-01-01', answerable: false }))
    const mislabeled = spawnSync(process.execPath, [script, file, manifest], {
      cwd: directory, encoding: 'utf8', timeout: 10000,
    })
    assert.ifError(mislabeled.error)
    assert.equal(mislabeled.status, 1)
    assert.equal(mislabeled.stdout, '')
    assert.match(mislabeled.stderr, /可回答标注.*冻结题目/)
    await fs.writeFile(manifest, JSON.stringify({ questionId: 'q1', lawAsOf: '2026-01-01', answerable: true }))
    await fs.appendFile(manifest, '\n' + JSON.stringify({ questionId: 'q2', lawAsOf: '2026-01-01', answerable: true }))
    const failure = spawnSync(process.execPath, [script, file, manifest], {
      cwd: directory, encoding: 'utf8', timeout: 10000,
    })
    assert.ifError(failure.error)
    assert.equal(failure.status, 1)
    assert.equal(failure.stdout, '')
    assert.match(failure.stderr, /完整覆盖/)
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
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
    await fs.writeFile(file, JSON.stringify(rows[0]) + '\n' + '\uFEFF' + JSON.stringify(rows[1]))
    await assert.rejects(readJsonl(file), /第 2 行/)
    await fs.writeFile(file, '\uFEFF\uFEFF' + JSON.stringify(rows[0]))
    await assert.rejects(readJsonl(file), /第 1 行/)
    const jsonRows = rows.map(JSON.stringify)
    await fs.writeFile(file, '\uFEFF' + jsonRows.join('\r\n \t\r\n'))
    assert.deepEqual(await readJsonl(file), rows)
    for (const strayLine of [' \uFEFF\t', '\u00A0']) {
      await fs.writeFile(file, [jsonRows[0], strayLine, ...jsonRows.slice(1)].join('\n'))
      await assert.rejects(readJsonl(file), /第 2 行/)
    }
    await fs.writeFile(file, '\uFEFF\uFEFF\n' + jsonRows.join('\n'))
    await assert.rejects(readJsonl(file), /第 1 行/)
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
})


test('rejects invalid UTF-8 rather than silently changing question IDs', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'legal-evaluation-encoding-'))
  const file = path.join(directory, 'labels.jsonl')
  const script = fileURLToPath(new URL('../../scripts/legal-citation-evaluation.mjs', import.meta.url))
  const rows = ['direct', 'rag', 'verified'].map(group => caseRow(group, []))
  try {
    const text = rows.map(JSON.stringify).join('\n')
    for (const invalidBytes of [Buffer.from([0x80]), Buffer.from([0xe4, 0xb8])]) {
      await fs.writeFile(file, Buffer.concat([
        Buffer.from(text.replaceAll('q1', '')),
        invalidBytes,
      ]))
      await assert.rejects(readJsonl(file), /UTF-8/)
    }
    const questionOffset = text.indexOf('q1')
    await fs.writeFile(file, Buffer.concat([
      Buffer.from(text.slice(0, questionOffset)), Buffer.from([0x80]),
      Buffer.from(text.slice(questionOffset + 2)),
    ]))
    await assert.rejects(readJsonl(file), /UTF-8/)
    const failure = spawnSync(process.execPath, [script, file], {
      cwd: directory, encoding: 'utf8', timeout: 10000,
    })
    assert.ifError(failure.error)
    assert.equal(failure.status, 1)
    assert.equal(failure.stdout, '')
    assert.match(failure.stderr, /UTF-8/)
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

const bootstrapGroups = ['direct', 'rag', 'verified']
const bootstrapMetricNames = ['locatedRate', 'quoteAccuracy', 'humanSupportRate',
  'unsupportedDisplayedRate', 'refusalRate', 'expectedRefusalRate', 'falseRefusalRate']
const bootstrapFixture = (claimsFor, runs = 1) => ({
  frozen: ['q1', 'q2'].map(questionId => ({ questionId, lawAsOf: '2026-01-01', answerable: true })),
  rows: bootstrapGroups.flatMap(group => ['q1', 'q2'].flatMap(questionId =>
    Array.from({ length: runs }, (_, index) => caseRow(group, claimsFor(group, questionId, index + 1), { questionId, run: index + 1 })))),
})
const assertClose = (actual, expected) => {
  assert.equal(typeof actual, 'number')
  assert.ok(Math.abs(actual - expected) < 1e-12, `${actual} differs from ${expected}`)
}

test('paired bootstrap is reproducible, row-order independent and reports its sampling contract', () => {
  const { rows, frozen } = bootstrapFixture((group, questionId) =>
    [claim(questionId === 'q1', questionId === 'q1', questionId === 'q1' ? 'SUPPORTED' : 'UNSUPPORTED', true)])
  const options = { iterations: 1000, seed: 0 }
  const before = JSON.stringify({ rows, frozen, options })
  const result = pairedQuestionBootstrap(rows, frozen, options)
  assert.deepEqual(result, pairedQuestionBootstrap(rows, frozen, options))
  assert.deepEqual(result, pairedQuestionBootstrap([...rows].reverse(), [...frozen].reverse(), options))
  assert.equal(JSON.stringify({ rows, frozen, options }), before)
  assert.equal(result.method, 'paired-question-percentile-bootstrap')
  assert.equal(result.confidenceLevel, 0.95)
  assert.equal(result.seed, 0)
  assert.equal(result.iterations, 1000)
  assert.equal(result.samplingUnit, 'questionId')
  assert.equal(result.questions, 2)
  assert.equal(result.percentileRule, 'sorted[floor((B-1)*p)]')
  assert.equal(result.randomGenerator, 'mulberry32-v1')
  assert.deepEqual(result.point, evaluate(rows, frozen))
  for (const group of bootstrapGroups) {
    assert.deepEqual(Object.keys(result.groups[group]).sort(), [...bootstrapMetricNames].sort())
    for (const metric of bootstrapMetricNames) {
      const summary = result.groups[group][metric]
      assert.equal(summary.point, result.point.find(item => item.group === group)[metric])
      assert.equal(summary.validResamples + summary.undefinedResamples, 1000)
    }
  }
  const defaults = pairedQuestionBootstrap(rows, frozen)
  assert.equal(defaults.seed, 1)
  assert.equal(defaults.iterations, 2000)
})

test('paired bootstrap uses the same question draws for all groups and subtracts left minus right', () => {
  const { rows, frozen } = bootstrapFixture((group, questionId) =>
    [claim(questionId === 'q1', questionId === 'q1', questionId === 'q1' ? 'SUPPORTED' : 'UNSUPPORTED', true)])
  const result = pairedQuestionBootstrap(rows, frozen, { iterations: 1000, seed: 42 })
  for (const metric of ['locatedRate', 'quoteAccuracy', 'humanSupportRate', 'unsupportedDisplayedRate']) {
    for (const pair of ['verified-direct', 'verified-rag', 'rag-direct']) {
      assert.deepEqual(result.differences[metric][pair], {
        point: 0, interval: { low: 0, high: 0 }, validResamples: 1000, undefinedResamples: 0, reason: null,
      })
    }
  }
  const ordered = bootstrapFixture(group => [claim(group !== 'direct', group !== 'direct', group === 'direct' ? 'UNSUPPORTED' : 'SUPPORTED', true)])
  const directional = pairedQuestionBootstrap(ordered.rows, ordered.frozen, { iterations: 100, seed: 1 })
  for (const [pair, difference] of [['verified-direct', 1], ['verified-rag', 0], ['rag-direct', 1]]) {
    assert.deepEqual(directional.differences.locatedRate[pair].interval, { low: difference, high: difference })
    assert.equal(directional.differences.locatedRate[pair].point, difference)
  }
})

test('bootstrap recomputes pooled counts instead of averaging per-question ratios', () => {
  const { rows, frozen } = bootstrapFixture((group, questionId) => {
    const verified = group === 'verified'
    const total = (verified ? questionId === 'q2' : questionId === 'q1') ? 10 : 1
    const located = verified ? 1 : questionId === 'q1' ? 9 : 0
    return Array.from({ length: total }, (_, index) =>
      claim(index < located, index < located, index < located ? 'SUPPORTED' : 'UNSUPPORTED', true))
  })
  const result = pairedQuestionBootstrap(rows, frozen, { iterations: 1000, seed: 42 })
  assertClose(result.groups.direct.locatedRate.point, 9 / 11)
  assertClose(result.groups.verified.locatedRate.point, 2 / 11)
  const difference = result.differences.locatedRate['verified-direct']
  assertClose(difference.point, -7 / 11)
  assertClose(difference.interval.low, -7 / 11)
  assertClose(difference.interval.high, 0.1)
  assert.equal(difference.validResamples, 1000)
  assert.equal(difference.undefinedResamples, 0)
  assert.equal(difference.reason, null)
})

test('bootstrap keeps every run and claim of a question together as one cluster', () => {
  const { rows, frozen } = bootstrapFixture((group, questionId, run) => {
    const supported = (questionId === 'q1') === (run === 1)
    return [claim(supported, supported, supported ? 'SUPPORTED' : 'UNSUPPORTED', true)]
  }, 2)
  const result = pairedQuestionBootstrap(rows, frozen, { iterations: 1000, seed: 42 })
  for (const point of result.point) {
    assert.equal(point.questions, 2)
    assert.equal(point.runsPerQuestion, 2)
    assert.equal(point.runs, 4)
  }
  for (const group of bootstrapGroups) {
    for (const metric of ['locatedRate', 'quoteAccuracy', 'humanSupportRate', 'unsupportedDisplayedRate']) {
      assert.deepEqual(result.groups[group][metric], {
        point: 0.5, interval: { low: 0.5, high: 0.5 }, validResamples: 1000, undefinedResamples: 0, reason: null,
      })
    }
  }
})

test('undefined resamples suppress intervals instead of being silently discarded', () => {
  const { rows, frozen } = bootstrapFixture((group, questionId) =>
    questionId === 'q1' ? [] : [claim(true, true, 'SUPPORTED', true)])
  const result = pairedQuestionBootstrap(rows, frozen, { iterations: 1000, seed: 42 })
  for (const group of bootstrapGroups) {
    const located = result.groups[group].locatedRate
    assert.equal(located.point, 1)
    assert.equal(located.interval, null)
    assert.equal(located.reason, 'undefined-resamples')
    assert.ok(located.undefinedResamples > 0 && located.undefinedResamples < 1000)
    assert.equal(located.validResamples + located.undefinedResamples, 1000)
    const refusal = result.groups[group].expectedRefusalRate
    assert.deepEqual(refusal, { point: null, interval: null, validResamples: 0, undefinedResamples: 1000, reason: 'point-undefined' })
  }
  const difference = result.differences.locatedRate['verified-direct']
  assert.equal(difference.point, 0)
  assert.equal(difference.interval, null)
  assert.equal(difference.reason, 'undefined-resamples')
  assert.equal(difference.undefinedResamples, result.groups.direct.locatedRate.undefinedResamples)
  assert.equal(difference.validResamples + difference.undefinedResamples, 1000)
})

test('unresolved human support never acquires a confidence interval', () => {
  const { rows, frozen } = bootstrapFixture(() => [claim(true, true, 'UNCERTAIN', true)])
  const result = pairedQuestionBootstrap(rows, frozen, { iterations: 100, seed: 0xffffffff })
  assert.equal(result.seed, 0xffffffff)
  for (const metric of ['humanSupportRate', 'unsupportedDisplayedRate']) {
    for (const summary of [
      ...bootstrapGroups.map(group => result.groups[group][metric]),
      ...Object.values(result.differences[metric]),
    ]) {
      assert.deepEqual(summary, { point: null, interval: null, validResamples: 0, undefinedResamples: 100, reason: 'point-undefined' })
    }
  }
  assert.deepEqual(result.groups.verified.locatedRate.interval, { low: 1, high: 1 })
})

test('bootstrap requires a frozen manifest, two question clusters and complete validated records', () => {
  const { rows, frozen } = bootstrapFixture(() => [claim(true, true, 'SUPPORTED', true)])
  const options = { iterations: 100, seed: 1 }
  assert.throws(() => pairedQuestionBootstrap(rows, undefined, options))
  assert.throws(() => pairedQuestionBootstrap(rows, [], options))
  assert.throws(() => pairedQuestionBootstrap(rows.filter(row => row.questionId === 'q1'), frozen.slice(0, 1), options))
  assert.throws(() => pairedQuestionBootstrap(rows.slice(1), frozen, options), /三个实验组/)
  assert.throws(() => pairedQuestionBootstrap([...rows, rows[0]], frozen, options), /重复/)
  assert.throws(() => pairedQuestionBootstrap(rows, frozen.map(item => ({ ...item, answerable: false })), options), /可回答标注/)
  assert.throws(() => pairedQuestionBootstrap(rows.map((row, index) => index === 0
    ? { ...row, claims: [claim(false, true, 'UNSUPPORTED', true)] } : row), frozen, options), /来源不存在/)
})

test('a null full-sample point remains undefined even when some resamples are judgeable', () => {
  const { rows, frozen } = bootstrapFixture((group, questionId) =>
    [claim(true, true, group === 'verified' && questionId === 'q1' ? 'UNCERTAIN' : 'SUPPORTED', true)])
  const result = pairedQuestionBootstrap(rows, frozen, { iterations: 1000, seed: 42 })
  const verified = result.groups.verified.unsupportedDisplayedRate
  assert.equal(verified.point, null)
  assert.equal(verified.interval, null)
  assert.equal(verified.reason, 'point-undefined')
  assert.ok(verified.validResamples > 0 && verified.undefinedResamples > 0)
  assert.equal(verified.validResamples + verified.undefinedResamples, 1000)
  assert.deepEqual(result.groups.direct.unsupportedDisplayedRate, {
    point: 0, interval: { low: 0, high: 0 }, validResamples: 1000, undefinedResamples: 0, reason: null,
  })
  for (const pair of ['verified-direct', 'verified-rag']) {
    const difference = result.differences.unsupportedDisplayedRate[pair]
    assert.equal(difference.point, null)
    assert.equal(difference.interval, null)
    assert.equal(difference.reason, 'point-undefined')
    assert.equal(difference.validResamples, verified.validResamples)
    assert.equal(difference.undefinedResamples, verified.undefinedResamples)
  }
  const support = result.groups.verified.humanSupportRate
  assert.equal(support.point, 1)
  assert.equal(support.interval, null)
  assert.equal(support.reason, 'undefined-resamples')
  assert.ok(support.validResamples > 0 && support.undefinedResamples > 0)
  assert.equal(support.validResamples + support.undefinedResamples, 1000)
})

test('bootstrap rejects non-integer or out-of-range iteration counts and non-uint32 seeds', () => {
  const { rows, frozen } = bootstrapFixture(() => [])
  for (const iterations of [0, 99, 100001, 100.5, NaN, Infinity, '100', null, true]) {
    assert.throws(() => pairedQuestionBootstrap(rows, frozen, { iterations, seed: 1 }), `iterations=${iterations}`)
  }
  for (const seed of [-1, 0x100000000, 1.5, NaN, Infinity, '1', null, true]) {
    assert.throws(() => pairedQuestionBootstrap(rows, frozen, { iterations: 100, seed }), `seed=${seed}`)
  }
})

test('CLI keeps default point estimates and exposes bootstrap only with explicit valid paired flags', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'legal-bootstrap-cli-'))
  const file = path.join(directory, 'labels.jsonl')
  const manifest = path.join(directory, 'questions.jsonl')
  const script = fileURLToPath(new URL('../../scripts/legal-citation-evaluation.mjs', import.meta.url))
  const { rows, frozen } = bootstrapFixture(() => [claim(true, true, 'SUPPORTED', true)])
  const invoke = argumentsList => {
    const result = spawnSync(process.execPath, [script, ...argumentsList], { cwd: directory, encoding: 'utf8', timeout: 10000 })
    assert.ifError(result.error)
    return result
  }
  try {
    await fs.writeFile(file, rows.map(JSON.stringify).join('\n'))
    await fs.writeFile(manifest, frozen.map(JSON.stringify).join('\n'))
    for (const argumentsList of [[file], [file, manifest]]) {
      const result = invoke(argumentsList)
      assert.equal(result.status, 0, result.stderr)
      assert.equal(result.stderr, '')
      assert.deepEqual(JSON.parse(result.stdout), evaluate(rows, argumentsList.length === 2 ? frozen : undefined))
    }
    const success = invoke([file, manifest, '--bootstrap=100', '--seed=0'])
    assert.equal(success.status, 0, success.stderr)
    assert.equal(success.stderr, '')
    assert.deepEqual(JSON.parse(success.stdout), pairedQuestionBootstrap(rows, frozen, { iterations: 100, seed: 0 }))
    for (const flags of [[], ['--bootstrap=100', '--seed=0']]) {
      const complete = invoke([file, manifest, '--rounds=1', ...flags])
      assert.equal(complete.status, 0, complete.stderr)
      assert.deepEqual(JSON.parse(complete.stdout), flags.length ? JSON.parse(success.stdout) : evaluate(rows, frozen))
      const audited = invoke([file, manifest, '--rounds=1', ...flags, '--audit'])
      assert.equal(audited.status, 0, audited.stderr)
      const hash = bytes => createHash('sha256').update(bytes).digest('hex')
      assert.deepEqual(JSON.parse(audited.stdout), {
        inputSha256: { records: hash(await fs.readFile(file)), frozenQuestions: hash(await fs.readFile(manifest)) },
        checkedRoundsPerQuestion: 1,
        result: JSON.parse(complete.stdout),
      })
      const omittedFinalRound = invoke([file, manifest, '--rounds=2', ...flags])
      assert.equal(omittedFinalRound.status, 1)
      assert.equal(omittedFinalRound.stdout, '')
      assert.match(omittedFinalRound.stderr, /预定轮数/)
      const auditedOmission = invoke([file, manifest, '--rounds=2', ...flags, '--audit'])
      assert.equal(auditedOmission.status, 1)
      assert.equal(auditedOmission.stdout, '')
      assert.match(auditedOmission.stderr, /预定轮数/)
    }
    for (const argumentsList of [
      [file, '--rounds=1'],
      [file, '--audit'],
      [file, manifest, '--audit'],
      [file, manifest, '--rounds=1', '--audit', '--audit'],
      [file, manifest, '--rounds=0'],
      [file, manifest, '--rounds=101'],
      [file, manifest, '--rounds=01'],
      [file, manifest, '--rounds=1', '--rounds=1'],
      [file, '--bootstrap=100', '--seed=1'],
      [file, manifest, '--bootstrap=100'],
      [file, manifest, '--seed=1'],
      [file, manifest, '--unknown=1'],
      [file, manifest, '--bootstrap=100', '--seed=1', '--seed=1'],
      [file, manifest, '--bootstrap=100', '--bootstrap=100', '--seed=1'],
      [file, manifest, '--bootstrap=99', '--seed=1'],
      [file, manifest, '--bootstrap=100001', '--seed=1'],
      [file, manifest, '--bootstrap=100.5', '--seed=1'],
      [file, manifest, '--bootstrap=NaN', '--seed=1'],
      [file, manifest, '--bootstrap=1e3', '--seed=1'],
      [file, manifest, '--bootstrap=', '--seed=1'],
      [file, manifest, '--bootstrap=100', '--seed=-1'],
      [file, manifest, '--bootstrap=100', '--seed=4294967296'],
      [file, manifest, '--bootstrap=100', '--seed=1.5'],
      [file, manifest, '--bootstrap=100', '--seed=Infinity'],
      [file, manifest, '--bootstrap=100', '--seed=1e3'],
      [file, manifest, '--bootstrap=100', '--seed='],
      [file, manifest, '--bootstrap=100', '--seed=1', '--unknown=1'],
      [file, manifest, '--bootstrap=100', '--seed=1', 'unexpected-extra-file'],
    ]) {
      const failure = invoke(argumentsList)
      assert.equal(failure.status, 1, JSON.stringify(argumentsList))
      assert.equal(failure.stdout, '', JSON.stringify(argumentsList))
      assert.notEqual(failure.stderr, '', JSON.stringify(argumentsList))
    }
    const manifestBytes = await fs.readFile(manifest)
    const manifestDigest = createHash('sha256').update(manifestBytes).digest('hex')
    const manifestFlag = '--manifest-sha256=' + manifestDigest.toUpperCase()
    const matched = invoke([file, manifest, '--rounds=1', '--audit', manifestFlag])
    assert.equal(matched.status, 0, matched.stderr)
    assert.equal(JSON.parse(matched.stdout).expectedManifestSha256, manifestDigest)
    await fs.appendFile(manifest, '\n')
    for (const args of [[], ['--bootstrap=100', '--seed=0', '--rounds=1', '--audit']]) {
      const mismatch = invoke([file, manifest, manifestFlag, ...args])
      assert.equal(mismatch.status, 1)
      assert.equal(mismatch.stdout, '')
      assert.match(mismatch.stderr, /SHA-256.*不一致/)
    }
    await fs.writeFile(manifest, manifestBytes)
    for (const args of [[file, manifestFlag], [file, manifest, manifestFlag, manifestFlag], [file, manifest, '--manifest-sha256=bad']]) {
      const invalid = invoke(args)
      assert.equal(invalid.status, 1)
      assert.equal(invalid.stdout, '')
    }
    await fs.writeFile(file, rows.slice(1).map(JSON.stringify).join('\n'))
    const invalidRecords = invoke([file, manifest, '--bootstrap=100', '--seed=1'])
    assert.equal(invalidRecords.status, 1)
    assert.equal(invalidRecords.stdout, '')
    assert.match(invalidRecords.stderr, /三个实验组/)
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
})
