import { useEffect, useMemo, useState } from 'react'
import { Alert, Box, Button, Card, CardContent, Chip, Grid, LinearProgress, Stack, Typography } from '@mui/material'
import AddRoundedIcon from '@mui/icons-material/AddRounded'
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded'
import DescriptionRoundedIcon from '@mui/icons-material/DescriptionRounded'
import EventNoteRoundedIcon from '@mui/icons-material/EventNoteRounded'
import ForumRoundedIcon from '@mui/icons-material/ForumRounded'
import MenuBookRoundedIcon from '@mui/icons-material/MenuBookRounded'
import WarningAmberRoundedIcon from '@mui/icons-material/WarningAmberRounded'
import { useNavigate } from 'react-router-dom'
import { businessLabels, statusLabels } from '../api/matters'
import { workflowRunCount } from '../api/dashboardMetrics'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'
import { useWorkspaceAudience } from '../components/WorkspaceAudienceContext'
import { isClientAudience } from '../navigation'
import { fetchBillingAccount, fetchBillingUsage, fetchGovernanceOverview, fetchLegalDocumentStats, fetchLegalMatters } from '../api/client'

const statusMeta = {
  INTAKE: ['待受理', 'default'],
  MATERIAL_PENDING: ['待补材料', 'warning'],
  REVIEW_REQUIRED: ['待人工复核', 'error'],
  SIGNOFF_REQUIRED: ['待律师签发', 'warning'],
  SIGNED_OFF: ['已签发', 'success'],
  CLOSED: ['已归档', 'success']
}
const nextActions = { INTAKE: '开始受理核验', CONFLICT_CHECK: '完成利益冲突检查', ENGAGEMENT_PENDING: '核对委托授权', MATERIAL_PENDING: '收集并核对材料', ANALYSIS: '登记分析报告并提交复核', REVIEW_REQUIRED: '处理质量复核任务', SIGNOFF_REQUIRED: '核对报告并提交律师签署' }
const priorityMeta = { HIGH: ['高', 'error'], MEDIUM: ['中', 'warning'], LOW: ['低', 'success'] }

function normalizeMatter(item) {
  const matter = item?.matter || item || {}
  const attributes = matter.attributes || {}
  return {
    ...matter,
    id: matter.id || '',
    title: matter.title || '未命名事项',
    status: matter.status || 'INTAKE',
    priority: matter.priority || attributes.priority || 'MEDIUM',
    type: businessLabels[item?.scope?.businessType] || item?.scope?.businessType || matter.type || attributes.type || '法律事项',
    owner: matter.owner || attributes.owner || '待分配',
    clientReference: matter.clientReference || attributes.client || '当前租户',
    updatedAt: matter.updatedAt || '',
    nextAction: matter.nextAction || attributes.nextAction || nextActions[matter.status] || '查看办理记录'
  }
}

function number(value) {
  return value === null || value === undefined ? '—' : new Intl.NumberFormat('zh-CN').format(value)
}

function ClientDashboard() {
  const navigate = useNavigate()
  const [matters, setMatters] = useState([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState(false)

  useEffect(() => {
    let active = true
    fetchLegalMatters().then(result => {
      if (active) setMatters(Array.isArray(result) ? result.map(normalizeMatter) : [])
    }).catch(() => {
      if (active) setUnavailable(true)
    }).finally(() => {
      if (active) setLoading(false)
    })
    return () => { active = false }
  }, [])

  const activeMatters = matters.filter(item => !['SIGNED_OFF', 'CLOSED', 'REJECTED'].includes(item.status))
  const completedMatters = matters.filter(item => ['SIGNED_OFF', 'CLOSED'].includes(item.status))
  const clientStatus = status => ({
    INTAKE: '事项受理中', MATERIAL_PENDING: '需要补充材料', SIGNED_OFF: '已完成', CLOSED: '已归档', REJECTED: '已结束'
  })[status] || '律师处理中'
  const clientStatusColor = status => status === 'MATERIAL_PENDING' ? 'warning' : ['SIGNED_OFF', 'CLOSED'].includes(status) ? 'success' : 'primary'

  return (
    <PageShell title="客户服务门户" subtitle="清晰查看事项进展，随时获取专业法律支持。">
      <Card variant="outlined" sx={{ bgcolor: 'background.paper', boxShadow: 'none' }}>
        <CardContent sx={{ p: { xs: 2.5, md: 3 }, '&:last-child': { pb: { xs: 2.5, md: 3 } } }}>
          <Typography variant="overline" color="text.secondary">客户服务 · 事项总览</Typography>
          <Typography variant="h4" sx={{ mt: 0.5 }}>我的法律事项</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>查看当前授权范围内的事项进展，或整理新的咨询问题。</Typography>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 2.5 }}>
            <Button variant="contained" onClick={() => navigate('/matters')}>查看我的事项</Button>
            <Button variant="outlined" startIcon={<ForumRoundedIcon />} onClick={() => navigate('/chat')}>填写咨询草稿</Button>
          </Stack>
        </CardContent>
      </Card>

      <Grid container spacing={1.5}>
        {[
          ['进行中', loading || unavailable ? '—' : activeMatters.length, '当前办理'],
          ['已完成', loading || unavailable ? '—' : completedMatters.length, '完成或归档'],
          ['全部事项', loading || unavailable ? '—' : matters.length, '当前授权范围']
        ].map(([label, value, hint]) => <Grid item xs={4} sm={4} key={label}>
          <SectionCard sx={{ height: '100%' }}><Typography variant="body2" color="text.secondary">{label}</Typography><Typography variant="h4" sx={{ mt: 0.7, fontSize: { xs: 24, sm: 30 } }}>{value}</Typography><Typography variant="caption" color="text.secondary">{hint}</Typography></SectionCard>
        </Grid>)}
      </Grid>

      <SectionCard title="事项进展" description="仅展示当前登录身份有权查看的事项。" action={<Button size="small" endIcon={<ArrowForwardRoundedIcon />} onClick={() => navigate('/matters')}>全部事项</Button>}>
        {loading && <LinearProgress />}
        {unavailable && <Alert severity="info">暂时无法读取事项，请稍后重试。</Alert>}
        {!loading && !unavailable && activeMatters.length === 0 && <Typography variant="body2" color="text.secondary">目前没有进行中的事项。需要帮助时，可以联系您的法律服务团队。</Typography>}
        <Stack divider={<Box sx={{ borderBottom: '1px solid', borderColor: 'divider' }} />}>
          {activeMatters.slice(0, 4).map(item => <Stack key={item.id} direction={{ xs: 'column', sm: 'row' }} spacing={1.25} alignItems={{ xs: 'stretch', sm: 'center' }} sx={{ py: 1.6 }}>
            <Box sx={{ flex: 1, minWidth: 0 }}><Typography variant="subtitle1" sx={{ fontWeight: 650 }}>{item.title}</Typography><Typography variant="caption" color="text.secondary">{item.type} · 更新于 {item.updatedAt || '暂无记录'}</Typography></Box>
            <Chip size="small" label={clientStatus(item.status)} color={clientStatusColor(item.status)} variant="outlined" />
            <Button size="small" onClick={() => navigate('/matters')}>查看</Button>
          </Stack>)}
        </Stack>
      </SectionCard>

      <Grid container spacing={1.5}>
        {[
          ['法律咨询', '整理问题，开始一条新的咨询记录。', ForumRoundedIcon, '/chat'],
          ['法律事项', '查看事项阶段、材料与进展。', EventNoteRoundedIcon, '/matters'],
          ['法规与判例', '浏览公开法律资料。', MenuBookRoundedIcon, '/law-library']
        ].map(([label, description, Icon, path]) => <Grid item xs={12} md={4} key={label}>
          <Button onClick={() => navigate(path)} fullWidth sx={{ height: '100%', p: 2, justifyContent: 'flex-start', textAlign: 'left', border: '1px solid', borderColor: 'divider', borderRadius: 3, bgcolor: 'background.paper', color: 'text.primary' }}>
            <Icon sx={{ mr: 1.5, color: 'secondary.main' }} /><Box><Typography variant="subtitle2">{label}</Typography><Typography variant="caption" color="text.secondary">{description}</Typography></Box>
          </Button>
        </Grid>)}
      </Grid>
      <Alert severity="info" icon={<WarningAmberRoundedIcon />}>系统生成内容仅作为办案辅助，不构成正式法律意见；重要判断与后续行动请以律师审核和正式文件为准。</Alert>
    </PageShell>
  )
}
function StaffDashboard() {
  const navigate = useNavigate()
  const [snapshot, setSnapshot] = useState({})
  const [loading, setLoading] = useState(true)
  const [errors, setErrors] = useState([])

  useEffect(() => {
    let active = true
    Promise.allSettled([
      fetchBillingAccount(),
      fetchBillingUsage(),
      fetchLegalDocumentStats(),
      fetchLegalMatters(),
      fetchGovernanceOverview()
    ]).then(results => {
      if (!active) return
      const [account, usage, documents, matters, governance] = results
      setSnapshot({
        account: account.status === 'fulfilled' ? account.value : null,
        usage: usage.status === 'fulfilled' ? usage.value : null,
        documents: documents.status === 'fulfilled' ? documents.value : null,
        matters: matters.status === 'fulfilled' && Array.isArray(matters.value) ? matters.value.map(normalizeMatter) : null,
        governance: governance.status === 'fulfilled' ? governance.value : null
      })
      setErrors(results.filter(result => result.status === 'rejected').map(result => result.reason?.message || '接口请求失败'))
      setLoading(false)
    })
    return () => { active = false }
  }, [])

  const documents = snapshot.documents
  const usage = snapshot.usage
  const matters = snapshot.matters || []
  const activeMatters = useMemo(() => matters.filter(item => !['SIGNED_OFF', 'CLOSED', 'REJECTED'].includes(item.status)), [matters])
  const highRiskMatters = activeMatters.filter(item => item.priority === 'HIGH').length
  const pendingDocuments = documents ? Math.max(0, (documents.totalDocuments || 0) - (documents.successDocuments || 0) - (documents.failedDocuments || 0)) : null
  const metrics = [
    { label: '本期工作流运行', value: workflowRunCount(usage), tone: 'primary', hint: usage ? 'UTC 月度计费' : '后端未提供' },
    { label: '知识库文档', value: documents?.totalDocuments, tone: 'info', hint: documents ? '摄取统计' : '接口未启用' },
    { label: '已向量化文档', value: documents?.successDocuments, tone: 'success', hint: documents ? '摄取成功' : '接口未启用' },
    { label: '待处理事项', value: snapshot.matters ? activeMatters.length : null, tone: 'warning', hint: snapshot.matters ? '事项服务' : '接口未启用' },
    { label: '高优先级事项', value: snapshot.matters ? highRiskMatters : null, tone: 'error', hint: snapshot.matters ? '需人工关注' : '接口未启用' }
  ]

  return (
    <PageShell
      title="律所专业工作台"
      subtitle="事项来自服务端认证租户；治理与入库统计为当前服务诊断口径。法律分析结果须完成专业复核后使用。"
      action={<Stack direction="row" spacing={1}><Button variant="outlined" startIcon={<ForumRoundedIcon />} onClick={() => navigate('/chat')}>新建咨询</Button><Button variant="contained" startIcon={<AddRoundedIcon />} onClick={() => navigate('/contract-review')}>上传合同</Button></Stack>}
    >
      {loading && <LinearProgress />}
      {!loading && errors.length > 0 && <Alert severity="info">部分后端接口当前不可用，页面不会用演示数字替代真实数据。未接入的指标显示为“—”。</Alert>}
      {!loading && errors.length === 5 && <Alert severity="warning">后端数据服务尚未连接，当前是空的离线工作区；本页内容不会进入正式审计或案件记录。</Alert>}

      <Grid container spacing={1.5}>
        {metrics.map(metric => (
          <Grid item xs={6} sm={4} lg={2.4} key={metric.label}>
            <SectionCard sx={{ height: '100%' }}>
              <Typography variant="body2" color="text.secondary">{metric.label}</Typography>
              <Stack direction="row" spacing={1} alignItems="baseline" sx={{ mt: 0.5 }}>
                <Typography variant="h4" sx={{ fontWeight: 800 }}>{number(metric.value)}</Typography>
                <Typography variant="caption" color={`${metric.tone}.main`}>{metric.hint}</Typography>
              </Stack>
              <Box sx={{ mt: 1.25, height: 3, borderRadius: 1, bgcolor: `${metric.tone}.main`, opacity: 0.3 }} />
            </SectionCard>
          </Grid>
        ))}
      </Grid>

      <Grid container spacing={2} alignItems="stretch">
        <Grid item xs={12} lg={8}>
          <SectionCard title="待办事项" description="显示最近更新的在办事项；点击处理后查看当前阶段与下一步。" action={<Button size="small" endIcon={<ArrowForwardRoundedIcon />} onClick={() => navigate('/matters')}>查看全部</Button>}>
            {activeMatters.length === 0 ? <Typography variant="body2" color="text.secondary">{snapshot.matters ? '当前没有在办事项，可进入法律事项创建或查看已归档记录。' : '事项数据暂不可用，请刷新后重试。'}</Typography> : <Stack divider={<Box sx={{ borderBottom: '1px solid', borderColor: 'divider' }} />}>
              {activeMatters.slice(0, 6).map(task => {
                const [priority, priorityColor] = priorityMeta[task.priority] || [task.priority, 'default']
                const [status, statusColor] = statusMeta[task.status] || [statusLabels[task.status] || task.status, 'default']
                return <Stack key={task.id} direction={{ xs: 'column', md: 'row' }} spacing={1.25} alignItems={{ xs: 'stretch', md: 'center' }} sx={{ py: 1.35 }}>
                  <Box sx={{ flex: 1, minWidth: 0 }}><Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap" useFlexGap><Typography variant="subtitle2" sx={{ fontWeight: 800 }}>{task.title}</Typography><Chip size="small" label={`${priority}优先级`} color={priorityColor} variant="outlined" /></Stack><Typography variant="caption" color="text.secondary">{task.type} · 客户引用：{task.clientReference}</Typography></Box>
                  <Chip size="small" label={status} color={statusColor} /><Typography variant="caption" sx={{ minWidth: 130 }} color="text.secondary">{task.nextAction}</Typography><Button size="small" onClick={() => navigate('/matters')}>处理</Button>
                </Stack>
              })}
            </Stack>}
          </SectionCard>
        </Grid>
        <Grid item xs={12} lg={4}>
          <SectionCard title="企业运行状态" description="租户、计费和治理状态由服务端认证上下文确定。" sx={{ height: '100%' }}>
            <Stack spacing={1.25}>
              <Stack direction="row" justifyContent="space-between"><Typography variant="body2" color="text.secondary">企业账户</Typography><Typography variant="body2" sx={{ fontWeight: 800 }}>{snapshot.account?.legalEntityName || '未连接'}</Typography></Stack>
              <Stack direction="row" justifyContent="space-between"><Typography variant="body2" color="text.secondary">账户状态</Typography><Chip size="small" label={snapshot.account?.status || '未知'} color={snapshot.account ? 'success' : 'default'} /></Stack>
              <Stack direction="row" justifyContent="space-between"><Typography variant="body2" color="text.secondary">治理请求</Typography><Typography variant="body2" sx={{ fontWeight: 800 }}>{number(snapshot.governance?.totalRequests)}</Typography></Stack>
              <Stack direction="row" justifyContent="space-between"><Typography variant="body2" color="text.secondary">待处理文档</Typography><Typography variant="body2" sx={{ fontWeight: 800 }}>{number(pendingDocuments)}</Typography></Stack>
              <Alert severity="info" icon={<WarningAmberRoundedIcon />}>高风险结论、诉讼策略和期限计算必须由律师或授权人员复核。</Alert>
            </Stack>
          </SectionCard>
        </Grid>
      </Grid>

      <Grid container spacing={2} alignItems="stretch">
        <Grid item xs={12} md={5}><SectionCard title="最近咨询" action={<Button size="small" onClick={() => navigate('/chat')}>进入顾问</Button>}><Stack spacing={1.25}><Typography variant="body2" color="text.secondary">当前后端未提供会话列表查询接口。新建咨询后，记录会在具备会话存储和审计服务时显示。</Typography></Stack></SectionCard></Grid>
        <Grid item xs={12} md={7}><SectionCard title="快捷入口" description="围绕律务日常工作的高频操作"><Grid container spacing={1}>{[['合同审查', '上传合同并生成风险清单', DescriptionRoundedIcon, '/contract-review'], ['风险巡检', '执行已配置的企业风险规则', WarningAmberRoundedIcon, '/risk-inspection'], ['事项管理', '查看案件阶段、证据和期限', EventNoteRoundedIcon, '/matters'], ['企业知识库', '管理当前租户授权文档', MenuBookRoundedIcon, '/knowledge-base']].map(([label, desc, Icon, path]) => <Grid item xs={12} sm={6} key={label}><Button onClick={() => navigate(path)} fullWidth sx={{ p: 1.25, justifyContent: 'flex-start', textAlign: 'left', border: '1px solid', borderColor: 'divider', color: 'text.primary' }}><Icon sx={{ mr: 1, color: 'primary.main' }} /><Box><Typography variant="subtitle2" sx={{ fontWeight: 800 }}>{label}</Typography><Typography variant="caption" color="text.secondary">{desc}</Typography></Box></Button></Grid>)}</Grid></SectionCard></Grid>
      </Grid>
    </PageShell>
  )
}

export default function Dashboard() {
  const { context } = useWorkspaceAudience()
  return isClientAudience(context?.role) ? <ClientDashboard /> : <StaffDashboard />
}
