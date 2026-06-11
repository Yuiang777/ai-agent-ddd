import axios from 'axios'

export const adminToken = import.meta.env.VITE_ADMIN_API_TOKEN || 'dev-token'

export function adminHeaders(headers = {}) {
  return adminToken ? { ...headers, 'X-Admin-Token': adminToken } : headers
}

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: Number(import.meta.env.VITE_API_TIMEOUT || 120000)
})

api.interceptors.request.use(config => {
  if (adminToken) {
    config.headers = config.headers || {}
    config.headers['X-Admin-Token'] = adminToken
  }
  return config
})

async function get(path) {
  const res = await api.get(path)
  if (res?.data?.success) return res.data.data
  if (res?.data?.message) throw new Error(res.data.message)
  return res.data
}

export async function fetchGovernanceOverview() {
  return get('/governance/overview')
}

export async function fetchGovernanceEvents() {
  return get('/governance/events')
}

export async function fetchGovernanceHealth() {
  return get('/governance/health')
}

export async function callToolAgent(payload) {
  const res = await api.post('/agent/tool-call', payload)
  if (res?.data?.success) return res.data.data
  throw new Error(res?.data?.message || '工具 Agent 调用失败')
}

export async function fetchMultimodalTasks() {
  return get('/multimodal/tasks')
}

export async function fetchMultimodalCapabilities() {
  return get('/multimodal/capabilities')
}

export async function callMultimodal(payload) {
  const form = new FormData()
  form.append('clientId', payload.clientId)
  form.append('taskType', payload.taskType)
  if (payload.prompt) form.append('prompt', payload.prompt)
  if (payload.file) form.append('file', payload.file)
  const res = await api.post('/multimodal', form, { headers: { 'Content-Type': 'multipart/form-data' } })
  if (res?.data?.success) return res.data.data
  throw new Error(res?.data?.message || '多模态 Agent 调用失败')
}

export default api
