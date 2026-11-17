import { useEffect, useState } from 'react'
import { Alert, Button, Card, CardContent, Stack, TextField, Typography } from '@mui/material'
import { callToolAgent, fetchGovernanceReadiness, modelAvailability } from '../api/client'

export default function ToolAgent() {
  const [clientId, setClientId] = useState('law-consultant')
  const [message, setMessage] = useState('')
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const [availability, setAvailability] = useState('checking')
  useEffect(() => {
    let active = true
    fetchGovernanceReadiness().then(data => { if (active) setAvailability(modelAvailability(data)) })
      .catch(() => { if (active) setAvailability('unknown') })
    return () => { active = false }
  }, [])
  const submit = async () => {
    if (loading || availability !== 'configured' || !clientId.trim() || !message.trim()) return
    setLoading(true)
    setError('')
    setResult(null)
    try {
      const data = await callToolAgent({ clientId, message })
      if (typeof data?.content !== 'string' || !data.content.trim()) throw new Error('服务未返回有效结果')
      setResult(data)
    } catch (e) {
      setError(e.message || '调用失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h5">工具调用 Agent</Typography>
      <Alert severity="info">
        此页用于技术人员验证已配置的工具助手。工具范围由服务端配置，结果用于辅助研究，正式报告请登记到法律事项并完成复核。
      </Alert>
      {availability !== 'configured' && <Alert severity="warning">{availability === 'disabled' ? '当前环境未启用模型，工具调用不可用。' : availability === 'checking' ? '正在确认模型配置…' : '无法确认模型配置，请刷新后重试。'}</Alert>}
      <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider' }}>
        <CardContent>
          <Stack spacing={2}>
            <TextField label="clientId" helperText="填写已在数据库配置的客户端 ID，例如 law-consultant" value={clientId} onChange={e => setClientId(e.target.value)} />
            <TextField label="任务消息" helperText="例如：检索已授权知识库中的合同条款并列出来源；请勿提交未经授权的案件资料" multiline minRows={4} value={message} onChange={e => setMessage(e.target.value)} />
            <Button variant="contained" onClick={submit} disabled={loading || availability !== 'configured' || !clientId.trim() || !message.trim()}>
              {loading ? '执行中...' : '调用工具 Agent'}
            </Button>
          </Stack>
        </CardContent>
      </Card>
      {error ? <Alert severity="error">{error}</Alert> : null}
      {result ? (
        <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider' }}>
          <CardContent>
            <Typography variant="body2" color="text.secondary">clientId</Typography>
            <Typography sx={{ mb: 2 }}>{result.clientId}</Typography>
            <Typography variant="body2" color="text.secondary">耗时(ms)</Typography>
            <Typography sx={{ mb: 2 }}>{result.costMs}</Typography>
            <Typography variant="body2" color="text.secondary">结果</Typography>
            <Typography sx={{ whiteSpace: 'pre-wrap' }}>{result.content}</Typography>
          </CardContent>
        </Card>
      ) : null}
    </Stack>
  )
}
