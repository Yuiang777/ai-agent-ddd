import { useEffect, useState } from 'react'
import { Alert, Button, Chip, Grid, MenuItem, Stack, TextField, Typography } from '@mui/material'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'
import { callMultimodal, fetchMultimodalCapabilities, fetchMultimodalTasks, fetchGovernanceReadiness, modelAvailability } from '../api/client'

const taskTypes = [
  { value: 'image_recognition', label: '图片识别（MiniMax MCP）' },
  { value: 'image_generation', label: '图片提示词生成' },
  { value: 'video_generation', label: '视频生成方案' },
  { value: 'video_recognition', label: '视频理解（方案生成）' }
]

const maxUploadSize = 20 * 1024 * 1024
const allowedUploadTypes = ['image/png', 'image/jpeg', 'image/webp', 'video/mp4', 'video/quicktime', 'video/x-msvideo']
const uploadAccept = allowedUploadTypes.join(',')

function validateFile(file) {
  if (!file) return ''
  if (file.size > maxUploadSize) return '文件不能超过 20MB'
  if (file.type && !allowedUploadTypes.includes(file.type)) return '只支持 PNG/JPEG/WebP 图片和 MP4/MOV/AVI 视频'
  return ''
}

const uploadRequiredTaskTypes = ['image_recognition', 'video_recognition']

function validateTask(taskType, file) {
  if (uploadRequiredTaskTypes.includes(taskType) && !file) return '识别类任务需要上传图片或视频文件'
  return ''
}

export default function Multimodal() {
  const [clientId, setClientId] = useState('law-consultant')
  const [taskType, setTaskType] = useState('image_recognition')
  const [prompt, setPrompt] = useState('请识别这张图片，提取可见文字、主体内容、关键风险点，并给出法律分析需要关注的信息。')
  const [file, setFile] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [tasks, setTasks] = useState([])
  const [capabilities, setCapabilities] = useState([])
  const [availability, setAvailability] = useState('checking')
  const [loadError, setLoadError] = useState('')

  const refresh = async () => {
    const results = await Promise.allSettled([fetchMultimodalTasks(), fetchMultimodalCapabilities(), fetchGovernanceReadiness()])
    setTasks(results[0].status === 'fulfilled' && Array.isArray(results[0].value) ? results[0].value : [])
    setCapabilities(results[1].status === 'fulfilled' && Array.isArray(results[1].value) ? results[1].value : [])
    setAvailability(results[2].status === 'fulfilled' ? modelAvailability(results[2].value) : 'unknown')
    setLoadError(results.some(item => item.status === 'rejected') ? '部分环境信息加载失败，请刷新重试；空白列表不能视为没有数据。' : '')
  }

  useEffect(() => { refresh() }, [])

  const submit = async () => {
    if (submitting || availability !== 'configured') return
    setSubmitting(true)
    setError('')
    setResult(null)
    try {
      const fileError = validateFile(file)
      if (fileError) throw new Error(fileError)
      const taskError = validateTask(taskType, file)
      if (taskError) throw new Error(taskError)
      const data = await callMultimodal({ clientId, taskType, prompt, file })
      if (!data?.taskId || !['success', 'failed', 'running'].includes(data.status)) throw new Error('服务返回的任务结果不完整')
      setResult(data)
      if (data?.status === 'failed') {
        setError(data.errorMessage || '任务执行失败')
      }
      await refresh()
    } catch (e) {
      setError(e.message || '提交失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <PageShell title="多模态工作台" subtitle="图片理解、视频理解方案和生成提示词统一入口。">
      <Alert severity="info">识别结果用于辅助核对材料，需与原件人工比对。视频理解与生成类任务当前返回分析方案或提示词；素材按临时文件处理，不会自动登记为事项证据。</Alert>
      {loadError && <Alert severity="warning" action={<Button onClick={refresh}>重试</Button>}>{loadError}</Alert>}
      {availability !== 'configured' && <Alert severity="warning">{availability === 'disabled' ? '当前环境未启用模型，多模态任务不可用。' : availability === 'checking' ? '正在确认模型配置…' : '无法确认模型配置，暂不能提交任务。'}</Alert>}

      <Grid container spacing={2.5} alignItems="flex-start">
        <Grid item xs={12} lg={7.5}>
          <Stack spacing={2}>
            <SectionCard title="提交任务" description="选择任务类型并上传对应素材，识别类任务需要文件。">
              <Grid container spacing={2}>
                <Grid item xs={12} md={6}>
                  <TextField label="模型客户端" helperText="实际使用服务端配置的客户端" disabled value={clientId} onChange={(e) => setClientId(e.target.value)} fullWidth />
                </Grid>
                <Grid item xs={12} md={6}>
                  <TextField select label="任务类型" value={taskType} onChange={(e) => setTaskType(e.target.value)} fullWidth>
                    {taskTypes.map(t => <MenuItem key={t.value} value={t.value}>{t.label}</MenuItem>)}
                  </TextField>
                </Grid>
                <Grid item xs={12}>
                  <TextField label="提示词" multiline minRows={4} value={prompt} onChange={(e) => setPrompt(e.target.value)} fullWidth />
                </Grid>
                <Grid item xs={12} md={7}>
                  <Button variant="outlined" component="label" fullWidth>
                    {file ? `已选择：${file.name}` : '选择文件'}
                    <input hidden type="file" accept={uploadAccept} onChange={(e) => {
                      const nextFile = e.target.files?.[0] || null
                      const fileError = validateFile(nextFile)
                      setFile(fileError ? null : nextFile)
                      setError(fileError)
                    }} />
                  </Button>
                </Grid>
                <Grid item xs={12} md={5}>
                  <Button variant="contained" onClick={submit} disabled={submitting || availability !== 'configured' || !clientId || !taskType} fullWidth>{submitting ? '提交中...' : '提交任务'}</Button>
                </Grid>
                {file ? <Grid item xs={12}><Chip label={`${file.name} · ${(file.size / 1024 / 1024).toFixed(2)}MB`} /></Grid> : null}
              </Grid>
            </SectionCard>

            {error ? <Alert severity="error">{error}</Alert> : null}
            {result ? (
              <SectionCard title="任务结果" description={`taskId：${result.taskId || '—'} · status：${result.status || '—'}`}>
                <Typography sx={{ whiteSpace: 'pre-wrap', lineHeight: 1.75 }}>{result.result || result.errorMessage || '—'}</Typography>
              </SectionCard>
            ) : null}
          </Stack>
        </Grid>

        <Grid item xs={12} lg={4.5}>
          <Stack spacing={2}>
            <SectionCard title="模型能力" description="能力定义清单；列出能力不代表外部服务已连通。">
              <Stack spacing={1.25}>
                {capabilities.map(c => (
                  <SectionCard key={c.capabilityId} sx={{ borderRadius: 2 }}>
                    <Typography variant="subtitle2" sx={{ fontWeight: 800 }}>{c.capabilityName}</Typography>
                    <Typography variant="body2" color="text.secondary">{c.modalities}</Typography>
                    <Typography variant="body2" sx={{ mt: 0.75 }}>{c.description}</Typography>
                  </SectionCard>
                ))}
              </Stack>
            </SectionCard>

            <SectionCard title="最近任务" description="当前进程的近期诊断记录，尚未按租户隔离；素材按临时文件处理。">
              <Stack spacing={1.25} sx={{ maxHeight: 520, overflow: 'auto', pr: 0.5 }}>
                {tasks.map(t => (
                  <SectionCard key={t.taskId} sx={{ borderRadius: 2 }}>
                    <Typography variant="subtitle2" sx={{ fontWeight: 800 }}>{t.taskType}</Typography>
                    <Typography variant="body2" color="text.secondary">{t.status}</Typography>
                    <Typography variant="body2" sx={{ mt: 0.75, whiteSpace: 'pre-wrap' }}>{t.result || t.errorMessage || t.prompt}</Typography>
                  </SectionCard>
                ))}
              </Stack>
            </SectionCard>
          </Stack>
        </Grid>
      </Grid>
    </PageShell>
  )
}
