import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import { parseJsonl } from './legal-citation-evaluation.mjs'
import { checkRounds } from './check-evaluation-rounds.mjs'

export function summarizeLatency(records, questions, rounds) {
  const coverage = checkRounds(records, questions, rounds)
  const groups = ['direct', 'rag', 'verified'].map(group => {
    const runs = records.filter(record => record.group === group)
    const times = []
    for (const { elapsedMs } of runs) {
      if (elapsedMs == null) continue
      if (!Number.isSafeInteger(elapsedMs) || elapsedMs < 0) throw new Error(group + ' 组 elapsedMs 必须为非负整数毫秒或 null')
      times.push(elapsedMs)
    }
    times.sort((left, right) => left - right)
    const missing = runs.length - times.length
    const percentile = fraction => missing ? null : times[Math.ceil(times.length * fraction) - 1]
    return { group, measured: times.length, missing, p50Ms: percentile(0.50), p95Ms: percentile(0.95) }
  })
  return { coverage, groups }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [, , records, questions, rounds, manifestFlag] = process.argv
    if (![5, 6].includes(process.argv.length) || !/^(?:[1-9][0-9]?|100)$/.test(rounds)) {
      throw new Error('用法: node scripts/summarize-evaluation-latency.mjs 标注记录.jsonl 冻结题目.jsonl 预登记轮数（1–100） [--manifest-sha256=预登记清单散列]')
    }
    const manifestMatch = manifestFlag === undefined ? undefined : /^--manifest-sha256=([0-9a-fA-F]{64})$/.exec(manifestFlag)
    if (manifestMatch === null) throw new Error('须使用 --manifest-sha256 指定 64 位十六进制散列')
    const expectedManifestSha256 = manifestMatch?.[1].toLowerCase()
    const [recordBytes, questionBytes] = await Promise.all([readFile(records), readFile(questions)])
    const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')
    const questionSha256 = sha256(questionBytes)
    if (expectedManifestSha256 !== undefined && expectedManifestSha256 !== questionSha256) {
      throw new Error('冻结题目清单与预登记 SHA-256 不一致')
    }
    const result = summarizeLatency(parseJsonl(recordBytes), parseJsonl(questionBytes), Number(rounds))
    console.log(JSON.stringify({
      ...result,
      inputSha256: { records: sha256(recordBytes), frozenQuestions: questionSha256 },
      ...(expectedManifestSha256 === undefined ? {} : { expectedManifestSha256 }),
    }))
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
