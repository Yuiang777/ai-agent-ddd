/** Fail closed: partial or malformed workflow responses are never publishable reports. */
export function validateWorkflowResult(result) {
  if (!result || !['SUCCESS', 'REVIEW_REQUIRED', 'BLOCKED', 'FAILED'].includes(result.status)) {
    throw new Error('工作流未返回有效结束状态，请核对服务响应后重新执行')
  }
  if (['SUCCESS', 'REVIEW_REQUIRED'].includes(result.status) && (typeof result.finalOutput !== 'string' || !result.finalOutput.trim())) {
    throw new Error('工作流未返回完整报告正文，本次结果不能登记')
  }
  return result
}

export function finalizedWorkflowReport(result) {
  return isPublishableWorkflowResult(result) && typeof result.finalOutput === 'string'
    ? result.finalOutput
    : ''
}

export function isPublishableWorkflowResult(result) {
  return ['SUCCESS', 'REVIEW_REQUIRED'].includes(result?.status)
    && typeof result.finalOutput === 'string'
    && Boolean(result.finalOutput.trim())
}

export function displayableItems(items, fields) {
  if (!Array.isArray(items)) return []
  return items.filter(item => item && typeof item === 'object' && !Array.isArray(item) && typeof item.title === 'string' && item.title.trim())
    .map(item => Object.fromEntries(fields.map(field => [field, typeof item[field] === 'string' ? item[field] : ''])))
}
export function displayableCitations(items) {
  return displayableItems(items, ['title', 'source', 'articleNo', 'clauseNo', 'excerpt'])
    .filter(item => item.source.trim() && item.excerpt.trim())
}
export function displayableReviewReasons(reasons) {
  return Array.isArray(reasons) ? reasons.filter(reason => typeof reason === 'string' && reason.trim()) : []
}
