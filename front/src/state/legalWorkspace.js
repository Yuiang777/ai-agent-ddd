const MATTER_KEY = 'lexicounsel_legal_matters_v1'
const REVIEW_KEY = 'lexicounsel_review_queue_v1'

const seedMatters = [
  {
    id: 'MAT-2026-0812-01',
    title: '华东区渠道合作协议续签审查',
    client: '集团销售中心',
    type: '合同审查',
    owner: '陈律师',
    priority: 'HIGH',
    status: 'REVIEWING',
    deadline: '2026-08-13',
    nextAction: '确认最低采购量与单方解约责任',
    updatedAt: '2026-08-12 13:40'
  },
  {
    id: 'MAT-2026-0811-03',
    title: '新供应商准入合规核查',
    client: '供应链管理部',
    type: '合规风控',
    owner: '刘法务',
    priority: 'MEDIUM',
    status: 'MATERIAL_PENDING',
    deadline: '2026-08-15',
    nextAction: '补充实际控制人及制裁筛查材料',
    updatedAt: '2026-08-12 10:15'
  },
  {
    id: 'MAT-2026-0809-02',
    title: '劳动争议仲裁答辩准备',
    client: '人力资源部',
    type: '诉讼仲裁',
    representedPartyName: '集团华东分公司',
    partyRole: 'RESPONDENT',
    proceedingType: 'LABOR_ARBITRATION',
    proceedingStage: 'FIRST_INSTANCE',
    courtOrTribunal: '上海市浦东新区劳动人事争议仲裁委员会',
    opponentNames: '张某',
    conflictCheckCompleted: true,
    engagementAuthorized: true,
    conflictCheckReference: 'COI-2026-0811-034',
    engagementReference: 'ENG-2026-HR-019',
    caseNumber: '沪浦劳人仲(2026)示例号',
    nextDeadline: '2026-08-14',
    deadlineSource: '仲裁委送达的开庭通知书，2026-08-11 收悉',
    owner: '王律师',
    priority: 'HIGH',
    status: 'REVIEW_REQUIRED',
    deadline: '2026-08-14',
    nextAction: '复核解除通知送达证据链',
    updatedAt: '2026-08-11 18:20'
  },
  {
    id: 'MAT-2026-0805-07',
    title: '软件采购框架合同定稿',
    client: '信息技术部',
    type: '合同审查',
    owner: '赵法务',
    priority: 'LOW',
    status: 'COMPLETED',
    deadline: '2026-08-10',
    nextAction: '归档签署版与审批记录',
    updatedAt: '2026-08-10 16:05'
  }
]

function read(key, fallback) {
  try {
    const parsed = JSON.parse(localStorage.getItem(key) || '')
    return Array.isArray(parsed) ? parsed : fallback
  } catch {
    return fallback
  }
}

function write(key, value) {
  localStorage.setItem(key, JSON.stringify(value))
  window.dispatchEvent(new CustomEvent('legal-workspace-updated', { detail: { key } }))
  return value
}

export function loadMatters() {
  const matters = read(MATTER_KEY, [])
  if (matters.length) {
    const normalized = matters.map(item => item.type === '诉讼仲裁' ? {
      representedPartyName: item.client || '', partyRole: '', proceedingType: '', proceedingStage: '',
      courtOrTribunal: '', opponentNames: '', conflictCheckCompleted: false, engagementAuthorized: false,
      conflictCheckReference: '', engagementReference: '', caseNumber: '', nextDeadline: item.deadline || '', deadlineSource: '', ...item
    } : item)
    if (JSON.stringify(normalized) !== JSON.stringify(matters)) write(MATTER_KEY, normalized)
    return normalized
  }
  return write(MATTER_KEY, seedMatters)
}

export function saveMatter(id, input) {
  return updateMatter(id, {
    title: input.title.trim(), client: input.client.trim(), type: input.type, owner: input.owner.trim(),
    priority: input.priority, deadline: input.deadline, nextAction: input.nextAction.trim(),
    representedPartyName: input.representedPartyName?.trim() || input.client.trim(),
    partyRole: input.partyRole || '', proceedingType: input.proceedingType || '', proceedingStage: input.proceedingStage || '',
    courtOrTribunal: input.courtOrTribunal?.trim() || '', opponentNames: input.opponentNames?.trim() || '',
    conflictCheckCompleted: Boolean(input.conflictCheckCompleted), engagementAuthorized: Boolean(input.engagementAuthorized),
    conflictCheckReference: input.conflictCheckReference?.trim() || '', engagementReference: input.engagementReference?.trim() || '',
    caseNumber: input.caseNumber?.trim() || '', nextDeadline: input.nextDeadline || input.deadline,
    deadlineSource: input.deadlineSource?.trim() || ''
  })
}

export function createMatter(input) {
  const current = loadMatters()
  const now = new Date()
  const matter = {
    id: `MAT-${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}-${String(current.length + 1).padStart(2, '0')}`,
    title: input.title.trim(),
    client: input.client.trim(),
    type: input.type,
    owner: input.owner.trim(),
    priority: input.priority,
    status: 'INTAKE',
    deadline: input.deadline,
    nextAction: input.nextAction.trim() || '完成材料收集与冲突检查',
    representedPartyName: input.representedPartyName?.trim() || input.client.trim(),
    partyRole: input.partyRole || '',
    proceedingType: input.proceedingType || '',
    proceedingStage: input.proceedingStage || '',
    courtOrTribunal: input.courtOrTribunal?.trim() || '',
    opponentNames: input.opponentNames?.trim() || '',
    conflictCheckCompleted: Boolean(input.conflictCheckCompleted),
    engagementAuthorized: Boolean(input.engagementAuthorized),
    conflictCheckReference: input.conflictCheckReference?.trim() || '',
    engagementReference: input.engagementReference?.trim() || '',
    caseNumber: input.caseNumber?.trim() || '',
    nextDeadline: input.nextDeadline || input.deadline,
    deadlineSource: input.deadlineSource?.trim() || '',
    updatedAt: now.toLocaleString('zh-CN', { hour12: false }).replaceAll('/', '-')
  }
  write(MATTER_KEY, [matter, ...current])
  return matter
}

export function updateMatter(id, changes) {
  const next = loadMatters().map(item => item.id === id
    ? { ...item, ...changes, updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }).replaceAll('/', '-') }
    : item)
  write(MATTER_KEY, next)
  return next.find(item => item.id === id)
}

export function loadReviews() {
  return read(REVIEW_KEY, [])
}

export function enqueueReview(review) {
  const current = loadReviews()
  const existing = current.find(item => item.runId === review.runId)
  if (existing) {
    return write(REVIEW_KEY, current.map(item => item.runId === review.runId
      ? { ...item, ...review, status: item.status, createdAt: item.createdAt }
      : item))
  }
  return write(REVIEW_KEY, [{ ...review, status: 'PENDING', createdAt: new Date().toISOString() }, ...current])
}

export function completeReview(runId, reviewer, opinion, decision = 'COMPLETED') {
  const status = decision === 'RETURNED' ? 'RETURNED' : decision === 'SIGNED_OFF' ? 'SIGNED_OFF' : 'COMPLETED'
  return write(REVIEW_KEY, loadReviews().map(item => item.runId === runId
    ? { ...item, status, decision, reviewer, opinion, reviewedAt: new Date().toISOString() }
    : item))
}

export const matterStatus = {
  INTAKE: { label: '待受理', color: 'default' },
  MATERIAL_PENDING: { label: '待补材料', color: 'warning' },
  REVIEWING: { label: '审查中', color: 'info' },
  REVIEW_REQUIRED: { label: '待人工复核', color: 'error' },
  SIGNOFF_REQUIRED: { label: '待律师签发', color: 'warning' },
  COMPLETED: { label: '已完成', color: 'success' }
}

// Keep the UI mapping compatible with the server-side canonical lifecycle.
Object.assign(matterStatus, {
  CONFLICT_CHECK: { label: 'Conflict check', color: 'info' },
  ENGAGEMENT_PENDING: { label: 'Engagement pending', color: 'warning' },
  ANALYSIS: { label: 'Legal analysis', color: 'info' },
  SIGNED_OFF: { label: 'Lawyer signed off', color: 'success' },
  CLOSED: { label: 'Closed', color: 'success' },
  REJECTED: { label: 'Rejected', color: 'error' }
})

export const priorityMeta = {
  HIGH: { label: '高', color: 'error' },
  MEDIUM: { label: '中', color: 'warning' },
  LOW: { label: '低', color: 'success' }
}
