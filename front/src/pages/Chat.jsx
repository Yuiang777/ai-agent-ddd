import { useEffect, useRef, useState } from 'react'
import { Alert, Avatar, Box, Button, Chip, Divider, IconButton, Paper, Stack, TextField, Tooltip, Typography } from '@mui/material'
import AddRoundedIcon from '@mui/icons-material/AddRounded'
import SendRoundedIcon from '@mui/icons-material/SendRounded'
import StopRoundedIcon from '@mui/icons-material/StopRounded'
import { useNavigate } from 'react-router-dom'
import api, { fetchGovernanceReadiness, modelAvailability } from '../api/client'
import { errorMessage, unwrap } from '../api/matters'

const session = () => ({ id: `local-${crypto.randomUUID()}`, title: '新建法律咨询', messages: [], draft: '' })
export default function Chat() {
  const navigate = useNavigate()
  const [sessions, setSessions] = useState(() => [session()])
  const [selectedId, setSelectedId] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [availability, setAvailability] = useState('checking')
  const abortRef = useRef(null)
  const bottomRef = useRef(null)
  const selected = sessions.find(item => item.id === selectedId) || sessions[0]
  const update = (id, transform) => setSessions(current => current.map(item => item.id === id ? transform(item) : item))
  useEffect(() => {
    let active = true
    fetchGovernanceReadiness().then(data => { if (active) setAvailability(modelAvailability(data)) })
      .catch(() => { if (active) setAvailability('unknown') })
    return () => { active = false; abortRef.current?.abort() }
  }, [])
  useEffect(() => { bottomRef.current?.scrollIntoView({ block: 'nearest' }) }, [selected.messages.length, loading])
  const newSession = () => {
    if (loading) return
    const created = session()
    setSessions(current => [created, ...current]); setSelectedId(created.id); setError('')
  }
  const submit = async () => {
    const text = selected.draft.trim()
    if (!text || loading || availability !== 'configured') return
    if (text.length > 20000) { setError('问题超过 20,000 字符，请整理后再发送。'); return }
    const id = selected.id
    const requestId = crypto.randomUUID()
    update(id, item => ({ ...item, title: item.messages.length ? item.title : text.slice(0, 32), draft: '', messages: [...item.messages, { id: requestId, role: 'user', text, status: 'sending' }] }))
    setLoading(true); setError('')
    const abort = new AbortController(); abortRef.current = abort
    try {
      const data = unwrap(await api.post('/chat', { message: text, conversationId: id }, { signal: abort.signal }))
      if (!data?.content?.trim()) throw new Error('服务未返回可显示内容')
      update(id, item => ({ ...item, messages: [...item.messages.map(message => message.id === requestId ? { ...message, status: 'sent' } : message), { id: crypto.randomUUID(), role: 'assistant', text: data.content }] }))
    } catch (failure) {
      const stopped = failure?.code === 'ERR_CANCELED'
      setError(stopped ? '已停止等待。服务端可能仍在处理；再次提交前请核对结果。' : `${errorMessage(failure)}。本次未获得完整回复，问题已恢复到输入框。`)
      update(id, item => ({ ...item, draft: text, messages: item.messages.map(message => message.id === requestId ? { ...message, status: stopped ? 'stopped' : 'failed' } : message) }))
    } finally { setLoading(false); abortRef.current = null }
  }
  return <Stack spacing={2}>
    <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}><Box><Typography variant="h5" fontWeight={800}>法律咨询</Typography><Typography variant="body2" color="text.secondary">整理事实并形成待核查的咨询草稿；正式结论须核对依据并完成专业复核。</Typography></Box><Button variant="outlined" disabled={loading} startIcon={<AddRoundedIcon />} onClick={newSession}>新建咨询</Button></Stack>
    {availability === 'checking' && <Alert severity="info">正在检查模型能力…</Alert>}
    {availability === 'disabled' && <Alert severity="warning">当前环境未启用模型，可填写本页草稿；自动咨询暂不可用。请在法律事项中登记人工报告并完成复核。</Alert>}
    {availability === 'unknown' && <Alert severity="warning">无法确认模型能力，请检查服务后刷新页面。</Alert>}
    {error && <Alert severity="error" onClose={() => setError('')}>{error}</Alert>}
    <Paper variant="outlined" sx={{ px: 1.5, py: 1.1, bgcolor: '#FAFBFC' }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 0.75, sm: 2 }} alignItems={{ xs: 'flex-start', sm: 'center' }}>
        <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700 }}>处理状态</Typography>
        <Chip size="small" label="当前为咨询草稿" variant="outlined" />
        <Chip size="small" label="来源未核验" color="warning" variant="outlined" />
        <Chip size="small" label="正式交付需律师审核" color="info" variant="outlined" />
      </Stack>
    </Paper>
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '220px minmax(0, 1fr) 280px' }, gap: 2 }}>
      <Paper variant="outlined" sx={{ p: 1.5, display: { xs: 'none', md: 'block' } }}><Stack spacing={1}><Typography variant="subtitle2" fontWeight={700}>本页咨询草稿</Typography><Typography variant="caption" color="text.secondary">离开或刷新页面后不保留。</Typography>{sessions.map(item => <Button key={item.id} variant={item.id === selected.id ? 'outlined' : 'text'} disabled={loading} onClick={() => { setSelectedId(item.id); setError('') }} sx={{ justifyContent: 'flex-start', textAlign: 'left', overflowWrap: 'anywhere' }}>{item.title}</Button>)}</Stack></Paper>
      <Paper variant="outlined" sx={{ minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Box sx={{ p: 2, borderBottom: '1px solid', borderColor: 'divider' }}><Typography fontWeight={700}>{selected.title}</Typography><Typography variant="caption" color="text.secondary">文本咨询 · 当前页面草稿</Typography></Box>
        <Stack spacing={2} sx={{ p: 2, minHeight: 280, maxHeight: { xs: 460, md: 540 }, overflowY: 'auto', flex: 1 }}>
          {!selected.messages.length && <Alert severity="info">先说明事实、相关法域、需要处理的问题及已有材料。此接口只返回文本，不提供独立的风险清单或可核验引用结构。</Alert>}
          {selected.messages.map(message => <Box key={message.id} sx={{ display: 'flex', gap: 1, justifyContent: message.role === 'user' ? 'flex-end' : 'flex-start', minWidth: 0 }}><Avatar sx={{ width: 28, height: 28, fontSize: 12, bgcolor: message.role === 'user' ? 'grey.700' : 'primary.main' }}>{message.role === 'user' ? '我' : '稿'}</Avatar><Box sx={{ p: 1.5, maxWidth: '85%', bgcolor: message.role === 'user' ? 'grey.100' : 'background.paper', border: '1px solid', borderColor: 'divider', borderRadius: 2, minWidth: 0 }}><Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', lineHeight: 1.8 }}>{message.text}</Typography>{message.role === 'assistant' && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>来源未核验 · 仅供内部讨论</Typography>}{['sending', 'failed', 'stopped'].includes(message.status) && <Chip size="small" sx={{ mt: 1 }} label={{ sending: '正在处理', failed: '未获回复', stopped: '已停止等待' }[message.status]} />}</Box></Box>)}
          {loading && <Typography variant="body2" color="text.secondary">正在等待服务端回复…</Typography>}<div ref={bottomRef} />
        </Stack>
        <Divider /><Box sx={{ p: 2 }}><TextField fullWidth multiline minRows={3} maxRows={8} label="事实与法律问题" placeholder="请输入事实、相关法域和需要分析的问题" value={selected.draft} disabled={loading} onChange={event => update(selected.id, item => ({ ...item, draft: event.target.value }))} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); submit() } }} />
          <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1} sx={{ mt: 1 }}><Typography variant="caption" color="text.secondary">{selected.draft.length} / 20,000 · Shift+Enter 换行</Typography>{loading ? <Tooltip title="停止等待回复"><IconButton aria-label="停止等待回复" color="error" onClick={() => abortRef.current?.abort()}><StopRoundedIcon /></IconButton></Tooltip> : <Button variant="contained" endIcon={<SendRoundedIcon />} disabled={!selected.draft.trim() || selected.draft.length > 20000 || availability !== 'configured'} onClick={submit}>发送问题</Button>}</Stack>
        </Box>
      </Paper>
      <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={2}><Typography fontWeight={700}>材料与复核</Typography><Typography variant="body2" color="text.secondary">咨询页不接收附件。需要处理合同或材料时，请使用对应业务入口。</Typography><Button variant="outlined" onClick={() => navigate('/contract-review')}>合同审查</Button><Button variant="outlined" onClick={() => navigate('/matters')}>事项与材料管理</Button><Divider /><Alert severity="info">本页草稿不属于正式交付物。报告版本和审核记录以事项服务保存的结果为准。</Alert></Stack></Paper>
    </Box>
  </Stack>
}