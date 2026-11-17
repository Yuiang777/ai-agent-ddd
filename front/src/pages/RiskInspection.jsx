import { Alert, Button, Grid, Typography } from '@mui/material'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'

export default function RiskInspection() {
  return <PageShell title="法律风险巡检" subtitle="巡检服务尚未接入，当前不能发起任务。" action={<Button variant="outlined" disabled>巡检未开放</Button>}>
    <Alert severity="warning">后端尚无巡检任务接口。此处不生成风险数量或结论；已有风险请在法律事项中登记，并由律师核查。</Alert>
    <Grid container spacing={2}><Grid item xs={12} md={4}><SectionCard title="风险总览"><Typography variant="h3" sx={{ fontWeight: 800 }}>—</Typography><Typography variant="body2" color="text.secondary">暂无可查询的巡检任务</Typography></SectionCard></Grid><Grid item xs={12} md={8}><SectionCard title="巡检结果" description="接入后应保留规则版本、证据来源和人工复核记录。"><Typography variant="body2" color="text.secondary">当前尚无任务查询接口，无法展示巡检结果。</Typography></SectionCard></Grid></Grid>
  </PageShell>
}
