import { useEffect, useState } from 'react'
import { Alert, Button, Typography } from '@mui/material'
import DownloadRoundedIcon from '@mui/icons-material/DownloadRounded'
import DataTable from '../components/DataTable'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'
import { fetchGovernanceEvents } from '../api/client'
import { errorMessage } from '../api/matters'

export default function AuditLog() {
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  useEffect(() => {
    fetchGovernanceEvents().then(data => {
      if (!Array.isArray(data) || data.some(item => !item || typeof item !== 'object' || Array.isArray(item))) throw new Error('诊断记录格式异常')
      const text = (value, fallback) => typeof value === 'string' && value.trim() ? value : fallback
      setEvents(data.map(item => ({
        timestamp: text(item.timestamp, '未记录'), actor: text(item.clientId, '未提供客户端编号'),
        action: text(item.detail, '未提供诊断信息'), scope: text(item.clientId, '未提供'),
        result: item.success === true ? '成功' : item.success === false ? '失败' : '结果未知',
        source: typeof item.costMs === 'number' && Number.isFinite(item.costMs) && item.costMs >= 0 ? `${item.costMs} ms` : '未记录'
      })))
      setError('')
    }).catch(failure => { setEvents([]); setError(errorMessage(failure)) }).finally(() => setLoading(false))
  }, [])
  return <PageShell title="审批与审计" subtitle="此页展示服务端模型调用诊断记录。事项审核、签署和材料操作记录请在事项详情的时间线查看。" action={<Button variant="outlined" startIcon={<DownloadRoundedIcon />} disabled={!events.length} onClick={() => window.print()}>打印当前诊断视图</Button>}>
    {error && <Alert severity="warning">{error}。当前没有展示示例记录。</Alert>}
    {!loading && !error && !events.length && <Alert severity="info">当前服务未返回模型调用诊断记录。</Alert>}
    <Alert severity="info">模型调用诊断当前为服务进程内记录，重启后不保留，不能替代正式审计档案。</Alert>
    <SectionCard title="模型调用诊断"><DataTable emptyText={loading ? '正在加载诊断记录…' : error ? '加载失败，未展示诊断记录' : '暂无诊断记录'} rows={events} rowKey={(row, index) => `${row.timestamp || ''}-${index}`} columns={[{ key: 'timestamp', label: '时间' }, { key: 'actor', label: '客户端编号' }, { key: 'action', label: '诊断信息', maxWidth: 240 }, { key: 'scope', label: '调用范围' }, { key: 'result', label: '结果' }, { key: 'source', label: '耗时' }]} /></SectionCard>
    <Typography variant="caption" color="text.secondary">正式审计仍需持久化、访问控制、保留策略与导出记录；当前打印功能仅保存页面视图。</Typography>
  </PageShell>
}
