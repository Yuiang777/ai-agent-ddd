import { useEffect, useRef, useState } from 'react'
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Divider, Grid, LinearProgress, MenuItem, Stack, Tab, Tabs, TextField, Typography, useMediaQuery, useTheme } from '@mui/material'
import CloseRoundedIcon from '@mui/icons-material/CloseRounded'
import SectionCard from '../SectionCard'
import MatterMaterials from './MatterMaterials'
import { getMatter, getMatterContext, getTimeline, matterCommand, statusLabels, businessLabels, errorMessage, formatTime } from '../../api/matters'

const taskLabels = { PENDING: '待处理', IN_REVIEW: '审核中', COMPLETED: '已通过', RETURNED: '已退回', CANCELLED: '已取消' }
const clientStatusLabels = { INTAKE: '已提交', CONFLICT_CHECK: '信息确认中', ENGAGEMENT_PENDING: '委托确认中', MATERIAL_PENDING: '等待材料', ANALYSIS: '处理中', REVIEW_REQUIRED: '处理中', SIGNOFF_REQUIRED: '处理中', SIGNED_OFF: '已完成', CLOSED: '已归档', REJECTED: '已结束' }
export default function MatterDetail({ id, onClose, onChanged }) {
  const fullscreen = useMediaQuery(useTheme().breakpoints.down('sm'))
  const [view, setView] = useState(null)
  const [timeline, setTimeline] = useState(null)
  const [tab, setTab] = useState(0)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [context, setContext] = useState(null)
  const [contextError, setContextError] = useState('')
  const [draft, setDraft] = useState('')
  const [reportVersion, setReportVersion] = useState('')
  const [opinion, setOpinion] = useState('')
  const [reference, setReference] = useState('')
  const [activeTask, setActiveTask] = useState(null)
  const [compare, setCompare] = useState(false)
  const [confirmClose, setConfirmClose] = useState(false)
  const [archive, setArchive] = useState(false)
  const [archiveReason, setArchiveReason] = useState('')
  const [archiveReference, setArchiveReference] = useState('')
  const mounted = useRef(true)
  const load = async () => {
    setLoading(true); setError('')
    try {
      const data = await getMatter(id)
      if (!data?.matter?.id) throw new Error('事项数据异常')
      if (mounted.current) setView(data)
    } catch (failure) { if (mounted.current) setError(errorMessage(failure)) }
    finally { if (mounted.current) setLoading(false) }
  }
  useEffect(() => {
    let active = true
    getMatterContext().then(data => { if (active) setContext(data) }).catch(failure => { if (active) setContextError(errorMessage(failure)) })
    return () => { active = false }
  }, [])
  const canReview = ['LAWYER', 'PARALEGAL'].includes(context?.role)
  const canSign = context?.role === 'LAWYER'
  const isClientView = !canReview
  useEffect(() => { mounted.current = true; load(); return () => { mounted.current = false } }, [id])
  useEffect(() => {
    if (tab !== 3 || !canReview) return
    let cancelled = false
    setTimeline(null)
    getTimeline(id).then(data => { if (!cancelled) setTimeline(data) }).catch(failure => { if (!cancelled) setError(errorMessage(failure)) })
    return () => { cancelled = true }
  }, [tab, id, view, canReview])
  const command = async (path, payload, success) => {
    if (busy) return false
    setBusy(true); setError(''); setNotice('')
    try {
      const data = await matterCommand(id, path, payload)
      if (!data?.matter?.id) throw new Error('服务未返回更新后的事项')
      setView(data); onChanged(data); setNotice(success); return true
    } catch (failure) { setError(errorMessage(failure)); return false }
    finally { setBusy(false) }
  }
  const matter = view?.matter
  const artifacts = matter?.artifacts || []
  const latest = artifacts.at(-1)
  const selected = artifacts.find(item => String(item.version) === reportVersion) || latest
  const tasks = view?.reviewTasks || []
  const pending = tasks.filter(task => ['PENDING', 'IN_REVIEW'].includes(task.status))
  const bindingMatches = task => Boolean(view?.currentArtifactBinding && task.artifactBinding
    && task.artifactBinding.artifactVersion === view.currentArtifactBinding.artifactVersion
    && task.artifactBinding.artifactSha256 === view.currentArtifactBinding.artifactSha256
    && task.artifactBinding.evidenceFingerprint === view.currentArtifactBinding.evidenceFingerprint)
  const currentTasks = pending.filter(bindingMatches)
  const qualityComplete = tasks.some(task => task.type === 'QUALITY_REVIEW' && task.status === 'COMPLETED' && bindingMatches(task))
  const reviewRestriction = task => {
    const actor = context?.actorId?.trim()
    if (!actor) return '正在确认当前处理人身份。'
    if (actor === latest?.createdBy?.trim()) return '报告作者不能复核或签署自己的报告，请交由其他同事处理。'
    if (task.type === 'LAWYER_SIGNOFF' && tasks.some(review => review.type === 'QUALITY_REVIEW' && review.status === 'COMPLETED' && bindingMatches(review) && review.reviewerId?.trim() === actor)) return '质量复核人不能签署同一份报告，请交由另一位律师签署。'
    if (task.status === 'IN_REVIEW' && task.reviewerId?.trim() !== actor) return '此任务已由 ' + task.reviewerId + ' 开始处理。'
    return ''
  }
  const publish = async () => {
    if (await command('artifacts', { content: draft, expectedVersion: matter.version }, '报告新版本已登记，需要重新复核。')) { setDraft(''); setReportVersion('') }
  }
  const decide = async approved => {
    if (await command(`reviews/${encodeURIComponent(activeTask.id)}/decision`, { approved, opinion: opinion.trim(), reference: reference.trim() }, approved ? '审核决定已保存。' : '已退回补充材料。')) { setActiveTask(null); setOpinion(''); setReference('') }
  }
  const archiveMatter = async () => {
    if (await command('transitions', { target: 'CLOSED', reason: archiveReason.trim(), reference: archiveReference.trim(), expectedVersion: matter.version }, '事项已归档，归档记录已写入时间线。')) { setArchive(false); setArchiveReason(''); setArchiveReference('') }
  }
  const close = () => { if (busy) return; if (draft.trim() || opinion.trim()) setConfirmClose(true); else onClose() }
  const next = { INTAKE: ['开始冲突检查', 'CONFLICT_CHECK'], CONFLICT_CHECK: ['确认冲突检查完成', 'ENGAGEMENT_PENDING'], ENGAGEMENT_PENDING: ['确认委托并准备材料', 'MATERIAL_PENDING'], MATERIAL_PENDING: ['材料就绪，开始分析', 'ANALYSIS'] }[matter?.status]
  return <Dialog open fullWidth maxWidth="lg" fullScreen={fullscreen} onClose={close}>
    <DialogTitle><Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2}><Box sx={{ minWidth: 0, flex: 1, overflowWrap: 'anywhere' }}><Typography component="div" variant="h6" sx={{ fontWeight: 800 }}>{matter?.title || '事项详情'}</Typography><Typography variant="caption" color="text.secondary">{matter?.clientReference || id}</Typography></Box><Button aria-label="关闭事项详情" sx={{ flexShrink: 0, minWidth: 72, whiteSpace: 'nowrap' }} disabled={busy} onClick={close} startIcon={<CloseRoundedIcon />}>关闭</Button></Stack></DialogTitle>
    <DialogContent dividers sx={{ bgcolor: 'background.default' }}><Stack spacing={2}>
      {loading && <LinearProgress aria-label="正在加载事项详情" />}
      {error && <Alert severity="error" action={<Button onClick={load} disabled={busy}>刷新</Button>}>{error}。如为版本冲突，请刷新后重新检查；未提交内容会保留。</Alert>}
      {contextError && <Alert severity="warning">无法确认当前账户身份：{contextError}。请刷新页面或联系服务团队。</Alert>}
      {context && canReview && !canSign && <Alert severity="info">当前身份为法律助理。律师签署和期限核验需要律师角色，最终权限由服务端检查。</Alert>}
      {notice && <Alert severity="success" onClose={() => setNotice('')}>{notice}</Alert>}
      {matter && (isClientView ? <Stack spacing={2}>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap><Chip label={clientStatusLabels[matter.status] || '办理中'} color="primary" variant="outlined" /><Chip label={`事项 V${matter.version}`} /></Stack>
        <SectionCard title="事项进度" description="展示面向客户的办理状态；内部审核与工作记录仅供服务团队使用。"><Stack spacing={1.5}><Typography variant="h6">{matter.status === 'MATERIAL_PENDING' ? '请按服务团队沟通补充材料' : (['SIGNED_OFF', 'CLOSED'].includes(matter.status)) ? '您的服务已完成' : matter.status === 'REJECTED' ? '事项已结束' : '团队正在为您办理'}</Typography><Typography variant="body2" color="text.secondary">最近更新：{formatTime(matter.updatedAt)}</Typography></Stack></SectionCard>
        <SectionCard title="受理范围"><Stack spacing={1}><Typography variant="body2">业务类型：{businessLabels[view.scope?.businessType] || '综合法律服务'}</Typography><Typography variant="body2">相关法域：{view.scope?.relatedJurisdictions?.join('、') || '待与服务团队确认'}</Typography><Typography variant="body2">工作语言：{view.scope?.workingLanguages?.join('、') || '按沟通约定'}</Typography></Stack></SectionCard>
        {latest && <SectionCard title="已签署交付物"><Stack spacing={1.5}><Typography variant="caption" color="text.secondary">版本 V{latest.version} · {formatTime(latest.createdAt)}</Typography><Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{latest.content}</Typography></Stack></SectionCard>}
        {!latest && <Alert severity="info">交付文件将在完成审核并签署后显示。如需了解进度，请联系您的服务团队。</Alert>}
      </Stack> : <><Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap><Chip label={statusLabels[matter.status] || matter.status} color="primary" variant="outlined" /><Chip label={`事项 V${matter.version}`} /><Chip label={`${artifacts.length} 个报告版本`} /><Chip label={`${view.evidence?.length || 0} 条证据版本`} /></Stack>
      <Tabs value={tab} onChange={(_, value) => setTab(value)} variant="scrollable" allowScrollButtonsMobile><Tab label="办理概况" /><Tab label="报告与版本" /><Tab label={`审核记录 · ${tasks.length}`} /><Tab label="材料与时间线" /></Tabs>
      {tab === 0 && <Grid container spacing={2}><Grid item xs={12} md={7}><SectionCard title="下一步办理"><Stack spacing={2}><Typography variant="body2">{next ? '完成相应业务核验后确认进入下一阶段。' : matter.status === 'ANALYSIS' ? '完成分析后，在“报告与版本”登记交付物并提交复核。' : ['REVIEW_REQUIRED', 'SIGNOFF_REQUIRED'].includes(matter.status) ? '查看报告原文与审核记录，核对版本和材料后处理。' : matter.status === 'SIGNED_OFF' ? '律师签署已完成。核对交付和归档记录后，可由律师归档事项。' : matter.status === 'CLOSED' ? '事项已归档，可查看报告、材料和办理时间线。' : '此事项当前阶段没有可用的办理操作。'}</Typography>{next && <><TextField label="核验依据 / 记录编号" value={reference} onChange={event => setReference(event.target.value)} /><Button variant="contained" disabled={busy || !reference.trim()} onClick={() => command('transitions', { target: next[1], reason: next[0], reference: reference.trim(), expectedVersion: matter.version }, '事项阶段已更新。')}>{next[0]}</Button></>}{matter.status === 'SIGNED_OFF' && <Button variant="contained" disabled={busy || !canSign} onClick={() => setArchive(true)}>归档事项</Button>}<Alert severity="info">页面操作使用当前登录身份。最终权限、审核版本与状态由服务端核验。</Alert></Stack></SectionCard></Grid><Grid item xs={12} md={5}><SectionCard title="受理信息"><Stack spacing={1}><Typography variant="body2">相关法域：{view.scope?.relatedJurisdictions?.join('、') || '待明确'}</Typography><Typography variant="body2">工作语言：{view.scope?.workingLanguages?.join('、') || '待明确'}</Typography><Typography variant="body2">最近更新：{formatTime(matter.updatedAt)}</Typography><Typography variant="caption" color="text.secondary">法域登记不等同于适用法认定。</Typography></Stack></SectionCard></Grid></Grid>}
      {tab === 1 && <Stack spacing={2}><SectionCard title="交付物版本" description="每次发布保留独立版本；审核与具体版本绑定。"><Stack spacing={2}>{artifacts.length ? <><TextField select size="small" label="查看版本" value={String(selected?.version || '')} onChange={event => setReportVersion(event.target.value)}>{[...artifacts].reverse().map(item => <MenuItem key={item.version} value={String(item.version)}>V{item.version} · {formatTime(item.createdAt)}</MenuItem>)}</TextField><Typography variant="caption" sx={{ overflowWrap: 'anywhere' }}>SHA-256：{selected.sha256}</Typography><Box component="pre" sx={{ m: 0, p: 2, bgcolor: 'background.default', borderRadius: 1, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 380, overflow: 'auto', font: 'inherit', fontSize: 14 }}>{selected.content}</Box><Typography variant="caption" color="text.secondary">发布人：{selected.createdBy} · {formatTime(selected.createdAt)}</Typography>{artifacts.length > 1 && <><Button variant="outlined" onClick={() => setCompare(value => !value)}>{compare ? '收起版本对照' : '与最新版本并排对照'}</Button>{compare && <Grid container spacing={2}>{[selected.version === latest.version ? artifacts.at(-2) : selected, latest].map(item => <Grid item xs={12} md={6} key={item.version}><Typography variant="subtitle2">V{item.version}{item.version === latest.version ? ' · 最新版' : ''}</Typography><Box component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', p: 2, bgcolor: 'background.default', maxHeight: 350, overflow: 'auto', font: 'inherit', fontSize: 14 }}>{item.content}</Box></Grid>)}</Grid>}</>}</> : <Alert severity="info">尚未登记报告。完成分析后发布首版交付物。</Alert>}</Stack></SectionCard>
      {['ANALYSIS', 'REVIEW_REQUIRED', 'SIGNOFF_REQUIRED'].includes(matter.status) && <SectionCard title="登记报告新版本" description="当前支持文本交付物。发布新版本后，事项回到分析阶段，旧批准不能用于新版本。"><Stack spacing={2}><TextField multiline minRows={6} maxRows={16} label="报告正文" value={draft} onChange={event => setDraft(event.target.value)} inputProps={{ maxLength: 200000 }} helperText={`${draft.length.toLocaleString()} / 200,000 字符；关闭详情将放弃未发布内容。`} /><Button variant="contained" disabled={busy || !draft.trim()} onClick={publish}>{busy ? '正在提交…' : '发布为新版本'}</Button></Stack></SectionCard>}
      {latest && <SectionCard title="提交独立审核"><Stack spacing={1.5}><Typography variant="body2">提交最新报告 V{latest.version}，同时绑定当前已登记证据版本。请先查看完整正文。</Typography>{matter.status === 'ANALYSIS' && <Button variant="contained" disabled={busy} onClick={() => command('reviews', { taskType: 'QUALITY_REVIEW', expectedVersion: matter.version }, '质量复核任务已创建。')}>提交质量复核</Button>}{matter.status === 'SIGNOFF_REQUIRED' && <Button variant="contained" disabled={busy || !qualityComplete || currentTasks.some(task => task.type === 'LAWYER_SIGNOFF')} onClick={() => command('reviews', { taskType: 'LAWYER_SIGNOFF', expectedVersion: matter.version }, '律师签署任务已创建。')}>提交律师签署</Button>}<Typography variant="caption" color="text.secondary">报告或材料已变更时，服务端会拒绝过期批准。</Typography></Stack></SectionCard>}</Stack>}
      {tab === 2 && <Stack spacing={2}>{!tasks.length && <Alert severity="info">尚无审核记录，请先登记报告并提交复核。</Alert>}{[...tasks].reverse().map(task => <SectionCard key={task.id} title={task.type === 'LAWYER_SIGNOFF' ? '律师签署' : '质量复核'} action={<Chip size="small" label={taskLabels[task.status] || task.status} />}><Stack spacing={1.5}><Typography variant="body2">{task.artifactBinding ? `绑定报告 V${task.artifactBinding.artifactVersion}` : '历史任务，未绑定交付物，不能批准'}</Typography>{task.artifactBinding && !bindingMatches(task) && <Alert severity="warning">此任务绑定的报告或材料与当前版本不一致，不能用于当前批准。</Alert>}{task.artifactBinding && <Typography variant="caption" sx={{ overflowWrap: 'anywhere' }}>正文摘要：{task.artifactBinding.artifactSha256}</Typography>}<Typography variant="body2">审核人：{task.reviewerId || '待处理'} · {task.opinion || '尚无审核意见'}</Typography>{task.decisionReference && <Typography variant="body2">决定依据：{task.decisionReference}</Typography>}{['PENDING', 'IN_REVIEW'].includes(task.status) && reviewRestriction(task) && <Alert severity="info">{reviewRestriction(task)}</Alert>}{['PENDING', 'IN_REVIEW'].includes(task.status) && <Button variant="outlined" disabled={busy || Boolean(reviewRestriction(task)) || !canReview || (task.type === 'LAWYER_SIGNOFF' && !canSign) || !currentTasks.includes(task) || (task.type === 'LAWYER_SIGNOFF' ? matter.status !== 'SIGNOFF_REQUIRED' : matter.status !== 'REVIEW_REQUIRED')} onClick={() => { setOpinion(''); setReference(''); setActiveTask(task) }}>查看并处理审核</Button>}<Typography variant="caption" color="text.secondary">{task.id}</Typography></Stack></SectionCard>)}</Stack>}
      {<Stack spacing={2} sx={{ display: tab === 3 ? 'flex' : 'none' }}><MatterMaterials view={view} command={command} busy={busy} error={error} canVerify={canSign} /><SectionCard title="办理时间线">{timeline ? <Stack spacing={2}>{timeline.audit?.length ? timeline.audit.map(item => <Box key={item.eventId}><Typography variant="body2" sx={{ fontWeight: 700 }}>{item.eventType}</Typography><Typography variant="body2">{item.reason}</Typography><Typography variant="caption" color="text.secondary">{item.actorId} · {formatTime(item.occurredAt)}</Typography><Divider sx={{ mt: 1 }} /></Box>) : <Typography variant="body2">暂无审计记录。</Typography>}</Stack> : <Typography variant="body2">正在获取时间线；失败时请点击刷新。</Typography>}</SectionCard></Stack>}
      </>) }
    </Stack></DialogContent>
    <DialogActions><Button onClick={load} disabled={busy || loading}>刷新详情</Button><Button onClick={close} disabled={busy}>完成</Button></DialogActions>
    <Dialog open={archive} onClose={() => !busy && setArchive(false)} fullWidth maxWidth="sm"><DialogTitle>确认归档事项</DialogTitle><DialogContent><Stack spacing={2} sx={{ pt: 1 }}><Alert severity="warning">归档后无法在当前版本重新开启办理。请确认已完成交付，并填写可追溯的归档记录。</Alert><TextField required multiline minRows={2} label="归档说明" value={archiveReason} onChange={event => setArchiveReason(event.target.value)} /><TextField required label="归档记录编号" value={archiveReference} onChange={event => setArchiveReference(event.target.value)} />{error && <Alert severity="error">{error}</Alert>}</Stack></DialogContent><DialogActions><Button disabled={busy} onClick={() => setArchive(false)}>取消</Button><Button variant="contained" disabled={busy || !archiveReason.trim() || !archiveReference.trim()} onClick={archiveMatter}>确认归档</Button></DialogActions></Dialog>
    <Dialog open={confirmClose} onClose={() => setConfirmClose(false)}><DialogTitle>放弃未提交内容？</DialogTitle><DialogContent>报告正文或审核意见尚未提交，关闭后不会保留。</DialogContent><DialogActions><Button onClick={() => setConfirmClose(false)}>继续编辑</Button><Button color="warning" onClick={onClose}>放弃并关闭</Button></DialogActions></Dialog>
    <Dialog open={Boolean(activeTask)} onClose={() => !busy && setActiveTask(null)} fullWidth maxWidth="sm"><DialogTitle>{activeTask?.type === 'LAWYER_SIGNOFF' ? '律师签署决定' : '质量复核决定'}</DialogTitle><DialogContent><Stack spacing={2} sx={{ pt: 1 }}><Alert severity="warning">你将对报告 V{activeTask?.artifactBinding?.artifactVersion} 作出决定。请先核对完整正文、来源和证据。</Alert><TextField required multiline minRows={3} label="审核意见" value={opinion} onChange={event => setOpinion(event.target.value)} /><TextField label="决定依据 / 签署记录引用" required={activeTask?.type === 'LAWYER_SIGNOFF'} value={reference} onChange={event => setReference(event.target.value)} />{error && <Alert severity="error">{error}</Alert>}</Stack></DialogContent><DialogActions><Button onClick={() => setActiveTask(null)} disabled={busy}>取消</Button><Button color="warning" disabled={busy || !opinion.trim()} onClick={() => decide(false)}>退回补充</Button><Button variant="contained" disabled={busy || !opinion.trim() || (activeTask?.type === 'LAWYER_SIGNOFF' && !reference.trim())} onClick={() => decide(true)}>{busy ? '保存中…' : '确认通过'}</Button></DialogActions></Dialog>
  </Dialog>
}
