const riskLevels = {
  HIGH: { label: '高风险', color: 'error' },
  MEDIUM: { label: '中风险', color: 'warning' },
  LOW: { label: '低风险', color: 'success' }
}

export function riskPresentation(level) {
  return typeof level === 'string' && Object.hasOwn(riskLevels, level)
    ? riskLevels[level]
    : { label: '等级待确认', color: 'default' }
}
