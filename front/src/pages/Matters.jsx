import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Alert, Box, Button, ButtonBase, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Grid, LinearProgress, MenuItem, Stack, Tab, Tabs, TextField, Typography, Pagination, useMediaQuery, useTheme } from '@mui/material'
import AddRoundedIcon from '@mui/icons-material/AddRounded'
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded'
import SearchRoundedIcon from '@mui/icons-material/SearchRounded'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'
import DataTable from '../components/DataTable'
import MatterDetail from '../components/matters/MatterDetail'
import { useWorkspaceAudience } from '../components/WorkspaceAudienceContext'
import { businessLabels, statusLabels, listMatters, createMatter, matterData, errorMessage, formatTime } from '../api/matters'

const blankForm = () => ({ title: '', clientReference: '', businessType: 'GENERAL_ADVISORY', priority: 'MEDIUM', jurisdictions: '', languages: '' })
export default function Matters() {
  const [searchParams, setSearchParams] = useSearchParams()
  const mobile = useMediaQuery(useTheme().breakpoints.down('sm'))
  const { context } = useWorkspaceAudience()
  const isStaff = ['LAWYER', 'PARALEGAL'].includes(context?.role)
  const clientStatus = { INTAKE: '已提交', CONFLICT_CHECK: '信息确认中', ENGAGEMENT_PENDING: '委托确认中', MATERIAL_PENDING: '等待材料', ANALYSIS: '处理中', REVIEW_REQUIRED: '处理中', SIGNOFF_REQUIRED: '处理中', SIGNED_OFF: '已完成', CLOSED: '已归档', REJECTED: '已结束' }
  const [page, setPage] = useState(1)
  const pageSize = 12
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [tab, setTab] = useState(0)
  const [business, setBusiness] = useState('ALL')
  const [selectedId, setSelectedId] = useState(searchParams.get('matter'))
  useEffect(() => { setSelectedId(searchParams.get('matter')) }, [searchParams])
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState(blankForm)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState('')
  const reload = async () => {
    setLoading(true); setError('')
    try {
      const data = await listMatters()
      if (!Array.isArray(data)) throw new Error('事项列表格式异常')
      setRows(data.map(matterData))
    } catch (failure) { setRows([]); setError(errorMessage(failure)) }
    finally { setLoading(false) }
  }
  useEffect(() => { reload() }, [])
  const pending = rows.filter(row => ['REVIEW_REQUIRED', 'SIGNOFF_REQUIRED'].includes(row.status)).length
  const visible = useMemo(() => rows.filter(row => {
    const matchesTab = tab === 0 || (tab === 1 ? (isStaff ? ['REVIEW_REQUIRED', 'SIGNOFF_REQUIRED'].includes(row.status) : !['SIGNED_OFF', 'CLOSED', 'REJECTED'].includes(row.status)) : ['SIGNED_OFF', 'CLOSED', ...(!isStaff ? ['REJECTED'] : [])].includes(row.status))
    const text = `${row.title} ${row.clientReference} ${(row.scope.relatedJurisdictions || []).join(' ')}`.toLowerCase()
    return matchesTab && (business === 'ALL' || row.scope.businessType === business) && text.includes(query.trim().toLowerCase())
  }), [rows, tab, business, query, isStaff])
  useEffect(() => { setPage(1) }, [query, business, tab])
  const pageCount = Math.max(1, Math.ceil(visible.length / pageSize))
  const currentPage = Math.min(page, pageCount)
  const pageRows = visible.slice((currentPage - 1) * pageSize, currentPage * pageSize)
  const save = async event => {
    event.preventDefault()
    if (saving) return
    setSaving(true); setSaveError('')
    const split = value => value.split(/[,，]/).map(item => item.trim()).filter(Boolean)
    try {
      const view = await createMatter({ title: form.title.trim(), clientReference: form.clientReference.trim(),
        attributes: { priority: form.priority }, scope: { businessType: form.businessType,
          relatedJurisdictions: split(form.jurisdictions), workingLanguages: split(form.languages) } })
      if (!view?.matter?.id) throw new Error('服务未返回事项编号')
      setRows(current => [matterData(view), ...current]); setOpen(false); setForm(blankForm()); setSelectedId(view.matter.id)
    } catch (failure) { setSaveError(errorMessage(failure)) }
    finally { setSaving(false) }
  }
  return <PageShell title={isStaff ? "法律事项" : "我的法律服务"} subtitle={isStaff ? "管理事项进度、交付版本和团队复核流程。" : "查看服务进度、受理范围与已签署交付物。"} action={<Stack direction="row" spacing={1}><Button startIcon={<RefreshRoundedIcon />} onClick={reload} disabled={loading}>刷新</Button><Button variant="contained" startIcon={<AddRoundedIcon />} onClick={() => { setSaveError(''); setOpen(true) }}>{isStaff ? '新建事项' : '提交服务申请'}</Button></Stack>}>
    <Grid container spacing={2}>{(isStaff ? [[rows.length, '管理中事项'], [pending, '待团队审核'], [rows.filter(row => row.status === 'MATERIAL_PENDING').length, '等待补充材料']] : [[rows.length, '我的服务事项'], [rows.filter(row => !['SIGNED_OFF', 'CLOSED', 'REJECTED'].includes(row.status)).length, '办理中'], [rows.filter(row => ['SIGNED_OFF', 'CLOSED'].includes(row.status)).length, '已完成']]).map(([value, label]) => <Grid item xs={12} sm={4} key={label}><SectionCard><Typography color="text.secondary" variant="body2">{label}</Typography><Typography variant="h4" sx={{ mt: 1, fontWeight: 800, color: 'primary.main' }}>{error || loading && !rows.length ? '—' : value}</Typography></SectionCard></Grid>)}</Grid>
    {error && <Alert severity="error" action={<Button onClick={reload} disabled={loading}>重试</Button>}>加载失败：{error}。事项列表已清空，请重试获取最新状态。</Alert>}
    <SectionCard title={isStaff ? "事项台账" : "服务事项"} description={isStaff ? "按业务和状态查找，查看交付物及内部复核进度。" : "按业务和状态查找，查看办理进度与已签署交付物。"}><Stack spacing={2}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}><TextField size="small" label="搜索事项、业务编号或法域" value={query} onChange={event => setQuery(event.target.value)} sx={{ flex: 1 }} InputProps={{ startAdornment: <SearchRoundedIcon sx={{ mr: 1 }} /> }} /><TextField size="small" select label="业务类型" value={business} onChange={event => setBusiness(event.target.value)} sx={{ minWidth: 200 }}><MenuItem value="ALL">全部业务</MenuItem>{Object.entries(businessLabels).map(([key, label]) => <MenuItem key={key} value={key}>{label}</MenuItem>)}</TextField></Stack>
      <Tabs value={tab} onChange={(_, value) => setTab(value)} variant="scrollable" allowScrollButtonsMobile><Tab label={`全部 · ${error ? '—' : rows.length}`} /><Tab label={isStaff ? `待团队审核 · ${error ? '—' : pending}` : `办理中 · ${error ? '—' : rows.filter(row => !['SIGNED_OFF', 'CLOSED', 'REJECTED'].includes(row.status)).length}`} /><Tab label={isStaff ? "已签署与归档" : "已完成"} /></Tabs>
      {loading && <LinearProgress aria-label="正在加载事项" />}
      {mobile && pageRows.length > 0 ? <Stack spacing={1.5}>{pageRows.map(row => <ButtonBase key={row.id} onClick={() => setSelectedId(row.id)} sx={{ display: 'block', width: '100%', p: 2, textAlign: 'left', border: '1px solid', borderColor: 'divider', borderRadius: 2, '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' } }}>
        <Stack spacing={1}><Typography component="div" fontWeight={700} sx={{ overflowWrap: 'anywhere' }}>{row.title}</Typography><Typography component="div" variant="caption" color="text.secondary">{row.clientReference}</Typography>
          <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap"><Chip size="small" label={isStaff ? statusLabels[row.status] || row.status : clientStatus[row.status] || '办理中'} /><Chip size="small" variant="outlined" label={`V${row.version}`} /></Stack>
          <Typography component="div" variant="body2">{businessLabels[row.scope.businessType] || '待分类'} · {row.scope.relatedJurisdictions?.join('、') || '法域待明确'}</Typography><Typography component="div" variant="caption" color="text.secondary">更新于 {formatTime(row.updatedAt)}</Typography>
        </Stack>
      </ButtonBase>)}</Stack> : <DataTable rows={pageRows} rowKey={row => row.id} emptyText={loading ? '正在加载…' : error ? '加载失败，请重试' : '暂无符合条件的事项'} columns={[
        { key: 'title', label: '事项名称', render: (value, row) => <Box><Button onClick={() => setSelectedId(row.id)} sx={{ p: 0, fontWeight: 700, textAlign: 'left' }}>{value}</Button><Typography variant="caption" display="block" color="text.secondary">{row.clientReference}</Typography></Box> },
        { key: 'scope', label: '业务 / 相关法域', render: value => <Box><Typography variant="body2">{businessLabels[value.businessType] || value.businessType || '待分类'}</Typography><Typography variant="caption" color="text.secondary">{value.relatedJurisdictions?.join(' · ') || '法域待明确'}</Typography></Box> },
        { key: 'status', label: '办理状态', render: value => <Chip size="small" label={isStaff ? statusLabels[value] || value : clientStatus[value] || '办理中'} color={isStaff && ['REVIEW_REQUIRED', 'SIGNOFF_REQUIRED'].includes(value) ? 'warning' : ['SIGNED_OFF', 'CLOSED'].includes(value) ? 'success' : 'default'} /> },
        { key: 'updatedAt', label: '最近更新', render: formatTime }, { key: 'version', label: '版本', render: value => `V${value}` }
      ]} />}
      {visible.length > pageSize && <Stack spacing={1} alignItems="center"><Pagination aria-label="事项列表分页" count={pageCount} page={currentPage} onChange={(_, value) => setPage(value)} size={mobile ? 'small' : 'medium'} siblingCount={mobile ? 0 : 1} /><Typography variant="caption" color="text.secondary">共 {visible.length} 条匹配事项，每页 {pageSize} 条</Typography></Stack>}
      <Typography variant="caption" color="text.secondary">筛选范围为当前已加载事项。相关法域仅作受理信息，适用法需由服务团队确认。</Typography>
    </Stack></SectionCard>
    <Dialog open={open} onClose={() => !saving && setOpen(false)} fullWidth maxWidth="sm"><Box component="form" onSubmit={save}><DialogTitle>新建法律事项</DialogTitle><DialogContent><Stack spacing={2} sx={{ pt: 1 }}>
      <TextField autoFocus required label="事项名称" value={form.title} onChange={event => setForm({ ...form, title: event.target.value })} /><TextField required label="客户 / 业务编号" value={form.clientReference} onChange={event => setForm({ ...form, clientReference: event.target.value })} />
      <TextField select label="业务类型" value={form.businessType} onChange={event => setForm({ ...form, businessType: event.target.value })}>{Object.entries(businessLabels).map(([key, label]) => <MenuItem key={key} value={key}>{label}</MenuItem>)}</TextField>
      <TextField label="相关法域引用" placeholder="CN, US-NY" helperText="多个引用用逗号分隔，可暂留空。" value={form.jurisdictions} onChange={event => setForm({ ...form, jurisdictions: event.target.value })} /><TextField label="工作语言" placeholder="zh-CN, en" value={form.languages} onChange={event => setForm({ ...form, languages: event.target.value })} />
      <TextField select label="优先级" value={form.priority} onChange={event => setForm({ ...form, priority: event.target.value })}>{[['LOW', '普通'], ['MEDIUM', '中等'], ['HIGH', '紧急']].map(([key, label]) => <MenuItem key={key} value={key}>{label}</MenuItem>)}</TextField>
      {saveError && <Alert severity="error">{saveError}。事项尚未创建，填写内容已保留。</Alert>}
    </Stack></DialogContent><DialogActions><Button onClick={() => setOpen(false)} disabled={saving}>取消</Button><Button type="submit" variant="contained" disabled={saving || !form.title.trim() || !form.clientReference.trim()}>{saving ? '正在创建…' : '创建事项'}</Button></DialogActions></Box></Dialog>
    {selectedId && <MatterDetail id={selectedId} onClose={() => { setSelectedId(null); if (searchParams.has('matter')) { const next = new URLSearchParams(searchParams); next.delete('matter'); setSearchParams(next, { replace: true }) } }} onChanged={view => setRows(current => current.map(row => row.id === view.matter.id ? matterData(view) : row))} />}
  </PageShell>
}
