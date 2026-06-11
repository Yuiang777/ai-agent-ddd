import { useMemo, useRef, useState } from 'react'
import { Alert, Box, Button, Chip, Grid, Stack, TextField, Typography } from '@mui/material'
import AutoAwesomeRoundedIcon from '@mui/icons-material/AutoAwesomeRounded'
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'
import { adminHeaders } from '../api/client'

function nowId() {
  return Date.now().toString()
}

function safeJsonParse(value) {
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

function useSseParser() {
  const stateRef = useRef({ event: '', dataLines: [] })
  const decoderRef = useRef(new TextDecoder('utf-8'))
  const bufferRef = useRef('')

  const feed = (chunkText, onEvent) => {
    bufferRef.current += chunkText
    let idx
    while ((idx = bufferRef.current.indexOf('\n')) >= 0) {
      const line = bufferRef.current.slice(0, idx).replace(/\r$/, '')
      bufferRef.current = bufferRef.current.slice(idx + 1)
      if (line.startsWith('event:')) {
        stateRef.current.event = line.slice(6).trim()
        continue
      }
      if (line.startsWith('data:')) {
        stateRef.current.dataLines.push(line.slice(5).trimStart())
        continue
      }
      if (line === '') {
        const eventName = stateRef.current.event || 'message'
        const dataText = stateRef.current.dataLines.join('\n')
        stateRef.current.event = ''
        stateRef.current.dataLines = []
        if (!dataText) continue
        onEvent(eventName, dataText)
      }
    }
  }

  const readStream = async (readableStream, onEvent) => {
    const reader = readableStream.getReader()
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      feed(decoderRef.current.decode(value, { stream: true }), onEvent)
    }
    feed(decoderRef.current.decode(new Uint8Array(), { stream: false }), onEvent)
  }

  return { readStream }
}

function MarkdownBlock({ value }) {
  return (
    <Box
      sx={{
        '& > :first-of-type': { mt: 0 },
        '& > :last-of-type': { mb: 0 },
        '& p': { m: 0, whiteSpace: 'pre-wrap', lineHeight: 1.7 },
        '& h1': { fontSize: '1.25rem', fontWeight: 800, mt: 1.5, mb: 1 },
        '& h2': { fontSize: '1.1rem', fontWeight: 800, mt: 1.25, mb: 0.75 },
        '& h3': { fontSize: '1.0rem', fontWeight: 800, mt: 1.0, mb: 0.5 },
        '& ul, & ol': { mt: 0.5, mb: 0.5, pl: 3 },
        '& li': { mt: 0.25, mb: 0.25 },
        '& blockquote': {
          m: 0,
          mt: 0.75,
          mb: 0.75,
          pl: 1.5,
          borderLeft: '4px solid',
          borderColor: 'divider',
          color: 'text.secondary'
        },
        '& pre': {
          mt: 1,
          mb: 1,
          p: 1.5,
          borderRadius: 1.5,
          overflow: 'auto',
          bgcolor: 'grey.900',
          color: 'grey.50',
          fontSize: '0.875rem',
          lineHeight: 1.6
        },
        '& code': {
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
          fontSize: '0.875em'
        },
        '& :not(pre) > code': {
          bgcolor: 'grey.100',
          color: 'text.primary',
          px: 0.6,
          py: 0.2,
          borderRadius: 1,
          border: '1px solid',
          borderColor: 'divider'
        },
        '& hr': { border: 'none', borderTop: '1px solid', borderColor: 'divider', my: 1.25 }
      }}
    >
      <ReactMarkdown remarkPlugins={[remarkGfm]}>
        {String(value ?? '')}
      </ReactMarkdown>
    </Box>
  )
}

const maxUploadSize = 20 * 1024 * 1024
const allowedUploadTypes = [
  'application/pdf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'text/plain',
  'text/markdown',
  'image/png',
  'image/jpeg',
  'image/webp'
]
const uploadAccept = allowedUploadTypes.join(',')

function validateFile(file) {
  if (!file) return ''
  if (file.size > maxUploadSize) return '文件不能超过 20MB'
  if (file.type && !allowedUploadTypes.includes(file.type)) return '只支持 PDF/Word/文本文件和 PNG/JPEG/WebP 图片'
  return ''
}

const nodes = [
  { id: 'intent', label: '意图识别' },
  { id: 'ocr', label: '材料解析' },
  { id: 'contract', label: '合同结构化' },
  { id: 'retrieval', label: '检索' },
  { id: 'reasoning', label: '推理' },
  { id: 'risk', label: '风险' },
  { id: 'citation', label: '引用' },
  { id: 'report', label: '报告' }
]

export default function Workflow() {
  const [clientId, setClientId] = useState('law-consultant')
  const [knowledgeTag, setKnowledgeTag] = useState('law-consultant')
  const [topK, setTopK] = useState(6)
  const [message, setMessage] = useState('请帮我审查这份劳动合同的竞业限制条款是否合理，并给出风险和修改建议。')
  const [file, setFile] = useState(null)
  const [running, setRunning] = useState(false)
  const [events, setEvents] = useState([])
  const [report, setReport] = useState('')
  const [error, setError] = useState('')
  const [nodeState, setNodeState] = useState(() => Object.fromEntries(nodes.map(n => [n.id, 'idle'])))
  const { readStream } = useSseParser()

  const reset = () => {
    setEvents([])
    setReport('')
    setError('')
    setNodeState(Object.fromEntries(nodes.map(n => [n.id, 'idle'])))
  }

  const onWorkflowEvent = (eventName, dataText) => {
    const parsed = safeJsonParse(dataText)
    const payload = parsed || { raw: dataText }
    setEvents(prev => [{ id: nowId(), event: eventName, payload }, ...prev].slice(0, 200))

    const nodeId = payload?.nodeId
    if (eventName === 'NODE_STARTED' && nodeId) setNodeState(prev => ({ ...prev, [nodeId]: 'running' }))
    if (eventName === 'NODE_COMPLETED' && nodeId) setNodeState(prev => ({ ...prev, [nodeId]: 'success' }))
    if (eventName === 'NODE_FAILED' && nodeId) setNodeState(prev => ({ ...prev, [nodeId]: 'failed' }))

    if (eventName === 'NODE_OUTPUT') {
      const text = payload?.data?.text
      if (typeof text === 'string' && text.trim()) setReport(text)
    }
    if (eventName === 'WORKFLOW_COMPLETED') {
      const finalOutput = payload?.data?.finalOutput
      if (typeof finalOutput === 'string' && finalOutput.trim()) setReport(finalOutput)
    }
    if (eventName === 'WORKFLOW_FAILED') {
      setError(payload?.message || '工作流失败')
    }
  }

  const runStream = async () => {
    if (running) return
    setRunning(true)
    reset()
    try {
      const cid = clientId.trim()
      const msg = message.trim()
      if (!cid || !msg) throw new Error('clientId 和 message 不能为空')
      const fileError = validateFile(file)
      if (fileError) throw new Error(fileError)

      let res
      if (file) {
        const form = new FormData()
        form.append('clientId', cid)
        form.append('message', msg)
        form.append('knowledgeTag', (knowledgeTag || '').trim())
        form.append('topK', String(topK || 6))
        form.append('file', file)
        res = await fetch('/api/workflows/run/stream/upload', { method: 'POST', headers: adminHeaders(), body: form })
      } else {
        res = await fetch('/api/workflows/run/stream', {
          method: 'POST',
          headers: adminHeaders({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({ clientId: cid, message: msg, variables: { knowledgeTag, topK } })
        })
      }

      if (!res.ok) {
        const text = await res.text()
        throw new Error(`HTTP ${res.status} ${res.statusText}\n${text}`)
      }
      if (!res.body) throw new Error('浏览器不支持 ReadableStream')

      await readStream(res.body, onWorkflowEvent)
    } catch (e) {
      setError(String(e?.message || e))
    } finally {
      setRunning(false)
    }
  }

  const runSync = async () => {
    if (running) return
    setRunning(true)
    reset()
    try {
      const cid = clientId.trim()
      const msg = message.trim()
      if (!cid || !msg) throw new Error('clientId 和 message 不能为空')
      const res = await fetch('/api/workflows/run', {
        method: 'POST',
        headers: adminHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ clientId: cid, message: msg, variables: { knowledgeTag, topK } })
      })
      const text = await res.text()
      const json = safeJsonParse(text)
      if (json?.success) {
        setReport(json?.data?.finalOutput || '')
        setEvents([{ id: nowId(), event: 'RUN', payload: json.data }])
        return
      }
      throw new Error(json?.message || text || '执行失败')
    } catch (e) {
      setError(String(e?.message || e))
    } finally {
      setRunning(false)
    }
  }

  const statusChip = useMemo(() => {
    const values = Object.values(nodeState)
    if (values.includes('failed')) return { label: 'FAILED', color: 'error' }
    if (values.includes('running')) return { label: 'RUNNING', color: 'warning' }
    if (report) return { label: 'DONE', color: 'success' }
    return { label: 'IDLE', color: 'default' }
  }, [nodeState, report])

  return (
    <PageShell
      title="工作流编排与执行"
      subtitle="合同解析、检索、推理、风险识别和报告生成的可追溯执行链路。"
      action={<Chip label={statusChip.label} color={statusChip.color} />}
    >
      <SectionCard title="执行参数" description="上传材料后会作为临时文件处理，工作流结束后自动清理。">
        <Grid container spacing={2}>
          <Grid item xs={12} md={3}>
            <TextField label="clientId" value={clientId} onChange={(e) => setClientId(e.target.value)} fullWidth />
          </Grid>
          <Grid item xs={12} md={3}>
            <TextField label="knowledgeTag" value={knowledgeTag} onChange={(e) => setKnowledgeTag(e.target.value)} fullWidth />
          </Grid>
          <Grid item xs={12} md={2}>
            <TextField label="topK" type="number" value={topK} onChange={(e) => setTopK(Number(e.target.value))} fullWidth />
          </Grid>
          <Grid item xs={12} md={4}>
            <Button variant="outlined" component="label" startIcon={<UploadFileRoundedIcon />} fullWidth sx={{ height: '100%', minHeight: 56 }}>
              {file ? `已选择：${file.name}` : '上传合同/PDF/Word/文本/图片（可选）'}
              <input hidden type="file" accept={uploadAccept} onChange={(e) => {
                const nextFile = e.target.files?.[0] || null
                const fileError = validateFile(nextFile)
                setFile(fileError ? null : nextFile)
                setError(fileError)
              }} />
            </Button>
          </Grid>
          <Grid item xs={12}>
            <TextField label="用户需求" value={message} onChange={(e) => setMessage(e.target.value)} fullWidth multiline minRows={3} />
          </Grid>
          <Grid item xs={12}>
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} alignItems={{ xs: 'stretch', md: 'center' }}>
              <Button variant="contained" startIcon={<AutoAwesomeRoundedIcon />} onClick={runStream} disabled={running}>
                {running ? '执行中...' : '流式执行（SSE）'}
              </Button>
              <Button variant="outlined" onClick={runSync} disabled={running}>同步执行</Button>
              <Button variant="text" color="error" onClick={reset} disabled={running}>清空结果</Button>
              <Box sx={{ flex: 1 }} />
              <Chip size="small" label="DAG · Multi‑Agent · 可追溯" />
            </Stack>
          </Grid>
        </Grid>
      </SectionCard>

      {error ? <Alert severity="error">{error}</Alert> : null}

      <Grid container spacing={2.5} alignItems="stretch">
        <Grid item xs={12} lg={3}>
          <SectionCard title="DAG 节点" sx={{ height: '100%' }}>
            <Stack spacing={1}>
              {nodes.map(n => (
                <Box key={n.id} sx={{ p: 1.25, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'grey.50' }}>
                  <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between">
                    <Typography variant="subtitle2">{n.label}</Typography>
                    <Chip size="small" label={nodeState[n.id] || 'idle'} color={nodeState[n.id] === 'failed' ? 'error' : nodeState[n.id] === 'running' ? 'warning' : nodeState[n.id] === 'success' ? 'success' : 'default'} />
                  </Stack>
                </Box>
              ))}
            </Stack>
          </SectionCard>
        </Grid>
        <Grid item xs={12} lg={4}>
          <SectionCard title="Agent Trace" description="展示每个节点的事件、输出与耗时，便于审计与复盘。" sx={{ height: '100%' }}>
            <Stack spacing={1} sx={{ maxHeight: 560, overflow: 'auto', pr: 0.5 }}>
              {events.length === 0 ? (
                <Alert severity="info">还没有事件。点击“流式执行（SSE）”开始。</Alert>
              ) : events.map(e => (
                <Box key={e.id} sx={{ p: 1.25, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'grey.50' }}>
                  <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between">
                    <Typography variant="subtitle2">{e.event}</Typography>
                    <Typography variant="caption" color="text.secondary">{e.payload?.nodeId || e.payload?.nodeType || 'workflow'}</Typography>
                  </Stack>
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75, whiteSpace: 'pre-wrap' }}>
                    {e.payload?.message || ''}
                  </Typography>
                </Box>
              ))}
            </Stack>
          </SectionCard>
        </Grid>
        <Grid item xs={12} lg={5}>
          <SectionCard title="最终报告（Markdown）" description="报告生成节点会输出可复制的结构化 Markdown，可扩展导出 PDF。" sx={{ height: '100%' }}>
            {report ? (
              <MarkdownBlock value={report} />
            ) : (
              <Alert severity="info">等待输出…</Alert>
            )}
          </SectionCard>
        </Grid>
      </Grid>
    </PageShell>
  )
}

