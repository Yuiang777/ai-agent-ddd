import { useState } from 'react'
import { Alert, Button, InputAdornment, Stack, TextField } from '@mui/material'
import SearchRoundedIcon from '@mui/icons-material/SearchRounded'
import MenuBookRoundedIcon from '@mui/icons-material/MenuBookRounded'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'

export default function LawLibrary() {
  const [query, setQuery] = useState('')
  return <PageShell title="法规与判例" subtitle="公共法规和判例只能作为可核验依据；引用时必须展示版本、效力状态、来源签名和原文片段。" action={<Button variant="outlined" startIcon={<MenuBookRoundedIcon />} disabled>订阅新法提醒</Button>}>
    <SectionCard><TextField fullWidth placeholder="搜索法规名称、条文、案号或发布机关" value={query} onChange={event => setQuery(event.target.value)} InputProps={{ startAdornment: <InputAdornment position="start"><SearchRoundedIcon fontSize="small" /></InputAdornment> }} /><Stack direction="row" spacing={1} sx={{ mt: 1.25 }} /></SectionCard>
    <Alert severity="warning">法规与判例检索 API 尚未接入。{query ? `当前搜索词“${query}”尚未发送到后端。` : ''}页面不会展示固定法规样例，避免误把演示内容当作现行法律依据。</Alert>
    <SectionCard title="检索结果"><Alert severity="info">等待后端法规库、版本快照和来源签名服务配置。</Alert></SectionCard>
  </PageShell>
}
