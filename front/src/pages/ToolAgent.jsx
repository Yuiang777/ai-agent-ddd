import { useState } from 'react'
import { Alert, Button, Card, CardContent, Stack, TextField, Typography } from '@mui/material'
import { callToolAgent } from '../api/client'

export default function ToolAgent() {
  const [clientId, setClientId] = useState('law-consultant')
  const [message, setMessage] = useState('')
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const submit = async () => {
    setLoading(true)
    setError('')
    setResult(null)
    try {
      const data = await callToolAgent({ clientId, message })
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
        工具 Agent 会使用当前 clientId 对应的后端配置进行动态装配。如果这个客户端绑定了 MCP 工具，模型会根据你的任务自动选择工具调用，并把工具结果整理成回答。
      </Alert>
      <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider' }}>
        <CardContent>
          <Stack spacing={2}>
            <TextField label="clientId" helperText="填写已在数据库配置的客户端 ID，例如 law-consultant" value={clientId} onChange={e => setClientId(e.target.value)} />
            <TextField label="任务消息" helperText="例如：帮我搜索今天的 AI 新闻并总结；或读取某个 MCP 工具可访问的数据" multiline minRows={4} value={message} onChange={e => setMessage(e.target.value)} />
            <Button variant="contained" onClick={submit} disabled={loading || !clientId || !message}>
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
