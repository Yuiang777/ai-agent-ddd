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
  return ['SUCCESS', 'REVIEW_REQUIRED'].includes(result.status) ? result.finalOutput : ''
}
