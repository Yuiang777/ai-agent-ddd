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

export function validateFrozenQuestions(questions) {
  required(Array.isArray(questions) && questions.length > 0, '冻结题目清单不能为空')
  const seen = new Set()
  for (const question of questions) {
    required(question && typeof question === 'object' && !Array.isArray(question)
      && typeof question.questionId === 'string' && question.questionId.trim()
      && question.questionId.trim() === question.questionId && !/[\r\n]/.test(question.questionId),
    '冻结题目清单的问题 ID 无效（questionId）')
    required(typeof question.lawAsOf === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(question.lawAsOf)
      && !Number.isNaN(Date.parse(question.lawAsOf))
      && new Date(question.lawAsOf).toISOString().slice(0, 10) === question.lawAsOf,
    '冻结题目清单的法律适用日期无效（lawAsOf）')
    required(typeof question.answerable === 'boolean', '冻结题目清单缺少布尔型 answerable 金标准')
    required(!seen.has(question.questionId), '冻结题目清单的问题 ID 重复')
    seen.add(question.questionId)
  }
}

export function evaluate(records, frozenQuestions) {
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
      required(claim.humanSupport !== 'SUPPORTED' || claim.evidenceLocated, claimAt + '来源未定位不能标记为人工支持')
      required(!record.refused || !claim.displayed, claimAt + '已拒答却对外展示主张')
    }
  }
  if (frozenQuestions !== undefined) {
    validateFrozenQuestions(frozenQuestions)
    const frozenDates = new Map()
    const frozenAnswerability = new Map()
    for (const question of frozenQuestions) {
      frozenDates.set(question.questionId, question.lawAsOf)
      frozenAnswerability.set(question.questionId, question.answerable)
    }
    required(frozenDates.size === legalDates.size
      && [...frozenDates.keys()].every(questionId => legalDates.has(questionId)),
    '标注记录必须完整覆盖冻结题目清单，不能漏题或增加题目')
    required([...frozenDates].every(([questionId, date]) => legalDates.get(questionId) === date),
      '标注记录与冻结题目清单的法律适用日期不一致')
    required([...frozenAnswerability].every(([questionId, expected]) => answerability.get(questionId) === expected),
      '标注记录的可回答标注与冻结题目金标准不一致')
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
      frozenQuestionCoverageChecked: frozenQuestions !== undefined,
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
  return parseJsonl(await fs.readFile(file))
}

const bootstrapMetrics = [
  ['locatedRate', 'locatedClaims', 'generatedClaims'],
  ['quoteAccuracy', 'matchedQuotes', 'generatedClaims'],
  ['humanSupportRate', 'supportedClaims', 'judgedClaims'],
  ['unsupportedDisplayedRate', 'unsupportedDisplayedClaims', 'displayedClaims'],
  ['refusalRate', 'refusedRuns', 'runs'],
  ['expectedRefusalRate', 'refusedUnanswerableRuns', 'unanswerableRuns'],
  ['falseRefusalRate', 'refusedAnswerableRuns', 'answerableRuns'],
]

function seededRandom(seed) {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6D2B79F5) >>> 0
    let value = Math.imul(state ^ state >>> 15, 1 | state)
    value ^= value + Math.imul(value ^ value >>> 7, 61 | value)
    return ((value ^ value >>> 14) >>> 0) / 4294967296
  }
}

function summarizeBootstrap(series, point) {
  const defined = series.filter(value => value !== null)
  const undefinedResamples = series.length - defined.length
  const reason = point === null ? 'point-undefined' : undefinedResamples ? 'undefined-resamples' : null
  defined.sort((left, right) => left - right)
  return {
    point,
    interval: reason ? null : {
      low: defined[Math.floor((defined.length - 1) * 0.025)],
      high: defined[Math.floor((defined.length - 1) * 0.975)],
    },
    validResamples: defined.length,
    undefinedResamples,
    reason,
  }
}

const bootstrapComparisons = [
  ['verified-direct', 'verified', 'direct'],
  ['verified-rag', 'verified', 'rag'],
  ['rag-direct', 'rag', 'direct'],
]

export function pairedQuestionBootstrap(records, frozenQuestions, options = {}) {
  required(frozenQuestions !== undefined, 'bootstrap必须传入冻结题目清单')
  const point = evaluate(records, frozenQuestions)
  required(options && typeof options === 'object' && !Array.isArray(options), 'bootstrap选项必须为对象')
  const iterations = options.iterations === undefined ? 2000 : options.iterations
  const seed = options.seed === undefined ? 1 : options.seed
  required(Number.isSafeInteger(iterations) && iterations >= 100 && iterations <= 100000, 'bootstrap迭代次数必须为100至100000的整数')
  required(Number.isSafeInteger(seed) && seed >= 0 && seed <= 0xFFFFFFFF, 'bootstrap seed必须为非负32位整数')
  const questionIds = [...new Set(records.map(record => record.questionId))].sort()
  required(questionIds.length >= 2, 'bootstrap至少需要2道问题')
  const pointByGroup = Object.fromEntries(point.map(result => [result.group, result]))
  const clusters = questionIds.map(questionId => Object.fromEntries(
    evaluate(records.filter(record => record.questionId === questionId)).map(result => [result.group, {
      ...result.metricCounts, runs: result.runs, unjudgedDisplayedClaims: result.unjudgedDisplayedClaims,
    }])
  ))
  const random = seededRandom(seed)
  const series = Object.fromEntries(groups.map(group => [group,
    Object.fromEntries(bootstrapMetrics.map(([metric]) => [metric, []]))]))
  const differences = Object.fromEntries(bootstrapMetrics.map(([metric]) => [metric,
    Object.fromEntries(bootstrapComparisons.map(([comparison]) => [comparison, []]))]))
  for (let iteration = 0; iteration < iterations; iteration++) {
    const sampled = Object.fromEntries(groups.map(group => [group, {}]))
    for (let draw = 0; draw < clusters.length; draw++) {
      const cluster = clusters[Math.floor(random() * clusters.length)]
      for (const group of groups) {
        for (const [key, count] of Object.entries(cluster[group])) {
          sampled[group][key] = (sampled[group][key] || 0) + count
        }
      }
    }
    for (const [metric, numerator, denominator] of bootstrapMetrics) {
      const values = Object.fromEntries(groups.map(group => {
        const counts = sampled[group]
        const value = metric === 'unsupportedDisplayedRate' && counts.unjudgedDisplayedClaims > 0
          ? null : ratio(counts[numerator], counts[denominator])
        series[group][metric].push(value)
        return [group, value]
      }))
      for (const [comparison, left, right] of bootstrapComparisons) {
        differences[metric][comparison].push(values[left] === null || values[right] === null
          ? null : values[left] - values[right])
      }
    }
  }
  return {
    method: 'paired-question-percentile-bootstrap', confidenceLevel: 0.95,
    percentileRule: 'sorted[floor((B-1)*p)]', randomGenerator: 'mulberry32-v1',
    seed, iterations, samplingUnit: 'questionId', questions: questionIds.length, point,
    groups: Object.fromEntries(groups.map(group => [group,
      Object.fromEntries(bootstrapMetrics.map(([metric]) => [metric,
        summarizeBootstrap(series[group][metric], pointByGroup[group][metric])]))])),
    differences: Object.fromEntries(bootstrapMetrics.map(([metric]) => [metric,
      Object.fromEntries(bootstrapComparisons.map(([comparison, left, right]) => [comparison,
        summarizeBootstrap(differences[metric][comparison],
          pointByGroup[left][metric] === null || pointByGroup[right][metric] === null
            ? null : pointByGroup[left][metric] - pointByGroup[right][metric])]))])),
  }
}
export function parseJsonl(bytes) {
  let text
  try {
    text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
  } catch {
    throw new Error('标注文件不是有效的 UTF-8 编码')
  }
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/)
  return lines.flatMap((line, index) => {
    if (!line.trim()) return []
    try { return [JSON.parse(line)] } catch { throw new Error('第 ' + (index + 1) + ' 行不是合法 JSON') }
  })
}

const entry = process.argv[1] ? pathToFileURL(process.argv[1]).href : ''
if (import.meta.url === entry) {
  const file = process.argv[2]
  if (!file) {
    console.error('用法: node scripts/legal-citation-evaluation.mjs <标注记录.jsonl> [冻结题目清单.jsonl] [--bootstrap=次数 --seed=种子]')
    process.exitCode = 2
  } else {
    try {
      const argumentsAfterFile = process.argv.slice(3)
      const manifest = argumentsAfterFile[0] && !argumentsAfterFile[0].startsWith('--') ? argumentsAfterFile.shift() : undefined
      const options = {}
      for (const argument of argumentsAfterFile) {
        const match = /^--(bootstrap|seed)=(0|[1-9]\d*)$/.exec(argument)
        required(match, '未知或非法统计参数：仅支持--bootstrap=次数和--seed=种子')
        required(!Object.hasOwn(options, match[1]), '统计参数不能重复')
        options[match[1]] = Number(match[2])
      }
      required(Object.hasOwn(options, 'bootstrap') === Object.hasOwn(options, 'seed'), 'bootstrap与seed必须同时指定')
      required(!Object.hasOwn(options, 'bootstrap') || manifest, 'bootstrap必须传入冻结题目清单')
      const frozenQuestions = manifest ? await readJsonl(manifest) : undefined
      const records = await readJsonl(file)
      const result = Object.hasOwn(options, 'bootstrap')
        ? pairedQuestionBootstrap(records, frozenQuestions, { iterations: options.bootstrap, seed: options.seed })
        : evaluate(records, frozenQuestions)
      console.log(JSON.stringify(result, null, 2))
    } catch (error) {
      console.error(error.message)
      process.exitCode = 1
    }
  }
}
