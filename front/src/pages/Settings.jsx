import { useEffect, useState } from 'react'
import { Alert, Box, Button, Chip, Grid, LinearProgress, Stack, Typography } from '@mui/material'
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded'
import SectionCard from '../components/SectionCard'
import PageShell from '../components/PageShell'
import api from '../api/client'
import { errorMessage, getDocumentUploadCapabilities, unwrap } from '../api/matters'

const names = {
  mysql_configuration: '业务数据库', pgvector_retrieval: '法律知识检索', model_runtime: '模型调用',
  evidence_quality_gate: '证据质量门禁', tenant_knowledge_isolation: '认证与租户隔离',
  automation_release_gate: '自动化发布开关', matter_lifecycle_store: '事项存储', billing_and_entitlements: '计费与权益'
}
const labels = { READY: '服务端能力已提供', CONFIGURED_UNVERIFIED: '已配置，待验证', DISABLED: '未启用', PLANNED: '待接入', ARMED_UNVERIFIED: '开关已开启，待验证', OFFLINE_MEMORY: '离线内存', OFFLINE_MOCK: '离线模拟' }
const summaryLabels = { OFFLINE_SAFE: '离线验收环境', VALIDATION_REQUIRED: '环境待验证', CONTROLLED_RELEASE_READY: '发布配置已满足，仍需验收' }
const hints = {
  mysql_configuration: '数据库连接与迁移须在目标环境验证。', pgvector_retrieval: '依赖向量库、嵌入模型和检索评测。',
  model_runtime: '模型开关不代表模型可达或法律输出已验证。', evidence_quality_gate: '服务端提供检索诊断和人工复核路由。',
  tenant_knowledge_isolation: '须核验认证身份、事项权限和存储过滤的完整链路。', automation_release_gate: '法律交付仍须完成规定的人工复核和签署。',
  matter_lifecycle_store: '内存模式重启即丢失；SQL 模式须验证迁移、备份与恢复。', billing_and_entitlements: '离线模拟数据不能作为支付或账单凭据。'
}
function Row({ label, value }) {
  return <Stack direction={{ xs: 'column', sm: 'row' }} spacing={0.5} justifyContent="space-between"><Typography variant="body2" color="text.secondary">{label}</Typography><Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>{value || '未返回'}</Typography></Stack>
}
export default function Settings() {
  const [snapshot, setSnapshot] = useState({})
  const [loading, setLoading] = useState(true)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    setLoading(true)
    Promise.allSettled([
      api.get('/governance/readiness').then(unwrap),
      getDocumentUploadCapabilities(),
      api.get('/billing/account').then(unwrap)
    ]).then(results => {
      if (!active) return
      const next = {}
      ;['readiness', 'upload', 'billing'].forEach((key, index) => {
        const result = results[index]
        if (result.status === 'fulfilled') next[key] = result.value
        else next[`${key}Error`] = errorMessage(result.reason)
      })
      setSnapshot(next); setLoading(false)
    })
    return () => { active = false }
  }, [revision])
  const { readiness, upload, billing } = snapshot
  return <PageShell title="系统设置与环境" subtitle="查看服务端返回的环境信息、存储能力与接入状态。配置修改由服务端管理。" action={<Button variant="outlined" startIcon={<RefreshRoundedIcon />} disabled={loading} onClick={() => setRevision(value => value + 1)}>刷新状态</Button>}>
    {loading && <LinearProgress aria-label="正在加载环境状态" />}
    {snapshot.readinessError && <Alert severity="warning">环境诊断读取失败：{snapshot.readinessError}</Alert>}
    {readiness && <Alert severity={readiness.status === 'OFFLINE_SAFE' ? 'info' : 'warning'}>{summaryLabels[readiness.status] || readiness.status}。配置状态不能代替连接健康检查、权限验证或上线验收。</Alert>}
    <Grid container spacing={2}>
      <Grid item xs={12} lg={8}><SectionCard title="服务能力"><Stack spacing={2}>
        {readiness && <Row label="运行配置" value={readiness.activeProfiles?.join('、') || '未指定'} />}
        {!readiness && !loading && <Typography color="text.secondary">暂无环境能力信息，请刷新重试。</Typography>}
        {Object.entries(readiness?.capabilities || {}).map(([key, capability]) => <Box key={key} sx={{ p: 2, border: '1px solid', borderColor: 'divider', borderRadius: 2 }}><Stack spacing={1}>
          <Stack direction="row" spacing={1} justifyContent="space-between" alignItems="center" useFlexGap flexWrap="wrap"><Typography fontWeight={700}>{names[key] || key}</Typography><Chip size="small" label={labels[capability.status] || capability.status} color={capability.status === 'READY' ? 'success' : 'default'} /></Stack>
          <Typography variant="body2" color="text.secondary">{hints[key] || '以服务端返回状态和实际验证结果为准。'}</Typography>
          <Box component="details"><Typography component="summary" variant="caption" sx={{ cursor: 'pointer' }}>查看服务端诊断原文</Typography><Typography variant="caption" sx={{ display: 'block', mt: 1, overflowWrap: 'anywhere' }}>{capability.detail}</Typography></Box>
        </Stack></Box>)}
      </Stack></SectionCard></Grid>
      <Grid item xs={12} lg={4}><Stack spacing={2}>
        <SectionCard title="材料上传"><Stack spacing={1.5}>
          {snapshot.uploadError && <Alert severity="warning">上传能力读取失败：{snapshot.uploadError}</Alert>}
          {upload && <><Chip sx={{ alignSelf: 'flex-start' }} size="small" label={upload.enabled ? '已配置，提交时验证' : '当前不可用'} /><Typography variant="body2">{upload.reason}</Typography><Row label="支持格式" value={upload.allowedExtensions?.join('、')} /><Row label="单文件上限" value={`${(upload.maxFileBytes / 1024 / 1024).toFixed(1)} MB`} /><Row label="总请求上限" value={`${(upload.maxRequestBytes / 1024 / 1024).toFixed(1)} MB`} /></>}
        </Stack></SectionCard>
        <SectionCard title="计费账户"><Stack spacing={1.5}>
          {snapshot.billingError && <Alert severity="warning">账户读取失败：{snapshot.billingError}</Alert>}
          {billing && <><Row label="账户名称" value={billing.legalEntityName} /><Row label="币种" value={billing.currency} /><Row label="账户状态" value={billing.status === 'ACTIVE' ? '有效' : billing.status} /></>}
          <Typography variant="body2" color="text.secondary">套餐、用量、发票和支付入口将在接入对应服务后提供。</Typography>
        </Stack></SectionCard>
        <SectionCard title="成员与权限"><Alert severity="info">成员目录与角色配置页面尚未接入。当前操作权限由服务端身份上下文核验，浏览器不能设置租户或角色。</Alert></SectionCard>
      </Stack></Grid>
    </Grid>
  </PageShell>
}
