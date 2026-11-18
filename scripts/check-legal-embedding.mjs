import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { pathToFileURL } from 'node:url'

const execute = promisify(execFile)

export async function checkLegalEmbedding(settings, dependencies = {}) {
  const fetchResponse = dependencies.fetch ?? fetch
  const executeCommand = dependencies.execute ?? execute
  const table = settings.table ?? 'vector_store_openai'
  const container = settings.container ?? 'vector-db-agent'
  const database = settings.database ?? 'springai'
  const username = settings.username ?? 'postgres'
  const dimensions = settings.dimensions
  if (dimensions !== undefined && (!Number.isSafeInteger(dimensions) || dimensions < 1)) {
    throw new Error('EMBEDDING_DIMENSIONS must be a positive safe integer when provided')
  }
  if (!/^[a-z_][a-z0-9_]*$/.test(table) ||
      !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(container) ||
      !/^[a-zA-Z_][a-zA-Z0-9_-]*$/.test(database) ||
      !/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(username)) {
    throw new Error('Invalid database or container identifier')
  }
  if (typeof settings.model !== 'string' || !settings.model.trim()) {
    throw new Error('EMBEDDING_MODEL is required; use the actual configured embedding model')
  }
  let endpoint
  try {
    endpoint = new URL(settings.baseUrl ?? 'http://127.0.0.1:11434/v1')
  } catch {
    throw new Error('Invalid embedding base URL')
  }
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(endpoint.hostname)
  if ((endpoint.protocol !== 'https:' && !(endpoint.protocol === 'http:' && loopback)) ||
      endpoint.username || endpoint.password || endpoint.search || endpoint.hash) {
    throw new Error('Embedding URL requires HTTPS (except loopback), without credentials, query or fragment')
  }
  endpoint.pathname = endpoint.pathname.replace(/\/+$/, '') + '/embeddings'
  const sql = `SELECT pg_catalog.format_type(a.atttypid, a.atttypmod)
FROM pg_catalog.pg_attribute a
WHERE a.attrelid = to_regclass('public.${table}')
AND a.attname = 'embedding' AND NOT a.attisdropped`
  let columnType
  try {
    const result = await executeCommand('docker', ['exec', '-u', 'postgres', container,
      'psql', '-X', '-U', username, '-d', database, '-At', '-c', sql],
    { timeout: 15000, maxBuffer: 65536, windowsHide: true })
    columnType = result.stdout.trim()
  } catch {
    throw new Error('Database inspection failed; check Docker and PostgreSQL access locally')
  }
  const typeMatch = /^vector\(([1-9]\d*)\)$/.exec(columnType)
  if (!typeMatch || !Number.isSafeInteger(Number(typeMatch[1]))) {
    throw new Error('Expected a fixed-dimension public vector column; no model request was sent')
  }
  const headers = { 'Content-Type': 'application/json' }
  if (settings.apiKey) headers.Authorization = 'Bearer ' + settings.apiKey
  let payload
  try {
    const response = await fetchResponse(endpoint, {
      method: 'POST', headers, redirect: 'error', signal: AbortSignal.timeout(60000),
      body: JSON.stringify({ model: settings.model.trim(), input: '法律知识库嵌入维度检查',
        encoding_format: 'float', ...(dimensions === undefined ? {} : { dimensions }) })
    })
    if (!response.ok) throw new Error()
    payload = await response.json()
  } catch {
    throw new Error('Embedding probe failed; provider response and credentials are omitted')
  }
  const embedding = payload?.data?.[0]?.embedding
  if (!Array.isArray(payload?.data) || payload.data.length !== 1 ||
      payload.data[0]?.index !== 0 || !Array.isArray(embedding) || !embedding.length ||
      !embedding.every(value => typeof value === 'number' && Number.isFinite(value))) {
    throw new Error('Embedding response must contain exactly one indexed, nonempty finite numeric vector')
  }
  const tableDimensions = Number(typeMatch[1])
  return {
    checkedAt: new Date().toISOString(), probeType: 'synthetic', table: 'public.' + table,
    model: settings.model.trim(), tableDimensions, modelDimensions: embedding.length,
    requestedDimensions: dimensions ?? null,
    dimensionsMatch: tableDimensions === embedding.length &&
      (dimensions === undefined || dimensions === embedding.length)
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const result = await checkLegalEmbedding({
      baseUrl: process.env.EMBEDDING_BASE_URL, apiKey: process.env.EMBEDDING_API_KEY,
      model: process.env.EMBEDDING_MODEL, table: process.env.APP_LEGAL_VECTOR_TABLE,
      dimensions: process.env.EMBEDDING_DIMENSIONS === undefined ? undefined : Number(process.env.EMBEDDING_DIMENSIONS),
      container: process.env.PGVECTOR_CONTAINER, database: process.env.PGVECTOR_DATABASE,
      username: process.env.PGVECTOR_USERNAME
    })
    console.log(JSON.stringify(result, null, 2))
    process.exitCode = result.dimensionsMatch ? 0 : 1
  } catch (error) {
    console.error(error.message)
    process.exitCode = 2
  }
}
