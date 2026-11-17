import { useEffect, useState } from 'react'
import { Alert, Button, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useNavigate } from 'react-router-dom'
import SectionCard from '../SectionCard'
import { errorMessage, getMatter, listMatters, matterCommand, statusLabels } from '../../api/matters'

export default function ReportRegistration({ content, workflowRunId, initialMatterId = '' }) {
  const navigate = useNavigate()
  const [matters, setMatters] = useState([])
  const [selected, setSelected] = useState(initialMatterId)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(null)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    setLoading(true); setError('')
    listMatters().then(data => {
      if (!Array.isArray(data)) throw new Error('事项列表格式异常')
      if (active) setMatters(data.filter(item => ['ANALYSIS', 'REVIEW_REQUIRED', 'SIGNOFF_REQUIRED'].includes(item.matter?.status)))
    }).catch(failure => { if (active) setError(errorMessage(failure)) }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [revision])
  useEffect(() => { setSaved(null) }, [content, workflowRunId])
  const save = async () => {
    if (busy || !selected || !content?.trim() || !workflowRunId?.trim()) return
    setBusy(true); setError('')
    try {
      const baseline = matters.find(item => item.matter.id === selected)?.matter
      if (!baseline) throw new Error('请刷新列表并重新选择目标事项')
      const current = await getMatter(selected)
      if (current?.matter?.version !== baseline.version) throw new Error('目标事项在列表加载后已变更，本次未登记。请打开事项核对最新报告和材料，再刷新列表重新确认')
      if (!['ANALYSIS', 'REVIEW_REQUIRED', 'SIGNOFF_REQUIRED'].includes(current?.matter?.status)) throw new Error('该事项当前阶段不允许发布报告，请刷新列表并重新选择')
      const data = await matterCommand(selected, 'artifacts', { content, workflowRunId: workflowRunId.trim(), expectedVersion: baseline.version })
      if (!data?.matter?.artifacts?.length) throw new Error('服务未返回报告版本，请先核对事项记录')
      setSaved({ id: data.matter.id, version: data.matter.artifacts.at(-1).version })
    } catch (failure) { setError(errorMessage(failure)) }
    finally { setBusy(false) }
  }
  return <SectionCard title="登记报告并进入事项复核" description="确认完整正文后保存到事项的报告版本，再到事项详情提交质量复核。">
    <Stack spacing={2}>
      <Alert severity="warning">发布新版本会使旧版本批准失效。登记时将检查所选事项的版本；如期间有变更，需要核对后刷新重新确认。工作流完成或产生待复核提示，不等于已经创建事项审核任务。</Alert>
      {!workflowRunId?.trim() && <Alert severity="error">运行编号缺失，本次报告不能登记；请重新执行并核对服务响应。</Alert>}
      {saved ? <Alert severity="success" action={<Button onClick={() => navigate(`/matters?matter=${encodeURIComponent(saved.id)}`)}>打开事项</Button>}>报告 V{saved.version} 已保存到事项，尚待提交复核。</Alert> : <>
        <TextField select label="目标法律事项" value={matters.some(item => item.matter.id === selected) ? selected : ''} disabled={loading || busy} onChange={event => setSelected(event.target.value)}>
          {matters.map(item => <MenuItem key={item.matter.id} value={item.matter.id}>{item.matter.title} · {statusLabels[item.matter.status] || item.matter.status} · 事项 V{item.matter.version}</MenuItem>)}
        </TextField>
        {!loading && !matters.length && <Typography variant="body2" color="text.secondary">暂无处于分析或审核阶段的事项。请先创建事项并完成受理核验。</Typography>}
        <Stack direction="row" spacing={1}><Button variant="contained" disabled={loading || busy || !content?.trim() || !workflowRunId?.trim() || content.length > 200000 || !matters.some(item => item.matter.id === selected)} onClick={save}>{busy ? '正在登记…' : '确认登记为报告新版本'}</Button><Button disabled={loading || busy} onClick={() => setRevision(value => value + 1)}>刷新事项</Button></Stack>
        {content.length > 200000 && <Alert severity="warning">正文超过当前报告版本的 200,000 字符上限，请整理后在事项详情登记。</Alert>}
      </>}
      {error && <Alert severity="error">{error}。报告内容仍保留在当前页面，请核对事项状态后重试。</Alert>}
    </Stack>
  </SectionCard>
}
