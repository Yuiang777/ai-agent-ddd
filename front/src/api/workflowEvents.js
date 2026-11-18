export function summarizeWorkflowEvent(eventName, payload, nodeIds, eventNames) {
  const nodeId = payload?.nodeId
  const costMs = payload?.data?.costMs
  return {
    event: eventNames.includes(eventName) ? eventName : 'UNKNOWN',
    nodeId: nodeIds.includes(nodeId) ? nodeId : '',
    costMs: typeof costMs === 'number' && Number.isFinite(costMs) && costMs >= 0 ? costMs : null
  }
}
