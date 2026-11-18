import { useEffect, useState } from 'react'
import { Alert, Box, Button, Chip, Divider, Paper, Stack, Typography } from '@mui/material'
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded'
import DownloadRoundedIcon from '@mui/icons-material/DownloadRounded'
import PageShell from '../components/PageShell'
import { adminHeaders, fetchGovernanceReadiness, modelAvailability } from '../api/client'
import { readSseEvents } from '../api/sse'
import { displayableCitations, displayableItems, isPublishableWorkflowResult, validateWorkflowResult } from '../api/workflowResult'
import ReportRegistration from '../components/matters/ReportRegistration'
import { riskPresentation } from '../api/riskPresentation'

function parseJson(value) {
  try { return JSON.parse(value) } catch { return null }
}

function normalizeRisk(item = {}) {
  const level = item.level
  return {
    level,
    article: item.article || item.articleNo || item.location || '未提供条款位置',
    title: item.title || '未命名风险',
    text: item.description || item.text || '工作流未返回风险说明。',
    suggestion: item.suggestion || '修改合同前请交由律师复核。',
    law: item.law || item.source || item.citation || '未返回可核验引用'
  }
}

export default function ContractReview() {
  const [file, setFile] = useState(null)
  const [availability, setAvailability] = useState('checking')
  useEffect(() => {
    let active = true
    fetchGovernanceReadiness().then(data => { if (active) setAvailability(modelAvailability(data)) })
      .catch(() => { if (active) setAvailability('unknown') })
    return () => { active = false }
  }, [])
  const [reviewed, setReviewed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [offline, setOffline] = useState(false)
  const [error, setError] = useState('')
  const [riskItems, setRiskItems] = useState([])
  const [citations, setCitations] = useState([])
  const [report, setReport] = useState('')
  const [auditId, setAuditId] = useState('')
  const [resultStatus, setResultStatus] = useState('IDLE')

  const runReview = async () => {
    if (!file || busy || availability !== 'configured') return
    setBusy(true)
    setReviewed(false)
    setOffline(false)
    setError('')
    setResultStatus('IDLE')
    setRiskItems([])
    setCitations([])
    setReport('')
    setAuditId('')
    const message = `Review contract file "${file.name}" and return structured risks, clause-level evidence, citations, and actionable amendments.`
    try {
      if (!/\.(pdf|docx?)$/i.test(file.name) || !file.size) throw new Error('请选择非空的 PDF、DOC 或 DOCX 合同文件')
      if (file.size > 20 * 1024 * 1024) throw new Error('合同文件不能超过 20 MB，实际上传上限还取决于服务器配置')
      const authorization = await fetch('/api/workflows/uploads/authorize', {
        method: 'POST',
        headers: adminHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ message, variables: { matterType: 'CONTRACT_REVIEW' } })
      })
      const authorizationJson = parseJson(await authorization.text())
      if (!authorization.ok || !authorizationJson?.success || !authorizationJson?.data?.token) {
        throw new Error(authorizationJson?.message || `上传受理检查失败（HTTP ${authorization.status})`)
      }
      const form = new FormData()
      form.append('message', message)
      form.append('matterType', 'CONTRACT_REVIEW')
      form.append('file', file)
      const response = await fetch('/api/workflows/run/stream/upload', {
        method: 'POST',
        headers: adminHeaders({ 'X-Workflow-Upload-Authorization': authorizationJson.data.token }),
        body: form
      })
      if (!response.ok || !response.body) throw new Error(`工作流请求失败（HTTP ${response.status})`)
      let completed = false
      await readSseEvents(response.body, (eventName, dataText) => {
        const payload = parseJson(dataText) || {}
        if (eventName === 'WORKFLOW_COMPLETED') {
          const data = validateWorkflowResult(payload.data)
          if (!isPublishableWorkflowResult(data)) throw new Error('本次工作流未形成可展示的合同审查报告')
          completed = true
          setResultStatus(data.status)
          setRiskItems(displayableItems(data.riskItems, ['title', 'level', 'article', 'articleNo', 'location', 'description', 'text', 'suggestion', 'law', 'source', 'citation']).map(normalizeRisk))
          setCitations(displayableCitations(data.citationItems))
          setReport(data.finalOutput)
          setAuditId(typeof payload.runId === 'string' ? payload.runId.trim() : '')
        }
        if (eventName === 'WORKFLOW_FAILED') throw new Error(payload.message || '工作流执行失败')
      })
      if (!completed) throw new Error('工作流未返回完成事件，请检查服务后重试')
      setReviewed(true)
    } catch (e) {
      setResultStatus('FAILED')
      setOffline(true)
      setReviewed(true)
      setError(String(e?.message || e))
      setRiskItems([])
      setCitations([])
      setReport('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageShell
      title="合同审查"
      subtitle="上传合同后由服务端解析、检索授权依据并生成待复核结果。服务不可用时不会生成示例风险。"
      action={(
        <Stack direction="row" spacing={1}>
          <Button variant="outlined" startIcon={<DownloadRoundedIcon />} disabled={!reviewed || offline || !report} onClick={() => window.print()}>打印当前报告</Button>
        </Stack>
      )}
    >
      {availability === 'checking' && <Alert severity="info">正在检查模型能力…</Alert>}
      {availability === 'disabled' && <Alert severity="warning">当前环境未启用模型，合同自动审查不可用。你仍可在法律事项中登记材料和人工报告。</Alert>}
      {availability === 'unknown' && <Alert severity="warning" action={<Button onClick={() => window.location.reload()}>刷新检查</Button>}>无法确认模型能力，请检查服务连接后刷新。</Alert>}
      {!reviewed && (
        <Paper variant="outlined" sx={{ p: { xs: 2, md: 4 }, textAlign: 'center', borderStyle: 'dashed', bgcolor: '#FBFCFE' }}>
          <UploadFileRoundedIcon sx={{ fontSize: 42, color: 'primary.main' }} />
          <Typography variant="h6" sx={{ mt: 1, fontWeight: 800 }}>上传待审查合同</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>支持 PDF、DOC、DOCX，页面上限 20 MB；实际限制由服务器决定。工作流材料临时处理，正式留存请在事项中登记。</Typography>
          <Stack direction="row" spacing={1} justifyContent="center" sx={{ mt: 2 }}>
            <Button component="label" variant="outlined" disabled={busy}>选择合同<input hidden type="file" accept=".pdf,.doc,.docx" onChange={event => setFile(event.target.files?.[0] || null)} /></Button>
            <Button variant="contained" disabled={!file || busy || availability !== 'configured'} onClick={runReview}>{busy ? '审查中…' : '开始审查'}</Button>
          </Stack>
          {file && <Chip sx={{ mt: 1.5 }} label={`${file.name} - ${(file.size / 1024 / 1024).toFixed(2)} MB`} />}
        </Paper>
      )}

      {reviewed && (
        <>
          {offline && <Alert severity="warning" sx={{ mb: 1.5 }}>本次审查未完成，未展示风险结论或引用。请检查错误后重新提交。</Alert>}
          {error && <Alert severity="error" sx={{ mb: 1.5 }}>{error}</Alert>}
          {offline && <Button variant="outlined" onClick={() => { setReviewed(false); setOffline(false); setError('') }}>返回并重新选择合同</Button>}
          {!offline && <Alert severity="info" sx={{ mb: 1.5 }}>工作流记录： {auditId || '未返回'}。结果须经法律专业人员复核后使用。</Alert>}
          <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
            <Stack direction={{ xs: 'column', lg: 'row' }} divider={<Divider orientation="vertical" flexItem />}>
              <Box sx={{ flex: 1, minHeight: 580, bgcolor: '#F1F5F9' }}>
                <Box sx={{ p: 1.5, bgcolor: '#FFFFFF', borderBottom: '1px solid', borderColor: 'divider' }}><Typography variant="subtitle2" sx={{ fontWeight: 800 }}>{file?.name || '已上传合同'}</Typography></Box>
                <Box sx={{ maxWidth: 650, mx: 'auto', my: 2, p: { xs: 2, md: 4 }, minHeight: 500, bgcolor: 'white', boxShadow: '0 1px 4px rgba(15,23,42,.08)' }}>
                  <Typography variant="body2" color="text.secondary">当前未接入原件预览，下方显示工作流报告正文。请另行核对合同原件。</Typography>
                  {report && <Typography variant="body2" sx={{ mt: 2, whiteSpace: 'pre-wrap', lineHeight: 1.8 }}>{report}</Typography>}
                </Box>
              </Box>
              <Box sx={{ width: { xs: '100%', lg: 480 }, p: 2 }}>
                <Stack direction="row" justifyContent="space-between" alignItems="center"><Typography variant="h6" sx={{ fontWeight: 800 }}>风险清单</Typography><Chip label={`${riskItems.length} 项`} color={riskItems.length ? 'error' : 'default'} variant="outlined" /></Stack>
                {!riskItems.length && <Alert severity="info" sx={{ mt: 1.5 }}>未返回结构化风险项，不能据此认定合同没有风险。</Alert>}
                <Stack spacing={1.25} sx={{ mt: 1.5 }}>{riskItems.map(item => <Box key={`${item.title}-${item.article}`} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1.5 }}><Stack direction="row" spacing={0.75} alignItems="center"><Chip size="small" label={riskPresentation(item.level).label} color={riskPresentation(item.level).color} /><Typography variant="caption" color="text.secondary">{item.article}</Typography></Stack><Typography variant="subtitle2" sx={{ mt: 0.75, fontWeight: 800 }}>{item.title}</Typography><Typography variant="body2" sx={{ mt: 0.75, lineHeight: 1.6 }}>{item.text}</Typography><Typography variant="body2" color="primary.dark" sx={{ mt: 0.75 }}>修改建议： {item.suggestion}</Typography><Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>模型提供的依据（未核验）： {item.law}</Typography></Box>)}</Stack>
                {citations.length > 0 && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>附来源与摘录的候选引用： {citations.length} 条；原文、版本与法律适用仍需人工核验。</Typography>}
              </Box>
            </Stack>
          </Paper>
        </>
      )}
      {reviewed && !offline && report && ['SUCCESS', 'REVIEW_REQUIRED'].includes(resultStatus) && <ReportRegistration content={report} workflowRunId={auditId} />}
    </PageShell>
  )
}
