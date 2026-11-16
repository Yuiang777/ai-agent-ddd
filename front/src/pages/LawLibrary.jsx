import { Alert, Stack, Typography } from '@mui/material'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'

export default function LawLibrary() {
  return <PageShell title="法规与判例" subtitle="公共法律资料须核对法源版本、效力状态和原文，不能仅凭文档标题形成结论。">
    <SectionCard title="检索服务尚未接入" description="当前没有可供查询的公共法规与判例接口。">
      <Stack spacing={1.5}>
        <Alert severity="warning">暂不能搜索法规或判例；页面不会展示模拟法条，也不提供无法执行的搜索操作。</Alert>
        <Typography variant="body2" color="text.secondary">接入检索服务后，结果应显示可核对的来源、版本、效力状态及原文片段，并由专业人员复核法律适用。</Typography>
      </Stack>
    </SectionCard>
  </PageShell>
}
