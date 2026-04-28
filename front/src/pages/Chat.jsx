import { useEffect, useMemo, useRef, useState } from 'react'
import { Box, Button, Card, CardContent, Divider, FormControl, InputLabel, MenuItem, Select, Stack, TextField, Typography, IconButton, Avatar, Dialog, DialogTitle, DialogContent, DialogActions } from '@mui/material'
import SendIcon from '@mui/icons-material/Send'
import SettingsIcon from '@mui/icons-material/Settings'

const STORAGE_KEY = 'agent_console_chat_config_v1'
const CONV_KEY = 'agent_console_chat_conversations_v1'

function defaultTool() {
  return {
    mcpId: '',
    mcpName: '',
    transportType: 'sse',
    baseUri: 'http://127.0.0.1:8101',
    sseEndpoint: '/sse',
    command: 'node',
    argsText: '',
    envJson: '{}'
  }
}

function presetTools() {
  return [
    {
      mcpId: '5001',
      mcpName: 'CSDN自动发帖',
      transportType: 'sse',
      baseUri: 'http://127.0.0.1:8101',
      sseEndpoint: '/sse',
      command: 'node',
      argsText: '',
      envJson: '{}'
    },
    {
      mcpId: '5002',
      mcpName: '微信公众号消息通知',
      transportType: 'sse',
      baseUri: 'http://127.0.0.1:8102',
      sseEndpoint: '/sse',
      command: 'node',
      argsText: '',
      envJson: '{}'
    },
    {
      mcpId: '5003',
      mcpName: 'server-filesystem',
      transportType: 'stdio',
      baseUri: '',
      sseEndpoint: '/sse',
      command: 'node',
      argsText: [
        'F:/Zliao/AI/tool/node_modules/@modelcontextprotocol/server-filesystem/dist/index.js',
        'F:/Zliao/AI/tool',
        'F:/Zliao/AI/tool'
      ].join('\n'),
      envJson: '{}'
    },
    {
      mcpId: '5004',
      mcpName: 'g-search',
      transportType: 'stdio',
      baseUri: '',
      sseEndpoint: '/sse',
      command: 'npx',
      argsText: ['-y', 'g-search-mcp'].join('\n'),
      envJson: '{}'
    }
  ]
}

function sqlEscape(value) {
  return String(value ?? '').replaceAll('\\', '\\\\').replaceAll("'", "''")
}

function buildSql({ clientId, apiId, baseUrl, apiKey, completionsPath, embeddingsPath, modelId, modelName, modelType, promptId, promptName, promptContent, tools }) {
  const now = 'NOW()'
  const toolIds = (tools || []).map(t => t.mcpId).filter(Boolean)
  const toolSql = (tools || [])
    .filter(t => t.mcpId && t.mcpName)
    .map(t => {
      const transportConfig = buildTransportConfig(t, false)
      return `INSERT INTO ai_client_tool_mcp (mcp_id, mcp_name, transport_type, transport_config, request_timeout, status, create_time, update_time)\nVALUES ('${sqlEscape(t.mcpId)}','${sqlEscape(t.mcpName)}','${sqlEscape(t.transportType)}','${sqlEscape(transportConfig)}',180,1,${now},${now});`
    })
    .join('\n\n')

  const clientConfigLines = [
    modelId ? `INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, status, create_time, update_time)\nVALUES ('client','${sqlEscape(clientId)}','model','${sqlEscape(modelId)}',1,${now},${now});` : '',
    promptId ? `INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, status, create_time, update_time)\nVALUES ('client','${sqlEscape(clientId)}','prompt','${sqlEscape(promptId)}',1,${now},${now});` : '',
    ...toolIds.map(mcpId => `INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, status, create_time, update_time)\nVALUES ('client','${sqlEscape(clientId)}','tool_mcp','${sqlEscape(mcpId)}',1,${now},${now});`)
  ].filter(Boolean).join('\n\n')

  const modelToolLines = modelId
    ? toolIds.map(mcpId => `INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, status, create_time, update_time)\nVALUES ('model','${sqlEscape(modelId)}','tool_mcp','${sqlEscape(mcpId)}',1,${now},${now});`).join('\n\n')
    : ''

  const blocks = [
    `INSERT INTO ai_client_api (api_id, base_url, api_key, completions_path, embeddings_path, status, create_time, update_time)\nVALUES ('${sqlEscape(apiId)}','${sqlEscape(baseUrl)}','${sqlEscape(apiKey)}','${sqlEscape(completionsPath)}','${sqlEscape(embeddingsPath)}',1,${now},${now});`,
    `INSERT INTO ai_client_model (model_id, api_id, model_name, model_type, status, create_time, update_time)\nVALUES ('${sqlEscape(modelId)}','${sqlEscape(apiId)}','${sqlEscape(modelName)}','${sqlEscape(modelType)}',1,${now},${now});`,
    `INSERT INTO ai_client (client_id, client_name, description, status, create_time, update_time)\nVALUES ('${sqlEscape(clientId)}','${sqlEscape(clientId)}','',1,${now},${now});`,
    promptId ? `INSERT INTO ai_client_system_prompt (prompt_id, prompt_name, prompt_content, description, status, create_time, update_time)\nVALUES ('${sqlEscape(promptId)}','${sqlEscape(promptName)}','${sqlEscape(promptContent)}','',1,${now},${now});` : '',
    toolSql,
    clientConfigLines,
    modelToolLines
  ].filter(Boolean)

  return blocks.join('\n\n')
}

function buildTransportConfig(tool, pretty = true) {
  if (tool.transportType === 'sse') {
    const payload = { baseUri: tool.baseUri.trim(), sseEndpoint: tool.sseEndpoint.trim() || '/sse' }
    return pretty ? JSON.stringify(payload, null, 2) : JSON.stringify(payload)
  }

  const key = tool.mcpName?.trim() || tool.mcpId?.trim() || 'tool'
  const args = tool.argsText
    .split('\n')
    .map(s => s.trim())
    .filter(Boolean)
  let env = {}
  try {
    env = JSON.parse(tool.envJson || '{}') || {}
  } catch {
    env = {}
  }

  const payload = {
    stdio: {
      [key]: {
        command: tool.command.trim(),
        args,
        env
      }
    }
  }
  return pretty ? JSON.stringify(payload, null, 2) : JSON.stringify(payload)
}

export default function Chat() {
  const [clientId, setClientId] = useState('3001')
  const [backendBaseUrl, setBackendBaseUrl] = useState('')
  const [apiId, setApiId] = useState('api-3001')
  const [baseUrl, setBaseUrl] = useState('https://yinli.one/')
  const [apiKey, setApiKey] = useState('YOUR_API_KEY')
  const [completionsPath, setCompletionsPath] = useState('/v1/chat/completions')
  const [embeddingsPath, setEmbeddingsPath] = useState('/v1/embeddings')
  const [modelId, setModelId] = useState('model-3001')
  const [modelName, setModelName] = useState('gpt-4.1-mini')
  const [modelType, setModelType] = useState('openai')
  const [promptId, setPromptId] = useState('prompt-3001')
  const [promptName, setPromptName] = useState('默认提示词')
  const [promptContent, setPromptContent] = useState('你是一个有用的 AI 助手，请用中文回答。')
  const [tools, setTools] = useState([defaultTool()])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [openSettings, setOpenSettings] = useState(false)
  const [testLoading, setTestLoading] = useState(false)
  const [testResult, setTestResult] = useState('')
  const [activeId, setActiveId] = useState(null)
  const [conversations, setConversations] = useState([])
  const [selectedToolIndex, setSelectedToolIndex] = useState(0)
  const selectedTool = tools[selectedToolIndex] || tools[0]
  const active = useMemo(() => conversations.find(c => c.id === activeId), [conversations, activeId])
  const [renameValue, setRenameValue] = useState('')
  const listRef = useRef(null)

  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return
      const parsed = JSON.parse(raw)
      if (parsed?.clientId) setClientId(parsed.clientId)
      if (parsed?.backendBaseUrl) setBackendBaseUrl(parsed.backendBaseUrl)
      if (parsed?.apiId) setApiId(parsed.apiId)
      if (parsed?.baseUrl) setBaseUrl(parsed.baseUrl)
      if (parsed?.apiKey) setApiKey(parsed.apiKey)
      if (parsed?.completionsPath) setCompletionsPath(parsed.completionsPath)
      if (parsed?.embeddingsPath) setEmbeddingsPath(parsed.embeddingsPath)
      if (parsed?.modelId) setModelId(parsed.modelId)
      if (parsed?.modelName) setModelName(parsed.modelName)
      if (parsed?.modelType) setModelType(parsed.modelType)
      if (parsed?.promptId) setPromptId(parsed.promptId)
      if (parsed?.promptName) setPromptName(parsed.promptName)
      if (parsed?.promptContent) setPromptContent(parsed.promptContent)
      if (Array.isArray(parsed?.tools) && parsed.tools.length > 0) setTools(parsed.tools)
    } catch {
    }
  }, [])

  useEffect(() => {
    try {
      const raw = localStorage.getItem(CONV_KEY)
      if (raw) {
        const parsed = JSON.parse(raw)
        if (Array.isArray(parsed)) {
          setConversations(parsed)
          if (parsed.length > 0) setActiveId(parsed[0].id)
        }
      }
    } catch {
    }
  }, [])

  useEffect(() => {
    if (!active && conversations.length === 0) {
      const id = Date.now().toString()
      const conv = { id, title: '新的对话', messages: [{ role: 'assistant', content: '你好，我可以为你提供帮助。' }] }
      setActiveId(id)
      setConversations([conv])
      localStorage.setItem(CONV_KEY, JSON.stringify([conv]))
    }
  }, [active, conversations])

  useEffect(() => {
    const payload = { clientId, backendBaseUrl, apiId, baseUrl, apiKey, completionsPath, embeddingsPath, modelId, modelName, modelType, promptId, promptName, promptContent, tools }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
  }, [clientId, backendBaseUrl, apiId, baseUrl, apiKey, completionsPath, embeddingsPath, modelId, modelName, modelType, promptId, promptName, promptContent, tools])

  const transportConfigPreview = useMemo(() => buildTransportConfig(selectedTool || defaultTool()), [selectedTool])
  const sqlPreview = useMemo(() => buildSql({ clientId, apiId, baseUrl, apiKey, completionsPath, embeddingsPath, modelId, modelName, modelType, promptId, promptName, promptContent, tools }), [clientId, apiId, baseUrl, apiKey, completionsPath, embeddingsPath, modelId, modelName, modelType, promptId, promptName, promptContent, tools])

  const updateConversations = (updater) => {
    setConversations(prev => {
      const next = updater(prev)
      localStorage.setItem(CONV_KEY, JSON.stringify(next))
      return next
    })
  }

  const ensureActiveId = () => {
    if (activeId) return activeId
    const id = Date.now().toString()
    const conv = { id, title: '新的对话', messages: [{ role: 'assistant', content: '你好，我可以为你提供帮助。' }] }
    setActiveId(id)
    updateConversations(prev => [conv, ...prev])
    return id
  }

  const appendToActive = (messagesPatch) => {
    const convId = ensureActiveId()
    updateConversations(prev => prev.map(c => c.id === convId ? { ...c, messages: [...c.messages, ...messagesPatch] } : c))
    return convId
  }

  const replaceLastAssistant = (convId, content) => {
    updateConversations(prev => prev.map(c => {
      if (c.id !== convId) return c
      const msgs = [...c.messages]
      for (let i = msgs.length - 1; i >= 0; i -= 1) {
        if (msgs[i]?.role === 'assistant') {
          msgs[i] = { ...msgs[i], content }
          break
        }
      }
      return { ...c, messages: msgs }
    }))
  }

  const onSend = async () => {
    const content = input.trim()
    if (!content || sending) return

    setSending(true)
    const convId = appendToActive([{ role: 'user', content }, { role: 'assistant', content: '...' }])
    setInput('')

    try {
      const base = (backendBaseUrl || '').trim().replace(/\/+$/, '')
      const url = base ? `${base}/api/chat` : '/api/chat'
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ clientId, message: content })
      })
      const text = await res.text()
      let json = null
      try {
        json = JSON.parse(text)
      } catch {
        json = null
      }

      if (json?.success) {
        replaceLastAssistant(convId, json?.data?.content ?? '')
        return
      }

      if (json && json.message) {
        replaceLastAssistant(convId, json.message)
        return
      }

      const hint = res.status === 404
        ? '后端没有注册 /api/chat，请确认已编译并重启后端（包含 ChatController）'
        : `HTTP ${res.status} ${res.statusText}`
      replaceLastAssistant(convId, `${hint}\n请求地址：${url}\n\n${text?.slice(0, 400) || ''}`)
    } catch (e) {
      const base = (backendBaseUrl || '').trim()
      const tip = base
        ? '直连后端会触发浏览器 CORS 预检，未配置跨域时可能直接 Failed to fetch。建议清空“后端地址”使用前端代理。'
        : '当前走前端代理 /api。如果仍失败，请重启前端 dev server，并确认 vite.config.js 已配置 proxy。'
      replaceLastAssistant(convId, `无法连接后端。\n${tip}\n后端地址：${backendBaseUrl || '(使用前端代理)'}\n错误：${String(e?.message || e)}`)
    } finally {
      setSending(false)
    }
  }

  const updateTool = (idx, patch) => {
    setTools(prev => prev.map((t, i) => (i === idx ? { ...t, ...patch } : t)))
  }

  const addTool = () => {
    setTools(prev => {
      const next = [...prev, defaultTool()]
      return next
    })
    setSelectedToolIndex(tools.length)
  }

  const removeTool = (idx) => {
    setTools(prev => prev.filter((_, i) => i !== idx))
    setSelectedToolIndex(0)
  }

  const startNew = () => {
    const id = Date.now().toString()
    const conv = { id, title: '新的对话', messages: [{ role: 'assistant', content: '你好，我可以为你提供帮助。' }] }
    setActiveId(id)
    updateConversations(prev => [conv, ...prev])
  }

  const setActiveById = (id) => setActiveId(id)

  const renameActive = (title) => {
    if (!active) return
    updateConversations(prev => prev.map(c => c.id === active.id ? { ...c, title } : c))
  }

  const deleteActive = () => {
    if (!active) return
    const deletedId = active.id
    updateConversations(prev => prev.filter(c => c.id !== deletedId))
    const nextId = conversations.find(c => c.id !== deletedId)?.id || null
    setActiveId(nextId)
  }

  useEffect(() => {
    setRenameValue(active?.title || '')
  }, [active?.id])

  useEffect(() => {
    if (!listRef.current) return
    listRef.current.scrollTop = listRef.current.scrollHeight
  }, [active?.messages?.length])

  const resetLocal = () => {
    localStorage.removeItem(STORAGE_KEY)
    localStorage.removeItem(CONV_KEY)
    setBackendBaseUrl('')
    setClientId('3001')
    setApiId('api-3001')
    setBaseUrl('https://yinli.one/')
    setApiKey('YOUR_API_KEY')
    setCompletionsPath('/v1/chat/completions')
    setEmbeddingsPath('/v1/embeddings')
    setModelId('model-3001')
    setModelName('gpt-4.1-mini')
    setModelType('openai')
    setPromptId('prompt-3001')
    setPromptName('默认提示词')
    setPromptContent('你是一个有用的 AI 助手，请用中文回答。')
    setTools(presetTools())
    setInput('')
    const id = Date.now().toString()
    const conv = { id, title: '新的对话', messages: [{ role: 'assistant', content: '你好，我可以为你提供帮助。' }] }
    setActiveId(id)
    setConversations([conv])
    localStorage.setItem(CONV_KEY, JSON.stringify([conv]))
  }

  const testBackend = async () => {
    if (testLoading) return
    setTestLoading(true)
    setTestResult('')
    try {
      const base = (backendBaseUrl || '').trim().replace(/\/+$/, '')
      const url = base ? `${base}/api/chat` : '/api/chat'
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ clientId, message: 'ping' })
      })
      const text = await res.text()
      setTestResult(`HTTP ${res.status} ${res.statusText}\n请求地址：${url}\n\n${text}`)
    } catch (e) {
      setTestResult(`请求失败：${String(e?.message || e)}`)
    } finally {
      setTestLoading(false)
    }
  }

  return (
    <Box sx={{ display: 'flex', height: 'calc(100vh - 80px)' }}>
      <Box sx={{ width: 280, borderRight: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
        <Box sx={{ p: 2, display: 'flex', gap: 1 }}>
          <Button variant="contained" fullWidth onClick={startNew}>新对话</Button>
        </Box>
        <Box sx={{ p: 1, overflow: 'auto', height: 'calc(100% - 64px)' }}>
          <Stack spacing={0.5}>
            {conversations.map(c => (
              <Button key={c.id} variant={c.id === activeId ? 'outlined' : 'text'} onClick={() => setActiveById(c.id)} sx={{ justifyContent: 'flex-start' }}>
                {c.title}
              </Button>
            ))}
          </Stack>
        </Box>
      </Box>

      <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <Box sx={{ px: 2, py: 1, borderBottom: '1px solid', borderColor: 'divider', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography variant="subtitle1">{active?.title || '新的对话'}</Typography>
            <TextField
              size="small"
              placeholder="重命名"
              value={renameValue}
              onChange={(e) => setRenameValue(e.target.value)}
              onBlur={() => renameActive(renameValue)}
              sx={{ width: 200 }}
            />
            <Button size="small" color="error" onClick={deleteActive}>删除</Button>
          </Stack>
          <IconButton onClick={() => setOpenSettings(true)}><SettingsIcon /></IconButton>
        </Box>

        <Box ref={listRef} sx={{ flex: 1, overflow: 'auto', px: 3, py: 2 }}>
          <Stack spacing={2}>
            {(active?.messages || [{ role: 'assistant', content: '你好，我可以为你提供帮助。' }]).map((m, idx) => (
              <Stack key={idx} direction="row" spacing={2} sx={{ alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start', maxWidth: '900px' }}>
                <Avatar sx={{ bgcolor: m.role === 'user' ? 'primary.main' : 'grey.700', width: 28, height: 28 }}>{m.role === 'user' ? '我' : 'AI'}</Avatar>
                <Box sx={{ px: 2, py: 1.5, borderRadius: 2, bgcolor: m.role === 'user' ? 'primary.main' : 'background.paper', color: m.role === 'user' ? 'primary.contrastText' : 'text.primary', border: m.role === 'user' ? 'none' : '1px solid', borderColor: 'divider', whiteSpace: 'pre-wrap' }}>
                  {m.content}
                </Box>
              </Stack>
            ))}
          </Stack>
        </Box>

        <Box sx={{ px: 3, py: 2, borderTop: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <TextField
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="发送消息"
              fullWidth
              multiline
              minRows={1}
              maxRows={6}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  onSend()
                }
              }}
            />
            <Button variant="contained" endIcon={<SendIcon />} onClick={onSend} disabled={sending}>发送</Button>
          </Stack>
        </Box>
      </Box>

      <Dialog open={openSettings} onClose={() => setOpenSettings(false)} maxWidth="md" fullWidth>
        <DialogTitle>MCP 设置</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField label="后端地址（留空使用前端代理 /api）" value={backendBaseUrl} onChange={(e) => setBackendBaseUrl(e.target.value)} fullWidth />
            <Stack direction="row" spacing={1}>
              <Button variant="outlined" onClick={() => setBackendBaseUrl('')}>清空后端地址</Button>
              <Button variant="outlined" onClick={testBackend} disabled={testLoading}>测试接口</Button>
              <Button variant="outlined" color="error" onClick={resetLocal}>重置本地配置</Button>
            </Stack>
            {testResult ? (
              <TextField label="测试结果" value={testResult} fullWidth multiline minRows={4} />
            ) : null}
            <TextField label="Client ID" value={clientId} onChange={(e) => setClientId(e.target.value)} fullWidth />
            <Divider />
            <Typography variant="subtitle2">后端需要的数据（参考 AgentTest：commandIdList = [{clientId}]）</Typography>
            <Typography variant="body2" color="text.secondary">至少需要：ai_client + ai_client_config + ai_client_model + ai_client_api。若要用工具需配置 ai_client_tool_mcp 并启动对应 MCP 服务。</Typography>
            <Stack spacing={1}>
              <TextField label="api_id" value={apiId} onChange={(e) => setApiId(e.target.value)} fullWidth />
              <TextField label="base_url" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} fullWidth />
              <TextField label="api_key（可留空，使用你自己的 key）" value={apiKey} onChange={(e) => setApiKey(e.target.value)} fullWidth />
              <Stack direction="row" spacing={1}>
                <TextField label="completions_path" value={completionsPath} onChange={(e) => setCompletionsPath(e.target.value)} fullWidth />
                <TextField label="embeddings_path" value={embeddingsPath} onChange={(e) => setEmbeddingsPath(e.target.value)} fullWidth />
              </Stack>
              <Stack direction="row" spacing={1}>
                <TextField label="model_id" value={modelId} onChange={(e) => setModelId(e.target.value)} fullWidth />
                <TextField label="model_type" value={modelType} onChange={(e) => setModelType(e.target.value)} fullWidth />
              </Stack>
              <TextField label="model_name" value={modelName} onChange={(e) => setModelName(e.target.value)} fullWidth />
              <Stack direction="row" spacing={1}>
                <TextField label="prompt_id（可选）" value={promptId} onChange={(e) => setPromptId(e.target.value)} fullWidth />
                <TextField label="prompt_name（可选）" value={promptName} onChange={(e) => setPromptName(e.target.value)} fullWidth />
              </Stack>
              <TextField label="prompt_content（可选）" value={promptContent} onChange={(e) => setPromptContent(e.target.value)} fullWidth multiline minRows={2} />
            </Stack>
            <Divider />
            <Stack direction="row" spacing={1} alignItems="center">
              <FormControl fullWidth>
                <InputLabel>MCP 工具</InputLabel>
                <Select label="MCP 工具" value={selectedToolIndex} onChange={(e) => setSelectedToolIndex(Number(e.target.value))}>
                  {tools.map((t, idx) => (
                    <MenuItem key={idx} value={idx}>{t.mcpName || t.mcpId || `未命名工具 ${idx + 1}`}</MenuItem>
                  ))}
                </Select>
              </FormControl>
              <Button variant="outlined" onClick={addTool}>新增</Button>
              <Button variant="outlined" onClick={() => { setTools(presetTools()); setSelectedToolIndex(0) }}>导入预置</Button>
              <Button variant="outlined" color="error" onClick={() => removeTool(selectedToolIndex)} disabled={tools.length <= 1}>删除</Button>
            </Stack>
            <TextField label="mcpId" value={selectedTool?.mcpId || ''} onChange={(e) => updateTool(selectedToolIndex, { mcpId: e.target.value })} fullWidth />
            <TextField label="mcpName" value={selectedTool?.mcpName || ''} onChange={(e) => updateTool(selectedToolIndex, { mcpName: e.target.value })} fullWidth />
            <FormControl fullWidth>
              <InputLabel>transportType</InputLabel>
              <Select label="transportType" value={selectedTool?.transportType || 'sse'} onChange={(e) => updateTool(selectedToolIndex, { transportType: e.target.value })}>
                <MenuItem value="sse">sse</MenuItem>
                <MenuItem value="stdio">stdio</MenuItem>
              </Select>
            </FormControl>
            {selectedTool?.transportType === 'sse' ? (
              <Stack spacing={1}>
                <TextField label="baseUri" value={selectedTool?.baseUri || ''} onChange={(e) => updateTool(selectedToolIndex, { baseUri: e.target.value })} fullWidth />
                <TextField label="sseEndpoint" value={selectedTool?.sseEndpoint || ''} onChange={(e) => updateTool(selectedToolIndex, { sseEndpoint: e.target.value })} fullWidth />
              </Stack>
            ) : (
              <Stack spacing={1}>
                <TextField label="command" value={selectedTool?.command || ''} onChange={(e) => updateTool(selectedToolIndex, { command: e.target.value })} fullWidth />
                <TextField label="args（每行一个参数）" value={selectedTool?.argsText || ''} onChange={(e) => updateTool(selectedToolIndex, { argsText: e.target.value })} fullWidth multiline minRows={3} />
                <TextField label="env（JSON）" value={selectedTool?.envJson || ''} onChange={(e) => updateTool(selectedToolIndex, { envJson: e.target.value })} fullWidth multiline minRows={3} />
              </Stack>
            )}
            <Divider />
            <TextField label="transport_config 预览" value={transportConfigPreview} fullWidth multiline minRows={6} />
            <TextField label="SQL 生成（复制到 ai-agent-station 数据库执行）" value={sqlPreview} fullWidth multiline minRows={10} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpenSettings(false)}>完成</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
