import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000
})

async function get(path) {
  try {
    const res = await api.get(path)
    if (res?.data?.success) return res.data.data
    return res.data
  } catch {
    return []
  }
}

export async function fetchAgents() {
  return get('/config/agents')
}

export async function fetchClients() {
  return get('/config/clients')
}

export async function fetchModels() {
  return get('/config/models')
}

export async function fetchMcpTools() {
  return get('/config/mcp-tools')
}

export async function fetchPrompts() {
  return get('/config/prompts')
}

export async function fetchAdvisors() {
  return get('/config/advisors')
}

export async function fetchRagOrders() {
  return get('/config/rag-orders')
}

export default api
