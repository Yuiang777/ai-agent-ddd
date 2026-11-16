import { useEffect, useState } from 'react'
import { Alert, Button, Grid, LinearProgress, Stack, Typography } from '@mui/material'
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded'
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded'
import DataTable from '../components/DataTable'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'
import api from '../api/client'
import { errorMessage, formatTime, getDocumentUploadCapabilities, unwrap } from '../api/matters'

export default function KnowledgeBase() {
  const [rows, setRows] = useState([])
  const [stats, setStats] = useState(null)
  const [capability, setCapability] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [file, setFile] = useState(null)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    setLoading(true); setError(''); setCapability(null)
    const statsRequest = api.get('/legal/documents/stats').then(unwrap).then(value => {
      if (!value || !['totalDocuments', 'successDocuments', 'failedDocuments', 'totalChunks'].every(key => Number.isSafeInteger(value[key]) && value[key] >= 0)) throw new Error('知识库统计结果格式异常')
      return value
    })
    Promise.allSettled([statsRequest, getDocumentUploadCapabilities()]).then(results => {
      if (!active) return
      setStats(results[0].status === 'fulfilled' ? results[0].value : null)
      setCapability(results[1].status === 'fulfilled' ? results[1].value : null)
      setError(results.filter(item => item.status === 'rejected').map(item => errorMessage(item.reason)).join('；'))
      setLoading(false)
    })
    return () => { active = false }
  }, [revision])
  const upload = async () => {
    if (!file || busy || !capability?.enabled) return
    const selected = file
    if (selected.size === 0 || selected.size > capability.maxFileBytes || !capability.allowedExtensions.includes(selected.name.split('.').at(-1).toLowerCase())) {
      setError('文件为空、格式不支持或超过当前服务器的单文件上限。'); return
    }
    setBusy(true); setError('')
    const form = new FormData()
    form.append('file', selected); form.append('corpusType', 'FIRM_KNOWLEDGE')
    try {
      const result = unwrap(await api.post('/legal/documents/upload', form))
      if (result?.status !== 'STORED' || typeof result.documentId !== 'string' || !result.documentId.trim() || typeof result.objectUri !== 'string' || !result.objectUri.trim() || !/^[a-f0-9]{64}$/i.test(result.versionHash || '') || result.scope?.corpusType !== 'FIRM_KNOWLEDGE' || typeof result.scope?.tenantId !== 'string' || !result.scope.tenantId.trim()) throw new Error('服务器未返回完整的律所知识存储结果，请核对目录后重试')
      setRows(current => [{ id: result.documentId, name: selected.name, category: '律所知识', status: '已存储，待入库', updated: new Date().toISOString(), access: '服务端认证租户范围' }, ...current])
      setFile(null)
    } catch (failure) { setError(errorMessage(failure)) }
    finally { setBusy(false) }
  }
  return <PageShell title="企业知识库" subtitle="登记律所共享资料。文档存储与检索入库是不同阶段，访问范围由服务端身份及权限核验。" action={<Stack direction="row" spacing={1}><Button variant="outlined" startIcon={<RefreshRoundedIcon />} onClick={() => setRevision(value => value + 1)} disabled={loading || busy}>刷新状态</Button><Button component="label" variant="contained" startIcon={<UploadFileRoundedIcon />} disabled={!capability?.enabled || busy}>选择文档<input hidden type="file" accept={capability?.allowedExtensions.map(value => `.${value}`).join(',') || '.pdf,.doc,.docx,.txt,.md'} onChange={event => { setFile(event.target.files?.[0] || null); event.target.value = '' }} /></Button></Stack>}>
    {(loading || busy) && <LinearProgress aria-label={busy ? '正在上传文档' : '正在检查知识库状态'} />}
    {capability && <Alert severity={capability.enabled ? 'info' : 'warning'}>{capability.reason}{capability.enabled && ` 单文件上限 ${(capability.maxFileBytes / 1024 / 1024).toFixed(1)} MB。`}</Alert>}
    {file && <Alert severity="info" action={<Button size="small" disabled={busy || !capability?.enabled} onClick={upload}>{busy ? '上传中…' : '确认上传'}</Button>}>已选择：{file.name}。上传成功后仅登记为已存储，不代表已完成解析或检索入库。</Alert>}
    {error && <Alert severity="warning">{error}。填写和文件选择仍保留；请核对服务状态后重试。</Alert>}
    <Alert severity="info">文档目录查询接口尚未提供。下方仅显示当前页面上传成功的文件；统计来自服务端入库统计，当前尚未提供认证租户专属口径。</Alert>
    <Grid container spacing={1.5}>{[['入库统计文档数', stats?.totalDocuments], ['已摄取文档', stats?.successDocuments], ['文档分片', stats?.totalChunks], ['解析失败', stats?.failedDocuments]].map(([name, value]) => <Grid item xs={6} md={3} key={name}><SectionCard><Typography variant="body2" color="text.secondary">{name}</Typography><Typography variant="h5" sx={{ mt: 0.5, fontWeight: 800 }}>{value ?? '—'}</Typography></SectionCard></Grid>)}</Grid>
    <SectionCard title="本次上传记录" description="离开页面后此列表不保留；刷新状态不会清除本页记录。正式记录以服务端目录为准。"><DataTable rows={rows} rowKey={row => row.id} columns={[{ key: 'name', label: '文档名称', maxWidth: 320 }, { key: 'category', label: '分类' }, { key: 'status', label: '存储状态' }, { key: 'updated', label: '提交时间', render: formatTime }, { key: 'access', label: '访问范围' }]} /></SectionCard>
  </PageShell>
}
