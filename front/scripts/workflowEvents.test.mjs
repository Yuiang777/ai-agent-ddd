import test from 'node:test'
import assert from 'node:assert/strict'
import { summarizeWorkflowEvent } from '../src/api/workflowEvents.js'

test('event history retains only allowlisted event, node and finite duration', () => {
  const payload = { nodeId: 'retrieval', nodeType: 'private-file', message: 'private case text', data: { costMs: 12, report: 'private report' }, raw: 'private source' }
  const summary = summarizeWorkflowEvent('NODE_COMPLETED', payload, ['retrieval'], ['NODE_COMPLETED'])
  assert.deepEqual(summary, { event: 'NODE_COMPLETED', nodeId: 'retrieval', costMs: 12 })
  assert.ok(!JSON.stringify(summary).includes('private'))
})

test('unexpected server labels and malformed duration cannot enter event history', () => {
  assert.deepEqual(summarizeWorkflowEvent('private event', { nodeId: 'private node', data: { costMs: Infinity } }, ['retrieval'], ['RUN']),
    { event: 'UNKNOWN', nodeId: '', costMs: null })
  assert.deepEqual(summarizeWorkflowEvent('RUN', null, ['retrieval'], ['RUN']),
    { event: 'RUN', nodeId: '', costMs: null })
})
