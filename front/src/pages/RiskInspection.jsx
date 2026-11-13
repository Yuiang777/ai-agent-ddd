import { useState } from 'react'
import { Alert, Button, Grid, LinearProgress, Stack, Typography } from '@mui/material'
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded'
import TuneRoundedIcon from '@mui/icons-material/TuneRounded'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'

export default function RiskInspection() {
  const [running, setRunning] = useState(false)
  const run = async () => { setRunning(true); await new Promise(resolve => setTimeout(resolve, 500)); setRunning(false) }
  return <PageShell title="法律风险巡检" subtitle="巡检任务必须由后端规则引擎、企业知识库和审计服务共同驱动；AI 结果不能直接替代律师判断。" action={<Stack direction="row" spacing={1}><Button variant="outlined" startIcon={<TuneRoundedIcon />} disabled>巡检规则</Button><Button variant="contained" startIcon={<PlayArrowRoundedIcon />} onClick={run} disabled>运行巡检</Button></Stack>}>
    {running && <LinearProgress />}
    <Alert severity="warning">风险巡检后端接口尚未提供。当前页面不展示固定风险数量，也不会生成正式风险台账；配置规则引擎后才能运行可审计的巡检任务。</Alert>
    <Grid container spacing={2}><Grid item xs={12} md={4}><SectionCard title="风险总览"><Typography variant="h3" sx={{ fontWeight: 800 }}>—</Typography><Typography variant="body2" color="text.secondary">等待后端巡检任务返回</Typography></SectionCard></Grid><Grid item xs={12} md={8}><SectionCard title="巡检结果" description="结果需包含规则版本、文档快照、证据引用、风险等级和人工复核状态。"><Typography variant="body2" color="text.secondary">尚未连接巡检任务查询接口。运行按钮当前仅用于验证页面状态，不会伪造或写入风险结果。</Typography></SectionCard></Grid></Grid>
    <Alert severity="info">企业级接入建议：以事项/工作流 ID 关联巡检运行，服务端记录租户范围、规则版本、证据来源、模型版本和复核结论，审计记录不可物理删除。</Alert>
  </PageShell>
}
