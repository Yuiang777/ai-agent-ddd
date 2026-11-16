import { useEffect, useState } from 'react'
import { Alert, Box, Button, Grid, MenuItem, Stack, TextField, Typography } from '@mui/material'
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded'
import PageShell from '../components/PageShell'
import SectionCard from '../components/SectionCard'
import { callToolAgent, fetchGovernanceReadiness, modelAvailability } from '../api/client'
import { errorMessage } from '../api/matters'

export default function AgentStudio() {
  const [prompt, setPrompt] = useState('你是律所内部法律研究助手。分析前确认事实、授权、利益冲突和适用范围；结论须提供可核验来源并说明不确定性。高风险和诉讼事项须由律师复核。')
  const [test, setTest] = useState('请分析供应商合同中的单方解除条款，并指出所需事实与依据。')
  const [result, setResult] = useState('')
  const [testing, setTesting] = useState(false)
  const [testError, setTestError] = useState('')
  const [modelAvailable, setModelAvailable] = useState(false)
  useEffect(() => {
    let active = true
    fetchGovernanceReadiness().then(data => { if (active) setModelAvailable(modelAvailability(data) === 'configured') }).catch(() => {})
    return () => { active = false }
  }, [])

  const runTest = async () => {
    if (testing || !test.trim() || !modelAvailable) return
    setTesting(true)
    setTestError('')
    setResult('')
    try {
      const data = await callToolAgent({ clientId: 'law-consultant', message: test.trim() })
      if (typeof data?.content !== 'string' || !data.content.trim()) throw new Error('服务端未返回可显示文本')
      setResult(data.content)
    } catch (error) {
      setTestError(errorMessage(error))
    } finally { setTesting(false) }
  }

  return <PageShell title="流程配置草稿" subtitle="设计 Agent 行为、来源、工具与复核要求。此页配置尚不能保存或发布。">
    <Alert severity="info" sx={{ mb: 2 }}>当前未接入配置保存与发布接口。下方只用于整理设计草稿；右侧测试使用服务端已有 Agent，不会应用此处草稿。</Alert>
    {!modelAvailable && <Alert severity="warning">当前模型不可用或尚未确认，测试入口暂不可用；可继续整理设计草稿。</Alert>}
    <Grid container spacing={2}><Grid item xs={12} lg={7}><Stack spacing={2}>
      <SectionCard title="基本信息"><Grid container spacing={1.5}><Grid item xs={12} sm={6}><TextField fullWidth label="流程名称" placeholder="填写草稿名称" /></Grid><Grid item xs={12} sm={6}><TextField fullWidth select label="使用场景" defaultValue=""><MenuItem value="" disabled>请选择场景</MenuItem><MenuItem value="general">综合法律咨询</MenuItem><MenuItem value="contract">合同审查</MenuItem><MenuItem value="litigation">商事争议</MenuItem></TextField></Grid><Grid item xs={12}><TextField fullWidth label="职责说明" placeholder="说明业务边界、来源要求与复核责任" /></Grid></Grid></SectionCard>
      <SectionCard title="系统指令草稿" description="草稿用于讨论行为规范；身份、权限和模型凭据须由服务端管理。"><TextField fullWidth multiline minRows={8} value={prompt} onChange={event => setPrompt(event.target.value)} /></SectionCard>
      <SectionCard title="拟接入知识与工具" description="以下仅为设计讨论项，不代表服务已接入或启用。"><Stack spacing={0.75}><Typography variant="body2">公共法规库、律所知识库</Typography><Typography variant="body2">合同解析、条款定位、风险分析与人工复核</Typography><Typography variant="body2">外部检索须另行确定接入方式和授权范围</Typography></Stack><Alert severity="warning" sx={{ mt: 1.25 }}>无法核验来源时，应明确指出证据缺口，不得编造法律、判例或确定性策略。</Alert></SectionCard>
    </Stack></Grid><Grid item xs={12} lg={5}><SectionCard title="现有服务测试" description="调用服务端已配置 Agent。此测试不保存或应用左侧草稿。"><Stack spacing={1.5}><TextField fullWidth multiline minRows={4} label="测试问题" value={test} onChange={event => setTest(event.target.value)} /><Button variant="contained" startIcon={<PlayArrowRoundedIcon />} onClick={runTest} disabled={testing || !test.trim() || !modelAvailable}>{testing ? '测试中…' : '运行服务测试'}</Button>{testError && <Alert severity="error">{testError}</Alert>}{result && <Box sx={{ p: 1.5, bgcolor: '#F1F5F9', borderRadius: 1 }}><Typography variant="caption" color="text.secondary">服务端回复</Typography><Typography variant="body2" sx={{ mt: 0.75, whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>{result}</Typography></Box>}</Stack></SectionCard><SectionCard title="待落实的服务规则" description="这些规则需在服务端配置和审计，当前页面无法启用。"><Stack spacing={0.75}><Typography variant="body2">提供可核验法律依据；高风险进入人工复核</Typography><Typography variant="body2">诉讼报告须律师签署；记录模型与检索快照版本</Typography></Stack></SectionCard></Grid></Grid>
  </PageShell>
}
