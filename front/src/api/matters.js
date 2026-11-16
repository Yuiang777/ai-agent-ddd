import api from './client'
import { matterErrorMessage } from './matterErrors'

export function unwrap(response) {
 const body = response?.data
 if (body?.success === false) throw new Error(body.message || '操作未完成，请重试')
 if (body?.success === true) return body.data
 throw new Error('服务返回格式异常，请刷新后重试')
}
export async function listMatters() { return unwrap(await api.get('/legal/matters')) }
export async function getMatter(id) { return unwrap(await api.get(`/legal/matters/${encodeURIComponent(id)}`)) }
export async function getTimeline(id) { return unwrap(await api.get(`/legal/matters/${encodeURIComponent(id)}/timeline`)) }
export async function createMatter(payload) { return unwrap(await api.post('/legal/matters', payload)) }
export async function matterCommand(id, path, payload) {
  return unwrap(await api.post(`/legal/matters/${encodeURIComponent(id)}/${path}`, payload))
}
export const errorMessage = matterErrorMessage
export const statusLabels = { INTAKE: '待受理', CONFLICT_CHECK: '冲突检查', ENGAGEMENT_PENDING: '待委托确认', MATERIAL_PENDING: '待补充材料', ANALYSIS: '分析中', REVIEW_REQUIRED: '待质量复核', SIGNOFF_REQUIRED: '待律师签署', SIGNED_OFF: '已签署', CLOSED: '已归档', REJECTED: '已拒绝' }
export const businessLabels = { GENERAL_ADVISORY: '综合法律咨询', DISPUTE_RESOLUTION: '诉讼与争议解决', CONTRACT: '合同与交易', CORPORATE: '公司与并购', EMPLOYMENT: '劳动与雇佣', INTELLECTUAL_PROPERTY: '知识产权', DATA_PRIVACY: '数据与隐私', COMPETITION: '竞争与反垄断', TAX: '税务', FINANCE: '金融与资本市场', REAL_ESTATE: '房地产与建设工程', FAMILY_SUCCESSION: '家事与继承', IMMIGRATION: '移民', CRIMINAL_ASSISTANCE: '刑事辩护辅助', COMPLIANCE: '合规', OTHER: '其他业务', UNSPECIFIED: '待分类' }
export function matterData(view) {
 const matter = view?.matter || {}
 return { ...matter, scope: view?.scope || {}, reviewTasks: view?.reviewTasks || [], evidence: view?.evidence || [], deadlines: view?.deadlines || [] }
}
export function formatTime(value) {
 if (!value) return '未记录'
 const date = new Date(value)
 return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

export async function uploadMatterDocument(matterId, file, onProgress) {
  const form = new FormData()
  form.append('file', file)
  form.append('corpusType', 'MATTER_PRIVATE')
  form.append('matterId', matterId)
  const result = unwrap(await api.post('/legal/documents/upload', form, {
    onUploadProgress: event => onProgress?.(event.total ? Math.round(event.loaded * 100 / event.total) : null)
  }))
  if (result?.status !== 'STORED' || !result.documentId || !result.objectUri || !/^[a-f0-9]{64}$/i.test(result.versionHash || '') || result.scope?.matterId !== matterId || result.scope?.corpusType !== 'MATTER_PRIVATE') {
    throw new Error('上传结果不完整或事项范围不符，请先核对存储记录再重试')
  }
  return result
}

export async function getDocumentUploadCapabilities() {
  const data = unwrap(await api.get('/legal/documents/upload-capabilities'))
  if (typeof data?.enabled !== 'boolean' || typeof data.reason !== 'string' || !Number.isSafeInteger(data.maxFileBytes) || data.maxFileBytes < 0 || (data.enabled && data.maxFileBytes === 0) || !Array.isArray(data.allowedExtensions) || data.allowedExtensions.some(extension => typeof extension !== 'string' || !/^[a-z0-9]+$/i.test(extension)) || (data.enabled && !data.allowedExtensions.length)) throw new Error('上传能力信息异常，请重试')
  return data
}

export async function getMatterContext() {
  const data = unwrap(await api.get('/legal/matters/context'))
  if (!data?.role) throw new Error('身份上下文不完整')
  return data
}
