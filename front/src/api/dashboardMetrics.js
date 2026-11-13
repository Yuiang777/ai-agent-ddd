export function workflowRunCount(usage) {
  return usage?.totals ? (usage.totals.WORKFLOW_RUNS ?? 0) : null
}
