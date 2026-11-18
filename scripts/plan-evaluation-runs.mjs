import fs from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { pathToFileURL } from 'node:url'
import { parseJsonl } from './legal-citation-evaluation.mjs'

const groups = ['direct', 'rag', 'verified']
const hash = value => createHash('sha256').update(value).digest('hex')

export async function planRuns(file, rounds, seed) {
  if (!Number.isSafeInteger(rounds) || rounds < 1 || rounds > 100 ||
      typeof seed !== 'string' || !seed.trim()) {
    throw new Error('轮次数须为 1–100 的整数，随机种子不能为空')
  }
  const manifestBytes = await fs.readFile(file)
  const questions = parseJsonl(manifestBytes)
  if (!questions.length) throw new Error('题目清单不能为空')
  const seen = new Set()
  for (const [index, question] of questions.entries()) {
    const at = `第 ${index + 1} 道题`
    if (!question || typeof question !== 'object' || Array.isArray(question) ||
        typeof question.questionId !== 'string' || !question.questionId.trim() ||
        question.questionId.trim() !== question.questionId || /[\r\n]/.test(question.questionId)) {
      throw new Error(at + '缺少有效 questionId')
    }
    const date = question.lawAsOf
    if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date) ||
        Number.isNaN(Date.parse(date)) || new Date(date).toISOString().slice(0, 10) !== date) {
      throw new Error(at + '缺少有效 lawAsOf')
    }
    if (seen.has(question.questionId)) throw new Error(at + '问题 ID 重复')
    seen.add(question.questionId)
  }
  const manifestSha256 = hash(manifestBytes)
  return questions.flatMap(({ questionId, lawAsOf }) =>
    Array.from({ length: rounds }, (_, index) => {
      const run = index + 1
      const order = [...groups].sort((left, right) => {
        const leftKey = hash(JSON.stringify([seed, questionId, run, left]))
        const rightKey = hash(JSON.stringify([seed, questionId, run, right]))
        return leftKey < rightKey ? -1 : leftKey > rightKey ? 1 : 0
      })
      return { questionId, lawAsOf, run, order, manifestSha256 }
    }))
}

const entry = process.argv[1] ? pathToFileURL(process.argv[1]).href : ''
if (import.meta.url === entry) {
  const [, , file, rounds, seed] = process.argv
  if (!file || !rounds || !seed) {
    console.error('用法: node scripts/plan-evaluation-runs.mjs <题目清单.jsonl> <轮次数> <随机种子>')
    process.exitCode = 2
  } else {
    try {
      if (!/^[1-9]\d*$/.test(rounds)) throw new Error('轮次数须为正整数')
      const result = await planRuns(file, Number(rounds), seed)
      console.log(result.map(JSON.stringify).join('\n'))
    } catch (error) {
      console.error(error.message)
      process.exitCode = 1
    }
  }
}
