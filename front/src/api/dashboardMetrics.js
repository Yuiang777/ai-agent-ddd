export function workflowRunCount(usage) {
  return usage?.totals ? (usage.totals.WORKFLOW_RUNS ?? 0) : null
}

export function validatedMatterItems(items) {
  if (!Array.isArray(items)) return null
  return items.every(item => {
    const matter = item?.matter || item
    return matter && typeof matter === 'object' && !Array.isArray(matter)
      && typeof matter.id === 'string' && matter.id.trim().length > 0
  }) ? items : null
}
