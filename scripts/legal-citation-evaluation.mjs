import fs from 'node:fs/promises'
import { pathToFileURL } from 'node:url'

const groups = ['direct', 'rag', 'verified']
const supportValues = ['SUPPORTED', 'UNSUPPORTED', 'UNCERTAIN']

function required(condition, message) {
  if (!condition) throw new Error(message)
}

function ratio(numerator, denominator) {
  return denominator === 0 ? null : numerator / denominator
}

export function evaluate(records) {
  required(Array.isArray(records) && records.length > 0, '标注记录不能为空')
  const seen = new Set()
  const answerability = new Map()
  const legalDates = new Map()
  const promptVersions = new Map()
  let modelVersion
  let corpusSnapshotId
  const covered = Object.fromEntries(groups.map(group => [group, new Set()]))
  for (const [index, record] of records.entries()) {
    const at = '第 ' + (index + 1) + ' 条记录'
    required(record && typeof record === 'object' && !Array.isArray(record), at + '不是对象')
    required(groups.includes(record.group), at + '实验组无效')
    required(typeof record.questionId === 'string' && record.questionId.length > 0
      && record.questionId.trim() === record.questionId
      && !/[\r\n]/.test(record.questionId), at + '问题 ID 不能为空或包含首尾空格/换行')
    required(Number.isSafeInteger(record.run) && record.run > 0, at + '运行次数必须为正整数')
    for (const key of ['modelVersion', 'promptVersion', 'corpusSnapshotId']) {
      required(typeof record[key] === 'string' && record[key].trim(), at + '缺少 ' + key)
    }
    required(typeof record.lawAsOf === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(record.lawAsOf)
      && !Number.isNaN(Date.parse(record.lawAsOf))
      && new Date(record.lawAsOf).toISOString().slice(0, 10) === record.lawAsOf,
    at + '缺少有效的适用法律日期 lawAsOf')
    if (modelVersion === undefined) modelVersion = record.modelVersion
    required(modelVersion === record.modelVersion, at + '模型版本不一致')
    if (corpusSnapshotId === undefined) corpusSnapshotId = record.corpusSnapshotId
    required(corpusSnapshotId === record.corpusSnapshotId, at + '语料快照不一致')
    if (!promptVersions.has(record.group)) promptVersions.set(record.group, record.promptVersion)
    required(promptVersions.get(record.group) === record.promptVersion, at + '同一实验组的提示词版本不一致')
    if (!legalDates.has(record.questionId)) legalDates.set(record.questionId, record.lawAsOf)
    required(legalDates.get(record.questionId) === record.lawAsOf, at + '同一问题的法律适用日期不一致')
    required(typeof record.answerable === 'boolean' && typeof record.refused === 'boolean', at + '缺少可回答/拒答标注')
    required(Array.isArray(record.claims), at + '缺少主张数组')
    required(record.refused === !record.claims.some(claim => claim && claim.displayed === true),
      at + '拒答标注与展示主张不一致')
    if (answerability.has(record.questionId)) {
      required(answerability.get(record.questionId) === record.answerable, at + '同一问题的可回答标注不一致')
    } else {
      answerability.set(record.questionId, record.answerable)
    }
    const caseKey = JSON.stringify([record.questionId, record.run])
    const uniqueKey = record.group + '#' + caseKey
    required(!seen.has(uniqueKey), at + '问题、轮次与实验组重复')
    seen.add(uniqueKey)
    covered[record.group].add(caseKey)
    for (const [claimIndex, claim] of record.claims.entries()) {
      const claimAt = at + '的第 ' + (claimIndex + 1) + ' 条主张'
      required(claim && typeof claim === 'object', claimAt + '不是对象')
      required(typeof claim.evidenceLocated === 'boolean' && typeof claim.quoteMatched === 'boolean'
        && typeof claim.displayed === 'boolean', claimAt + '缺少来源/摘录/展示状态')
      required(supportValues.includes(claim.humanSupport), claimAt + '人工支持状态无效')
      required(!claim.quoteMatched || claim.evidenceLocated, claimAt + '原文匹配但来源不存在')
      required(!record.refused || !claim.displayed, claimAt + '已拒答却对外展示主张')
    }
  }
  const rounds = new Map()
  for (const record of records.filter(item => item.group === 'direct')) {
    if (!rounds.has(record.questionId)) rounds.set(record.questionId, new Set())
    rounds.get(record.questionId).add(record.run)
  }
  const counts = [...rounds.values()].map(values => values.size)
  required(counts.every(count => count === counts[0])
    && [...rounds.values()].every(values => [...values].every(run => run <= values.size)),
  '每道问题必须有相同次数且从 1 开始连续的运行轮次')
  const baseline = [...covered.direct].sort().join('\n')
  required(baseline.length > 0 && groups.every(group => [...covered[group]].sort().join('\n') === baseline),
    '三个实验组必须覆盖相同的问题 ID 与运行轮次')

  return groups.map(group => {
    const items = records.filter(record => record.group === group)
    const claims = items.flatMap(record => record.claims)
    const judged = claims.filter(claim => claim.humanSupport !== 'UNCERTAIN')
    const displayed = claims.filter(claim => claim.displayed)
    const unjudgedDisplayed = displayed.filter(claim => claim.humanSupport === 'UNCERTAIN').length
    const answerable = items.filter(item => item.answerable)
    const unanswerable = items.filter(item => !item.answerable)
    const metricCounts = {
      generatedClaims: claims.length,
      locatedClaims: claims.filter(claim => claim.evidenceLocated).length,
      matchedQuotes: claims.filter(claim => claim.quoteMatched).length,
      judgedClaims: judged.length,
      supportedClaims: judged.filter(claim => claim.humanSupport === 'SUPPORTED').length,
      displayedClaims: displayed.length,
      unsupportedDisplayedClaims: displayed.filter(claim => claim.humanSupport === 'UNSUPPORTED').length,
      refusedRuns: items.filter(item => item.refused).length,
      answerableRuns: answerable.length,
      refusedAnswerableRuns: answerable.filter(item => item.refused).length,
      unanswerableRuns: unanswerable.length,
      refusedUnanswerableRuns: unanswerable.filter(item => item.refused).length,
    }
    return {
      group, modelVersion, promptVersion: promptVersions.get(group), corpusSnapshotId,
      questions: rounds.size, runsPerQuestion: counts[0],
      runs: items.length, claims: claims.length, unjudgedClaims: claims.length - judged.length,
      metricCounts,
      locatedRate: ratio(metricCounts.locatedClaims, metricCounts.generatedClaims),
      quoteAccuracy: ratio(metricCounts.matchedQuotes, metricCounts.generatedClaims),
      humanSupportRate: ratio(metricCounts.supportedClaims, metricCounts.judgedClaims),
      unsupportedDisplayedRate: unjudgedDisplayed ? null
        : ratio(metricCounts.unsupportedDisplayedClaims, metricCounts.displayedClaims),
      unjudgedDisplayedClaims: unjudgedDisplayed,
      refusalRate: ratio(metricCounts.refusedRuns, items.length),
      expectedRefusalRate: ratio(metricCounts.refusedUnanswerableRuns, metricCounts.unanswerableRuns),
      falseRefusalRate: ratio(metricCounts.refusedAnswerableRuns, metricCounts.answerableRuns),
    }
  })
}

export async function readJsonl(file) {
  const lines = (await fs.readFile(file, 'utf8')).replace(/^\uFEFF/, '').split(/\r?\n/)
  return lines.flatMap((line, index) => {
    if (!line.trim()) return []
    try { return [JSON.parse(line)] } catch { throw new Error('第 ' + (index + 1) + ' 行不是合法 JSON') }
  })
}

const entry = process.argv[1] ? pathToFileURL(process.argv[1]).href : ''
if (import.meta.url === entry) {
  const file = process.argv[2]
  if (!file) {
    console.error('用法: node scripts/legal-citation-evaluation.mjs <标注记录.jsonl>')
    process.exitCode = 2
  } else {
    try {
      console.log(JSON.stringify(evaluate(await readJsonl(file)), null, 2))
    } catch (error) {
      console.error(error.message)
      process.exitCode = 1
    }
  }
}
