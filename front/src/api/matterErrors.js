const messages = {
  DEADLINE_UPDATE_CONFLICT: '该期限编号已存在，内容或核验状态与本次登记不一致，请刷新核对原记录。',
  DEADLINE_NOT_PROPOSED: '此期限已完成核验或状态已变化，请刷新事项查看当前记录。',
  DEADLINE_SOURCE_REQUIRED: '缺少期限的权威依据，请补齐来源后再核验。',
  LAWYER_DEADLINE_VERIFICATION_REQUIRED: '期限核验需要律师身份，请交由律师处理。',
  MATTER_VERSION_CONFLICT: '事项已被其他操作更新，请刷新后核对最新内容再提交。',
  REVIEW_ARTIFACT_STALE: '报告或材料已更新，此审核已失效，请刷新并提交当前版本复核。',
  REVIEW_STAGE_REQUIRED: '事项办理阶段已变化，请刷新后查看当前待办。',
  ARTIFACT_AUTHOR_CANNOT_REVIEW: '报告作者不能复核或签署自己的报告，请交由其他同事处理。',
  QUALITY_REVIEWER_CANNOT_SIGNOFF: '质量复核人不能签署同一报告，请交由另一位律师处理。',
  REVIEW_ASSIGNEE_MISMATCH: '此审核已由其他同事领取，请刷新查看处理人。',
  SIGNED_MATTER_DEADLINES_FROZEN: '事项已签署或归档，当前不能修改期限。',
  SIGNED_MATTER_EVIDENCE_FROZEN: '事项已签署或归档，当前不能修改材料。'
}
export function matterErrorMessage(error) {
  const candidates = [error?.response?.data?.message, error?.message]
  const message = candidates.find(value => typeof value === 'string' && value.trim())
  if (!message) return '连接失败，请稍后重试'
  return Object.hasOwn(messages, message) ? messages[message] : message
}
