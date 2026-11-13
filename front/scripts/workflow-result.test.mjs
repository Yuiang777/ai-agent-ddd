import test from 'node:test'
import assert from 'node:assert/strict'
import { finalizedWorkflowReport, validateWorkflowResult } from '../src/api/workflowResult.js'

test('only completed and review-required workflows expose a final report', () => {
  for (const status of ['SUCCESS', 'REVIEW_REQUIRED']) {
    const result = validateWorkflowResult({ status, finalOutput: '待律师复核的候选报告' })
    assert.equal(finalizedWorkflowReport(result), '待律师复核的候选报告')
  }
  for (const status of ['BLOCKED', 'FAILED']) {
    const result = validateWorkflowResult({ status, finalOutput: '不应展示的草稿' })
    assert.equal(finalizedWorkflowReport(result), '')
  }
})

test('missing terminal status or report body fails closed', () => {
  assert.throws(() => validateWorkflowResult({ finalOutput: '未核验内容' }), /有效结束状态/)
  assert.throws(() => validateWorkflowResult({ status: 'SUCCESS', finalOutput: '' }), /完整报告正文/)
})
