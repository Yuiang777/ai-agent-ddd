import { Alert, Button, Stack } from '@mui/material'
import AddRoundedIcon from '@mui/icons-material/AddRounded'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'

export default function Templates() {
  return <PageShell title="模板中心" subtitle="合同、报告和法律文书模板应由后端版本库、权限和审批流统一管理。" action={<Button variant="contained" startIcon={<AddRoundedIcon />} disabled>新建模板</Button>}>
    <Alert severity="warning">模板目录、版本和使用权限 API 尚未接入。当前不展示固定模板样例，也不会把浏览器中的草稿当作企业正式模板。</Alert>
    <SectionCard title="模板目录"><Alert severity="info">等待模板服务接入后显示当前认证租户可见模板。正式模板应绑定版本、适用法域、审批记录和生效范围。</Alert></SectionCard>
  </PageShell>
}
