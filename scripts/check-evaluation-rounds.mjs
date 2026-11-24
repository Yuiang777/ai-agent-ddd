import { pathToFileURL } from 'node:url'
import { evaluate, readJsonl, validateFrozenQuestions } from './legal-citation-evaluation.mjs'

export function checkRounds(records, questions, rounds) {
  if (!Number.isSafeInteger(rounds) || rounds < 1 || rounds > 100) throw new Error('计划轮数须为 1–100 的整数')
  validateFrozenQuestions(questions)
  evaluate(records, questions)
  if (records.some(record => record.run > rounds) || records.length !== questions.length * rounds * 3) {
    throw new Error('标注记录与预定轮数不一致')
  }
  return { questions: questions.length, roundsPerQuestion: rounds, records: records.length }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [, , records, questions, rounds] = process.argv
    if (process.argv.length !== 5 || !/^[1-9]\d*$/.test(rounds)) {
      throw new Error('用法: node scripts/check-evaluation-rounds.mjs 标注记录.jsonl 题目清单.jsonl 预登记轮数')
    }
    console.log(JSON.stringify(checkRounds(await readJsonl(records), await readJsonl(questions), Number(rounds))))
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
