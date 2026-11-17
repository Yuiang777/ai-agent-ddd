const CLIENT_PATHS = new Set(['/', '/dashboard', '/chat', '/matters', '/law-library'])

export const staffNavigation = [
  { label: '业务工作台', items: [
    { label: '工作台', path: '/dashboard', icon: 'dashboard' },
    { label: '法律咨询', path: '/chat', icon: 'chat' },
    { label: '合同审查', path: '/contract-review', icon: 'document' },
    { label: '风险巡检', path: '/risk-inspection', icon: 'risk' },
    { label: '法律事项', path: '/matters', icon: 'matters' }
  ] },
  { label: '知识与资产', items: [
    { label: '企业知识库', path: '/knowledge-base', icon: 'library' },
    { label: '法规与判例', path: '/law-library', icon: 'book' },
    { label: '模板中心', path: '/templates', icon: 'document' }
  ] },
  { label: '协作与治理', items: [
    { label: '审批与审计', path: '/audit', icon: 'approval' },
    { label: '流程配置', path: '/agent-studio', icon: 'workflow' },
    { label: '系统设置', path: '/settings', icon: 'settings' }
  ] }
]

export const clientNavigation = [
  { label: '客户服务', items: [
    { label: '服务概览', path: '/dashboard', icon: 'dashboard' },
    { label: '法律事项', path: '/matters', icon: 'matters' },
    { label: '法律咨询', path: '/chat', icon: 'chat' },
    { label: '法规与判例', path: '/law-library', icon: 'book' }
  ] }
]

export function isClientAudience(role) {
  return role !== 'LAWYER' && role !== 'PARALEGAL' && role !== 'SYSTEM'
}

export function navigationForRole(role) {
  return isClientAudience(role) ? clientNavigation : staffNavigation
}

export function canAccessWorkspacePath(role, pathname) {
  return !isClientAudience(role) || CLIENT_PATHS.has(pathname)
}
