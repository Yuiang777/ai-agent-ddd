import test from 'node:test'
import assert from 'node:assert/strict'
import { displayableCitations, displayableItems, displayableReviewReasons, finalizedWorkflowReport, isPublishableWorkflowResult, validateWorkflowResult } from '../src/api/workflowResult.js'

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

test('only terminal publishable statuses expose derived result sections', () => {
  for (const status of ['SUCCESS', 'REVIEW_REQUIRED']) {
    assert.equal(isPublishableWorkflowResult({ status, finalOutput: '候选报告' }), true)
  }
  for (const status of ['BLOCKED', 'FAILED', 'RUNNING', undefined]) {
    assert.equal(isPublishableWorkflowResult({ status, finalOutput: '候选报告' }), false)
  }
  assert.equal(isPublishableWorkflowResult(null), false)
})

test('publishable results reject whitespace and non-string report bodies', () => {
  for (const status of ['SUCCESS', 'REVIEW_REQUIRED']) {
    for (const finalOutput of ['', '  \n\t', null, undefined, {}, [], 0, true]) {
      assert.throws(() => validateWorkflowResult({ status, finalOutput }))
      assert.equal(isPublishableWorkflowResult({ status, finalOutput }), false)
      assert.equal(finalizedWorkflowReport({ status, finalOutput }), '')
    }
  }
})

test('report extraction fails closed when called without prior validation', () => {
  assert.equal(finalizedWorkflowReport({ status: 'SUCCESS' }), '')
  assert.equal(finalizedWorkflowReport({ status: 'REVIEW_REQUIRED', finalOutput: null }), '')
  assert.equal(finalizedWorkflowReport({ status: 'BLOCKED', finalOutput: '不应展示' }), '')
})

test('malformed structured items cannot be rendered as citations or risks', () => {
  assert.deepEqual(displayableItems([null, 1, {}, { title: { fake: 'citation' } }, { title: '  ' }], ['title', 'excerpt']), [])
  assert.deepEqual(displayableItems([{ title: '候选引用', excerpt: { injected: true }, source: '出处' }], ['title', 'excerpt', 'source']),
    [{ title: '候选引用', excerpt: '', source: '出处' }])
})

test('candidate citations require traceable source and quoted excerpt', () => {
  assert.deepEqual(displayableCitations([{ title: '空摘录', source: 'source', excerpt: '  ' },
    { title: '空来源', source: '', excerpt: '引用原文' },
    { title: '合法条目', source: 'source', excerpt: '引用原文' }]),
  [{ title: '合法条目', source: 'source', articleNo: '', clauseNo: '', excerpt: '引用原文' }])
})

test('review reasons ignore malformed entries rather than emitting object placeholders', () => {
  assert.deepEqual(displayableReviewReasons([{}, null, '需要律师复核', '', 3]), ['需要律师复核'])
  assert.deepEqual(displayableReviewReasons({ untrusted: true }), [])
})
