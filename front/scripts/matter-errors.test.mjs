import test from 'node:test'
import assert from 'node:assert/strict'
import { matterErrorMessage } from '../src/api/matterErrors.js'
test('deadline conflict explains refresh and retains unknown diagnostics', () => {
  assert.match(matterErrorMessage(new Error('DEADLINE_NOT_PROPOSED')), /刷新/)
  assert.match(matterErrorMessage({ response: { data: { message: 'LAWYER_DEADLINE_VERIFICATION_REQUIRED' } } }), /律师/)
  assert.match(matterErrorMessage(new Error('REVIEW_ARTIFACT_STALE')), /当前版本/)
  assert.equal(matterErrorMessage(new Error('NEW_SERVER_DIAGNOSTIC')), 'NEW_SERVER_DIAGNOSTIC')
  assert.equal(matterErrorMessage(null), '连接失败，请稍后重试')
})

test('untrusted error values always produce displayable strings', () => {
  for (const message of ['__proto__', 'constructor', 'toString']) {
    assert.equal(matterErrorMessage({ message }), message)
  }
  assert.equal(matterErrorMessage({ response: { data: { message: { bad: true } } }, message: 'fallback' }), 'fallback')
  assert.equal(matterErrorMessage({ message: 123 }), '连接失败，请稍后重试')
  assert.match(matterErrorMessage(new Error('DEADLINE_UPDATE_CONFLICT')), /核对原记录/)
})
