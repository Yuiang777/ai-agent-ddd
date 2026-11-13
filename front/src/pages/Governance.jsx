import { useEffect, useState } from 'react'
import { Alert, Grid, Typography } from '@mui/material'
import DataTable from '../components/DataTable'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'
import { fetchGovernanceEvents, fetchGovernanceHealth, fetchGovernanceOverview } from '../api/client'

export default function Governance() {
  const [overview, setOverview] = useState(null)
  const [health, setHealth] = useState(null)
  const [events, setEvents] = useState([])
  const [error, setError] = useState('')

  useEffect(() => {
    Promise.all([
      fetchGovernanceOverview(),
      fetchGovernanceHealth(),
      fetchGovernanceEvents()
    ]).then(([overviewData, healthData, eventData]) => {
      setOverview(overviewData)
      setHealth(healthData)
      setEvents(Array.isArray(eventData) ? eventData : [])
      setError('')
    }).catch(e => {
      setOverview(null)
      setHealth(null)
      setEvents([])
      setError(e.message || '加载治理数据失败')
    })
  }, [])

  const statItems = [
    ['总请求', overview?.totalRequests ?? '—'],
    ['成功', overview?.successRequests ?? '—'],
    ['失败', overview?.failedRequests ?? '—'],
    ['限流', overview?.rateLimitedRequests ?? '—']
  ]

  const columns = [
    { key: 'timestamp', label: '时间' },
    { key: 'clientId', label: 'Client' },
    { key: 'success', label: '成功' },
    { key: 'costMs', label: '耗时(ms)' },
    { key: 'detail', label: '详情' }
  ]

  return (
    <PageShell title="治理中心" subtitle="查看当前进程的调用诊断，统计尚未按租户隔离；正式事项记录请查看法律事项时间线。">
      <Grid container spacing={2}>
        {statItems.map(([title, value]) => (
          <Grid item xs={12} sm={6} md={3} key={title}>
            <SectionCard sx={{ height: '100%' }}>
              <Typography variant="body2" color="text.secondary">{title}</Typography>
              <Typography variant="h4" sx={{ mt: 0.75, fontWeight: 800 }}>{value}</Typography>
            </SectionCard>
          </Grid>
        ))}
      </Grid>
      {error ? <Alert severity="error">{error}</Alert> : null}
      <Alert severity={!health || health.status === 'degraded' ? 'warning' : 'info'}>
        当前状态：{health?.status || '待确认'}，活跃客户端：{health?.activeClients ?? '—'}
      </Alert>
      <SectionCard title="最近调用诊断" description="进程内诊断日志，不构成持久、不可变的正式审计档案。">
        <DataTable columns={columns} rows={events} rowKey={(row) => `${row.timestamp || ''}-${row.clientId || ''}-${row.costMs || ''}-${row.detail || ''}`} />
      </SectionCard>
    </PageShell>
  )
}
