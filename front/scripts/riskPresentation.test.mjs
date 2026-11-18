import test from 'node:test'
import assert from 'node:assert/strict'
import { riskPresentation } from '../src/api/riskPresentation.js'

test('known risk levels retain their explicit labels and colors', () => {
  assert.deepEqual(riskPresentation('HIGH'), { label: '高风险', color: 'error' })
  assert.deepEqual(riskPresentation('MEDIUM'), { label: '中风险', color: 'warning' })
  assert.deepEqual(riskPresentation('LOW'), { label: '低风险', color: 'success' })
})

test('missing and unrecognized risk levels remain unconfirmed', () => {
  for (const level of [undefined, null, '', 'UNKNOWN', 'high', ' LOW ', '__proto__', 'constructor', {}, [], 0]) {
    assert.deepEqual(riskPresentation(level), { label: '等级待确认', color: 'default' })
  }
})
