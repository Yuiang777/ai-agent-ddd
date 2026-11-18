import { useMemo, useRef, useState } from 'react'
import { Alert, AlertTitle, Box, Button, Chip, Grid, Stack, TextField, Typography } from '@mui/material'
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded'
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded'
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded'
import FactCheckRoundedIcon from '@mui/icons-material/FactCheckRounded'
import { useLocation, useNavigate } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'
import { adminHeaders } from '../api/client'
import { readSseEvents } from '../api/sse'
import { validateWorkflowResult, finalizedWorkflowReport, isPublishableWorkflowResult, displayableItems, displayableCitations, displayableReviewReasons } from '../api/workflowResult'
import { summarizeWorkflowEvent } from '../api/workflowEvents'
import { riskPresentation } from '../api/riskPresentation'
import ReportRegistration from '../components/matters/ReportRegistration'

function nowId() {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function safeJsonParse(value) {
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

const reportMarkdownComponents = {
  table: ({ children }) => <Box role="region" aria-label="报告表格" tabIndex={0} sx={{ maxWidth: '100%', overflowX: 'auto', my: 1 }}><table>{children}</table></Box>,
  img: ({ alt }) => <Typography component="span" variant="caption" color="text.secondary">[外部图片未加载{alt ? `：${alt}` : ''}]</Typography>,
  a: ({ href, children }) => {
    try {
      if (typeof href !== 'string' || !href.trim()) throw new Error('missing href')
      const url = new URL(href, window.location.origin)
      if (!['http:', 'https:'].includes(url.protocol)) throw new Error('unsupported protocol')
      return <a href={url.toString()} target="_blank" rel="noreferrer noopener">{children}</a>
    } catch {
      return <Typography component="span" color="text.secondary">{children}</Typography>
    }
  }
}

const nodeStatusPresentation = {
  idle: { label: '待执行', color: 'default' },
  running: { label: '执行中', color: 'warning' },
  success: { label: '已完成', color: 'success' },
  skipped: { label: '已跳过', color: 'default' },
  failed: { label: '执行失败', color: 'error' }
}

const eventLabels = {
  WORKFLOW_STARTED: '流程开始',
  NODE_STARTED: '节点开始',
  NODE_OUTPUT: '候选内容生成',
  NODE_COMPLETED: '节点完成',
  NODE_SKIPPED: '节点跳过',
  NODE_FAILED: '节点失败',
  REVIEW_REQUIRED: '转人工复核',
  WORKFLOW_COMPLETED: '流程完成',
  WORKFLOW_FAILED: '流程失败',
  RUN: '同步执行结果'
}

const retrievalStatusPresentation = {
  NOT_RUN: { label: '未执行', color: 'default' },
  HEALTHY: { label: '正常', color: 'default' },
  DEGRADED: { label: '降级', color: 'warning' },
  INSUFFICIENT: { label: '证据不足', color: 'warning' },
  UNAVAILABLE: { label: '不可用', color: 'error' },
  UNKNOWN: { label: '状态未知', color: 'default' }
}

function RetrievalStatusChip({ status }) {
  const presentation = typeof status === 'string' && Object.hasOwn(retrievalStatusPresentation, status)
    ? retrievalStatusPresentation[status]
    : retrievalStatusPresentation.UNKNOWN
  return <Chip size="small" variant="outlined" color={presentation.color} label={'检索：' + presentation.label}
    title={(typeof status === 'string' ? status : 'UNKNOWN') + '：仅表示检索服务状态，不代表引用或法律结论已经核验'} />
}

function NodeStatusChip({ state, running }) {
  const status = state === 'running' && !running
    ? { label: '状态未确认', color: 'warning' }
    : Object.hasOwn(nodeStatusPresentation, state)
      ? nodeStatusPresentation[state]
      : { label: '状态未知', color: 'default' }
  return <Chip size="small" label={status.label} color={status.color} />
}

function MarkdownBlock({ value }) {
  return (
    <Box
      sx={{
        minWidth: 0,
        overflowWrap: 'anywhere',
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
          maxWidth: '100%',
          boxSizing: 'border-box',
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
        '& table': { width: '100%', minWidth: 480, borderCollapse: 'collapse' },
        '& th, & td': { p: 1, border: '1px solid', borderColor: 'divider', textAlign: 'left', verticalAlign: 'top' },
        '& th': { bgcolor: 'grey.50', fontWeight: 650 },
        '& hr': { border: 'none', borderTop: '1px solid', borderColor: 'divider', my: 1.25 }
      }}
    >
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={reportMarkdownComponents}>
        {String(value ?? '')}
      </ReactMarkdown>
    </Box>
  )
}

function StructuredBlock({ value, empty = '尚未生成' }) {
  if (!value) return <Alert severity="info">{empty}</Alert>
  const parsed = typeof value === 'string' ? safeJsonParse(value) : value
  const rendered = parsed ? `\`\`\`json\n${JSON.stringify(parsed, null, 2)}\n\`\`\`` : String(value)
  return <MarkdownBlock value={rendered} />
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
  'image/webp',
  'video/mp4',
  'video/quicktime',
  'video/x-msvideo'
]
const uploadAccept = allowedUploadTypes.join(',')

function validateFile(file) {
  if (!file) return ''
  if (file.size > maxUploadSize) return '文件不能超过 20MB'
  if (file.type && !allowedUploadTypes.includes(file.type)) return '只支持 PDF/Word/文本文件、PNG/JPEG/WebP 图片和 MP4/MOV/AVI 视频'
  return ''
}

const nodes = [
  { id: 'intake', label: '受理门禁' },
  { id: 'intent', label: '意图识别' },
  { id: 'ocr', label: '材料解析' },
  { id: 'contract', label: '合同结构化' },
  { id: 'facts', label: '事实时间线' },
  { id: 'issues', label: '争点与举证' },
  { id: 'retrieval', label: '检索' },
  { id: 'evidence', label: '证据矩阵' },
  { id: 'procedure', label: '程序与期限' },
  { id: 'reasoning', label: '推理' },
  { id: 'opponent', label: '对方分析' },
  { id: 'strategy', label: '诉讼策略' },
  { id: 'risk', label: '风险' },
  { id: 'citation', label: '引用' },
  { id: 'review', label: '证据复核' },
  { id: 'lawyerReview', label: '律师签发' },
  { id: 'report', label: '报告' }
]

export default function Workflow() {
  const location = useLocation()
  const navigate = useNavigate()
  const matter = location.state?.matter || null
  const [message, setMessage] = useState(() => matter
    ? `请处理法律事项“${matter.title}”（${matter.type}）。委托方：${matter.client}；当前需要完成：${matter.nextAction}。请识别风险、核验法律依据并给出可执行建议。`
    : '请帮我审查这份劳动合同的竞业限制条款是否合理，并给出风险和修改建议。')
  const [file, setFile] = useState(null)
  const [running, setRunning] = useState(false)
  const [events, setEvents] = useState([])
  const [report, setReport] = useState('')
  const [workflowRunId, setWorkflowRunId] = useState('')
  const [risks, setRisks] = useState([])
  const [citations, setCitations] = useState([])
  const [reviewReasons, setReviewReasons] = useState([])
  const [retrievalStatus, setRetrievalStatus] = useState('NOT_RUN')
  const [resultStatus, setResultStatus] = useState('IDLE')
  const [litigationAnalysis, setLitigationAnalysis] = useState({})
  const [modelOutputQuality, setModelOutputQuality] = useState({})
  const [error, setError] = useState('')
  const [nodeState, setNodeState] = useState(() => Object.fromEntries(nodes.map(n => [n.id, 'idle'])))
  const terminalEvent = useRef(false)

  const reset = () => {
    setEvents([])
    setReport('')
    setWorkflowRunId('')
    terminalEvent.current = false
    setRisks([])
    setCitations([])
    setReviewReasons([])
    setRetrievalStatus('NOT_RUN')
    setResultStatus('IDLE')
    setLitigationAnalysis({})
    setModelOutputQuality({})
    setError('')
    setNodeState(Object.fromEntries(nodes.map(n => [n.id, 'idle'])))
  }

  const discardUnverifiedResult = () => {
    setReport('')
    setRisks([])
    setCitations([])
    setReviewReasons([])
    setRetrievalStatus('NOT_RUN')
    setLitigationAnalysis({})
    setModelOutputQuality({})
  }

  const onWorkflowEvent = (eventName, dataText) => {
    const parsed = safeJsonParse(dataText)
    const payload = parsed || { raw: dataText }
    setEvents(prev => [{ id: nowId(), ...summarizeWorkflowEvent(eventName, payload, nodes.map(node => node.id), Object.keys(eventLabels)) }, ...prev].slice(0, 200))

    const nodeId = nodes.some(node => node.id === payload?.nodeId) ? payload.nodeId : null
    if (eventName === 'NODE_STARTED' && nodeId) setNodeState(prev => ({ ...prev, [nodeId]: 'running' }))
    if (eventName === 'NODE_COMPLETED' && nodeId) setNodeState(prev => ({ ...prev, [nodeId]: 'success' }))
    if (eventName === 'NODE_SKIPPED' && nodeId) setNodeState(prev => ({ ...prev, [nodeId]: 'skipped' }))
    if (eventName === 'NODE_FAILED' && nodeId) setNodeState(prev => ({ ...prev, [nodeId]: 'failed' }))
    if (eventName === 'NODE_COMPLETED' && nodeId === 'retrieval' && payload?.data?.retrievalStatus) {
      setRetrievalStatus(payload.data.retrievalStatus)
    }
    if (eventName === 'REVIEW_REQUIRED') {
      setReviewReasons(displayableReviewReasons(payload?.data?.reasons))
      setResultStatus('REVIEW_REQUIRED')
    }
    if (eventName === 'WORKFLOW_COMPLETED') {
      const completed = validateWorkflowResult(payload?.data)
      terminalEvent.current = true
      setWorkflowRunId(typeof payload?.runId === 'string' ? payload.runId.trim() : '')
      setReport(finalizedWorkflowReport(completed))
      const publishable = isPublishableWorkflowResult(completed)
      if (publishable) setRetrievalStatus(previous => previous === 'NOT_RUN' ? 'UNKNOWN' : previous)
      setRisks(publishable ? displayableItems(payload?.data?.riskItems, ['title', 'level', 'description', 'suggestion']) : [])
      setCitations(publishable ? displayableCitations(payload?.data?.citationItems) : [])
      setReviewReasons(displayableReviewReasons(payload?.data?.reviewReasons))
      setResultStatus(payload.data.status)
      setLitigationAnalysis(publishable ? (payload?.data?.litigationAnalysis || {}) : {})
      setModelOutputQuality(payload?.data?.modelOutputQuality || {})
    }
    if (eventName === 'WORKFLOW_FAILED') {
      throw new Error(typeof payload?.message === 'string' && payload.message.trim()
        ? payload.message
        : '工作流失败')
    }
  }

  const workflowVariables = () => ({
    representedPartyName: matter?.representedPartyName || '',
    partyRole: matter?.partyRole || '',
    proceedingType: matter?.proceedingType || '',
    proceedingStage: matter?.proceedingStage || '',
    courtOrTribunal: matter?.courtOrTribunal || '',
    opponentNames: matter?.opponentNames || '',
    conflictCheckCompleted: Boolean(matter?.conflictCheckCompleted),
    engagementAuthorized: Boolean(matter?.engagementAuthorized),
    caseNumber: matter?.caseNumber || '',
    nextDeadline: matter?.nextDeadline || matter?.deadline || '',
    conflictCheckReference: matter?.conflictCheckReference || '',
    engagementReference: matter?.engagementReference || '',
    deadlineSource: matter?.deadlineSource || '',
    matterReference: matter?.id || ''
  })

  const uploadPermitted = !matter || matter.type !== '诉讼仲裁' || (
    matter.conflictCheckCompleted
    && matter.engagementAuthorized
    && Boolean(matter.conflictCheckReference?.trim())
    && Boolean(matter.engagementReference?.trim())
    && (!matter.nextDeadline && !matter.deadline || Boolean(matter.deadlineSource?.trim()))
  )

  const runStream = async () => {
    if (running) return
    setRunning(true)
    reset()
    try {
      const msg = message.trim()
      if (!msg) throw new Error('请输入需要分析的法律问题')
      const fileError = validateFile(file)
      if (fileError) throw new Error(fileError)

      let res
      if (file) {
        const variables = workflowVariables()
        const authorizationResponse = await fetch('/api/workflows/uploads/authorize', {
          method: 'POST',
          headers: adminHeaders({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({ message: msg, variables })
        })
        const authorizationText = await authorizationResponse.text()
        const authorizationJson = safeJsonParse(authorizationText)
        if (!authorizationResponse.ok || !authorizationJson?.success || !authorizationJson?.data?.token) {
          throw new Error(authorizationJson?.message || `上传受理预检失败（HTTP ${authorizationResponse.status}）`)
        }
        const form = new FormData()
        form.append('message', msg)
        Object.entries(variables).forEach(([key, value]) => {
          if (value !== '') form.append(key, String(value))
        })
        form.append('file', file)
        res = await fetch('/api/workflows/run/stream/upload', {
          method: 'POST',
          headers: adminHeaders({ 'X-Workflow-Upload-Authorization': authorizationJson.data.token }),
          body: form
        })
      } else {
        res = await fetch('/api/workflows/run/stream', {
          method: 'POST',
          headers: adminHeaders({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({ message: msg, variables: workflowVariables() })
        })
      }

      if (!res.ok) {
        const text = await res.text()
        throw new Error(`HTTP ${res.status} ${res.statusText}\n${text}`)
      }
      if (!res.body) throw new Error('浏览器不支持 ReadableStream')

      await readSseEvents(res.body, onWorkflowEvent)
      if (!terminalEvent.current) throw new Error('工作流连接已结束，但未返回完成事件；当前内容可能不完整，请重新执行')
    } catch (e) {
      discardUnverifiedResult()
      setError(String(e?.message || e))
      setResultStatus('FAILED')
    } finally {
      setRunning(false)
    }
  }

  const runSync = async () => {
    if (running || file) return
    setRunning(true)
    reset()
    try {
      const msg = message.trim()
      if (!msg) throw new Error('请输入需要分析的法律问题')
      const res = await fetch('/api/workflows/run', {
        method: 'POST',
        headers: adminHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ message: msg, variables: workflowVariables() })
      })
      const text = await res.text()
      const json = safeJsonParse(text)
      if (res.ok && json?.success === true) {
        const result = validateWorkflowResult(json.data)
        const reasons = displayableReviewReasons(result?.variables?.reviewReasons)
        setReport(finalizedWorkflowReport(result))
        setWorkflowRunId(typeof result.runId === 'string' ? result.runId.trim() : '')
        const publishable = isPublishableWorkflowResult(result)
        setRisks(publishable ? displayableItems(result.risks, ['title', 'level', 'description', 'suggestion']) : [])
        setCitations(publishable ? displayableCitations(result.citations) : [])
        setReviewReasons(reasons)
        setRetrievalStatus(result?.variables?.retrievalStatus || 'UNKNOWN')
        setResultStatus(result.status)
        setModelOutputQuality(result.modelOutputQuality || {})
        setLitigationAnalysis(publishable ? {
          casePosture: result?.variables?.casePosture,
          partyRole: result?.variables?.partyRole,
          proceedingType: result?.variables?.proceedingType,
          proceedingStage: result?.variables?.proceedingStage,
          representedPartyName: result?.variables?.representedPartyName,
          opponentNames: result?.variables?.opponentNames,
          courtOrTribunal: result?.variables?.courtOrTribunal,
          caseNumber: result?.variables?.caseNumber,
          nextDeadline: result?.variables?.nextDeadline,
          intakeApproved: result?.variables?.intakeApproved,
          intakeDecision: result?.variables?.intakeDecision,
          factsTimeline: result?.variables?.factsTimeline,
          issuesMatrix: result?.variables?.issuesMatrix,
          evidenceMatrix: result?.variables?.evidenceMatrix,
          proceduralAnalysis: result?.variables?.proceduralAnalysis,
          opponentAnalysis: result?.variables?.opponentAnalysis,
          litigationStrategy: result?.variables?.litigationStrategy,
          lawyerReviewStatus: result?.variables?.lawyerReviewStatus
        } : {})
        setEvents([{ id: nowId(), ...summarizeWorkflowEvent('RUN', result, nodes.map(node => node.id), Object.keys(eventLabels)) }])
        return
      }
      throw new Error(json?.message || text || '执行失败')
    } catch (e) {
      discardUnverifiedResult()
      setError(String(e?.message || e))
      setResultStatus('FAILED')
    } finally {
      setRunning(false)
    }
  }

  const statusChip = useMemo(() => {
    if (resultStatus === 'FAILED' || error) return { label: '执行失败', color: 'error' }
    if (resultStatus === 'BLOCKED') return { label: '受理暂停', color: 'error' }
    if (running) return { label: '执行中', color: 'warning' }
    if (resultStatus === 'REVIEW_REQUIRED') return {
      label: litigationAnalysis.casePosture === 'LITIGATION' || matter?.type === '诉讼仲裁' ? '待律师签发' : '待人工复核',
      color: 'warning'
    }
    if (report && resultStatus === 'SUCCESS') return workflowRunId
      ? { label: '分析完成，待登记', color: 'success' }
      : { label: '分析完成，缺少运行编号', color: 'warning' }
    return { label: '待执行', color: 'default' }
  }, [error, litigationAnalysis, matter?.type, running, report, resultStatus, workflowRunId])

  return (
    <PageShell
      title="案件分析与报告"
      subtitle="从委托受理、事实与证据分析，到诉讼策略草案和律师签发的可追溯案件链路。"
      action={<Chip label={statusChip.label} color={statusChip.color} />}
    >
      {matter ? (
        <Alert
          severity="info"
          action={<Button color="inherit" size="small" startIcon={<ArrowBackRoundedIcon />} onClick={() => navigate('/matters')}>返回事项</Button>}
        >
          <AlertTitle>{matter.title}</AlertTitle>
          {matter.id} · {matter.client} · 负责人：{matter.owner} · 时限：{matter.nextDeadline || matter.deadline}
          {matter.type === '诉讼仲裁' ? ` · ${matter.partyRole || '地位待定'} · 对方：${matter.opponentNames || '待登记'}` : ''}
        </Alert>
      ) : null}
      <SectionCard title="执行参数" description="上传材料后会作为临时文件处理，工作流结束后自动清理。">
        <Grid container spacing={2}>
          <Grid item xs={12}>
            <Button disabled={!uploadPermitted || running} variant="outlined" component="label" startIcon={<UploadFileRoundedIcon />} fullWidth sx={{ minHeight: 56 }}>
              {!uploadPermitted ? '完成冲突审查与授权后可上传' : file ? `已选择：${file.name}` : '上传案件材料（可选）'}
              <input hidden disabled={!uploadPermitted || running} type="file" accept={uploadAccept} onChange={(e) => {
                const nextFile = e.target.files?.[0] || null
                const fileError = validateFile(nextFile)
                setFile(fileError ? null : nextFile)
                setError(fileError)
                e.target.value = ''
              }} />
            </Button>
            {file ? <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1 }}>
              <Typography variant="caption" color="text.secondary" sx={{ flex: 1, minWidth: 0 }}>已选材料仅随“实时执行”提交；同步执行不接收附件。</Typography>
              <Button size="small" disabled={running} sx={{ flexShrink: 0, whiteSpace: 'nowrap' }} onClick={() => setFile(null)}>移除材料</Button>
            </Stack> : null}
          </Grid>
          <Grid item xs={12}>
            <TextField label="用户需求" value={message} onChange={(e) => setMessage(e.target.value)} fullWidth multiline minRows={3} />
          </Grid>
          <Grid item xs={12}>
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} alignItems={{ xs: 'stretch', md: 'center' }}>
              <Button variant="contained" startIcon={<PlayArrowRoundedIcon />} onClick={runStream} disabled={running}>
                {running ? '执行中...' : '实时执行'}
              </Button>
              <Button variant="outlined" onClick={runSync} disabled={running || Boolean(file)}>同步执行</Button>
              <Button variant="text" color="error" onClick={reset} disabled={running}>清空结果</Button>
              <Box sx={{ flex: 1 }} />
              <Chip size="small" label="流程状态 · 过程可追溯" />
              <RetrievalStatusChip status={retrievalStatus} />
            </Stack>
          </Grid>
        </Grid>
      </SectionCard>

      {error ? <Alert severity="error">{error}</Alert> : null}

      {Object.entries(modelOutputQuality).some(([, quality]) => quality?.fallbackUsed) ? (
        <Alert severity="warning" icon={<FactCheckRoundedIcon />}>
          <AlertTitle>模型输出兜底，需要人工复核</AlertTitle>
          相关节点未返回有效结构化结果；当前输出仅供内部复核，不得直接作为对外法律意见。
        </Alert>
      ) : null}

      {resultStatus === 'BLOCKED' ? (
        <Alert severity="error" icon={<FactCheckRoundedIcon />}>
          <AlertTitle>案件受理已暂停，后续分析节点未执行</AlertTitle>
          {reviewReasons.length ? reviewReasons.join('；') : '未提供具体受理原因，请联系审核人员核查'}。补正并由律师确认前不会读取材料或生成诉讼策略。
        </Alert>
      ) : resultStatus === 'REVIEW_REQUIRED' || reviewReasons.length ? (
        <Alert severity="warning" icon={<FactCheckRoundedIcon />}>
          <AlertTitle>本次结果必须人工复核后方可对外使用</AlertTitle>
          {reviewReasons.length ? reviewReasons.join('；') : '系统已将本次结果标记为待人工复核'}。请在“法律事项 → 复核队列”登记并完成人工复核。
        </Alert>
      ) : report ? (
        <Alert severity="success">工作流已完成，未触发强制人工复核规则。对外出具正式意见前仍应遵循企业内部审批制度。</Alert>
      ) : null}

      <Grid container spacing={2.5} alignItems="stretch">
        <Grid item xs={12} lg={5} sx={{ order: { xs: 0, lg: 2 } }}>
          <SectionCard title="候选分析报告" description="仅展示完成门禁核验的结果；正式出具前仍需人工复核。" sx={{ height: '100%' }}>
            {report ? (
              <MarkdownBlock value={report} />
            ) : (
              <Alert severity="info">{resultStatus === 'BLOCKED' ? '受理已暂停，本次未形成报告。' : resultStatus === 'FAILED' ? '执行失败，本次未形成报告。' : '尚无报告，完成分析后显示。'}</Alert>
            )}
          </SectionCard>
        </Grid>
        <Grid item xs={12} lg={3} sx={{ order: { xs: 1, lg: 0 } }}>
          <SectionCard title="流程节点" sx={{ height: '100%' }}>
            <Stack spacing={1} role="region" aria-label="流程节点列表" tabIndex={0}
              sx={{ maxHeight: { xs: 280, lg: 480 }, overflowY: 'auto', pr: 0.5 }}>
              {nodes.map(n => (
                <Box key={n.id} sx={{ p: 1.25, flexShrink: 0, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'grey.50' }}>
                  <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between">
                    <Typography variant="subtitle2">{n.label}</Typography>
                    <NodeStatusChip state={nodeState[n.id] || 'idle'} running={running} />
                  </Stack>
                </Box>
              ))}
            </Stack>
          </SectionCard>
        </Grid>
        <Grid item xs={12} lg={4} sx={{ order: { xs: 2, lg: 1 } }}>
          <SectionCard title="执行记录" description="仅保留节点事件与耗时，不在事件历史中缓存原始响应。" sx={{ height: '100%' }}>
            <Stack spacing={1} sx={{ maxHeight: 560, overflow: 'auto', pr: 0.5 }}>
              {events.length === 0 ? (
                <Alert severity="info">还没有事件。点击“实时执行”开始。</Alert>
              ) : events.map(e => (
                <Box key={e.id} sx={{ p: 1.25, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'grey.50' }}>
                  <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between">
                    <Typography variant="subtitle2" title={e.event}>{Object.hasOwn(eventLabels, e.event) ? eventLabels[e.event] : '流程事件'}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {e.nodeId || 'workflow'}
                      {e.costMs !== null ? ` · ${e.costMs}ms` : ''}
                    </Typography>
                  </Stack>
                </Box>
              ))}
            </Stack>
          </SectionCard>
        </Grid>
      </Grid>

      {report && (matter?.type === '诉讼仲裁' || litigationAnalysis.casePosture === 'LITIGATION') ? (
        <Grid container spacing={2.5}>
          <Grid item xs={12}><Alert severity="warning">结构化内容仅供律师内部核验：来源匹配不等于语义支持或法律适用正确；核验失败时不展示自动分析。</Alert></Grid>
          <Grid item xs={12} lg={4}><SectionCard title="案件态势">
            <Stack spacing={1}>
              <Typography variant="body2">委托方：{litigationAnalysis.representedPartyName || matter?.representedPartyName || '待登记'}</Typography>
              <Typography variant="body2">诉讼地位：{litigationAnalysis.partyRole || matter?.partyRole || '待确认'}</Typography>
              <Typography variant="body2">程序：{litigationAnalysis.proceedingType || matter?.proceedingType || '待确认'} / {litigationAnalysis.proceedingStage || matter?.proceedingStage || '待确认'}</Typography>
              <Typography variant="body2">法院/机构：{litigationAnalysis.courtOrTribunal || matter?.courtOrTribunal || '待确认'}</Typography>
              <Typography variant="body2">对方：{litigationAnalysis.opponentNames || matter?.opponentNames || '待登记'}</Typography>
              <Typography variant="body2">案号：{litigationAnalysis.caseNumber || matter?.caseNumber || '未登记'}</Typography>
              <Typography variant="body2">最近期限：{litigationAnalysis.nextDeadline || matter?.nextDeadline || matter?.deadline || '待核验'}</Typography>
              <Chip size="small" color={litigationAnalysis.intakeApproved ? 'success' : 'warning'} label={litigationAnalysis.intakeApproved ? '受理门禁通过' : '受理条件待补'} />
              <Chip size="small" color="warning" label={`律师签发：${litigationAnalysis.lawyerReviewStatus || 'REQUIRED'}`} />
            </Stack>
          </SectionCard></Grid>
          <Grid item xs={12} lg={4}><SectionCard title="事实时间线"><StructuredBlock value={litigationAnalysis.factsTimeline} /></SectionCard></Grid>
          <Grid item xs={12} lg={4}><SectionCard title="争点与举证责任"><StructuredBlock value={litigationAnalysis.issuesMatrix} /></SectionCard></Grid>
          <Grid item xs={12} lg={4}><SectionCard title="证据矩阵"><StructuredBlock value={litigationAnalysis.evidenceMatrix} /></SectionCard></Grid>
          <Grid item xs={12} lg={4}><SectionCard title="程序与期限"><StructuredBlock value={litigationAnalysis.proceduralAnalysis} /></SectionCard></Grid>
          <Grid item xs={12} lg={4}><SectionCard title="对方最强主张"><StructuredBlock value={litigationAnalysis.opponentAnalysis} /></SectionCard></Grid>
          <Grid item xs={12}><SectionCard title="诉讼策略草案"><StructuredBlock value={litigationAnalysis.litigationStrategy} /></SectionCard></Grid>
        </Grid>
      ) : null}

      {report ? (
        <Grid container spacing={2.5}>
          <Grid item xs={12} lg={6}>
            <SectionCard title={`风险清单 (${risks.length})`} description="模型生成的候选风险清单，未完成逐条语义核查；高风险事项需人工复核。">
              <Stack spacing={1}>
                {risks.length ? risks.map((risk, index) => (
                  <Box key={`${risk.title}-${index}`} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1.5 }}>
                    <Stack direction="row" spacing={1} alignItems="center">
                      <Chip size="small" label={riskPresentation(risk.level).label} color={riskPresentation(risk.level).color} />
                      <Typography variant="subtitle2">{risk.title}</Typography>
                    </Stack>
                    <Typography variant="body2" sx={{ mt: 1 }}>{risk.description}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75 }}>建议：{risk.suggestion}</Typography>
                  </Box>
                )) : <Alert severity="info">工作流未返回结构化风险项。</Alert>}
              </Stack>
            </SectionCard>
          </Grid>
          <Grid item xs={12} lg={6}>
            <SectionCard title={`来源信息齐全的候选引用 (${citations.length})`} description="只校验原文匹配和来源元数据；法律结论的语义支持与适用性仍需人工复核。">
              <Stack spacing={1}>
                {citations.length ? citations.map((citation, index) => (
                  <Box key={`${citation.title}-${citation.articleNo}-${index}`} sx={{ borderLeft: '3px solid', borderColor: 'primary.main', pl: 1.5, py: 0.5 }}>
                    <Typography variant="subtitle2">{citation.title} {citation.articleNo || ''} {citation.clauseNo || ''}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>{citation.excerpt || '未提供条文摘录'}</Typography>
                    {citation.source ? <Typography variant="caption" color="text.secondary">来源：{citation.source}</Typography> : null}
                  </Box>
                )) : <Alert severity="warning">没有全部通过来源匹配的候选主张；不得据此出具法律意见。</Alert>}
              </Stack>
            </SectionCard>
          </Grid>
        </Grid>
      ) : null}
      {report && !running && !error && ['SUCCESS', 'REVIEW_REQUIRED'].includes(resultStatus) && <ReportRegistration content={report} workflowRunId={workflowRunId} initialMatterId={matter?.id} />}
    </PageShell>
  )
}
