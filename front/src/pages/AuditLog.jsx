import { useEffect, useState } from 'react'
import { Alert, Button, Typography } from '@mui/material'
import DownloadRoundedIcon from '@mui/icons-material/DownloadRounded'
import DataTable from '../components/DataTable'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'
import { fetchGovernanceEvents } from '../api/client'

export default function AuditLog() {
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  useEffect(() => { fetchGovernanceEvents().then(data => { setEvents(Array.isArray(data) ? data.map(item => ({ timestamp: item.timestamp, actor: item.clientId || '未提供客户端编号', action: item.detail || '模型调用', scope: item.clientId || '-', result: item.success ? '成功' : '失败', source: `${item.costMs || 0} ms` })) : []); setError('') }).catch(e => { setEvents([]); setError(e.message || '治理接口不可用') }).finally(() => setLoading(false)) }, [])
  return <PageShell title="审批与审计" subtitle="此页展示服务端模型调用诊断记录。事项审核、签署和材料操作记录请在事项详情的时间线查看。" action={<Button variant="outlined" startIcon={<DownloadRoundedIcon />} disabled={!events.length} onClick={() => window.print()}>打印当前诊断视图</Button>}>
    {error && <Alert severity="warning">{error}。当前没有展示示例记录。</Alert>}
    {!loading && !error && !events.length && <Alert severity="info">当前服务未返回模型调用诊断记录。</Alert>}
    <Alert severity="info">模型调用诊断当前为服务进程内记录，重启后不保留，不能替代正式审计档案。</Alert>
    <SectionCard title="模型调用诊断"><DataTable rows={events} rowKey={(row, index) => `${row.timestamp || ''}-${index}`} columns={[{ key: 'timestamp', label: '时间' }, { key: 'actor', label: '客户端编号' }, { key: 'action', label: '诊断信息', maxWidth: 240 }, { key: 'scope', label: '调用范围' }, { key: 'result', label: '结果' }, { key: 'source', label: '耗时' }]} /></SectionCard>
    <Typography variant="caption" color="text.secondary">正式审计仍需持久化、访问控制、保留策略与导出记录；当前打印功能仅保存页面视图。</Typography>
  </PageShell>
}
