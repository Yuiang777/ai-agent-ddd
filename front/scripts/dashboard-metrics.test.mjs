import test from 'node:test'
import assert from 'node:assert/strict'
import { validatedMatterItems, workflowRunCount } from '../src/api/dashboardMetrics.js'

test('workflow usage reads the backend WORKFLOW_RUNS metric', () => {
  assert.equal(workflowRunCount({ totals: { WORKFLOW_RUNS: 7 } }), 7)
  assert.equal(workflowRunCount({ totals: {} }), 0)
})

test('unavailable workflow usage remains unknown rather than zero', () => {
  assert.equal(workflowRunCount(null), null)
  assert.equal(workflowRunCount({}), null)
})

test('malformed matter lists never become a misleading empty dashboard', () => {
  const items = [{ matter: { id: 'matter-1' } }]
  assert.equal(validatedMatterItems(items), items)
  assert.deepEqual(validatedMatterItems([]), [])
  for (const invalid of [null, {}, { matters: [] }, [null], [{}], [{ matter: {} }], [{ matter: { id: '  ' } }]]) {
    assert.equal(validatedMatterItems(invalid), null)
  }
})
