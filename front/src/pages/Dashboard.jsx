import { useEffect, useState } from 'react'
import { Grid, Card, CardContent, Typography, Button, Stack, Box, Chip, Divider } from '@mui/material'
import { useNavigate } from 'react-router-dom'
import { fetchGovernanceOverview } from '../api/client'
import GavelRoundedIcon from '@mui/icons-material/GavelRounded'
import PolicyRoundedIcon from '@mui/icons-material/PolicyRounded'
import FactCheckRoundedIcon from '@mui/icons-material/FactCheckRounded'
import BalanceRoundedIcon from '@mui/icons-material/BalanceRounded'
import AutoAwesomeRoundedIcon from '@mui/icons-material/AutoAwesomeRounded'
import PhotoCameraFrontRoundedIcon from '@mui/icons-material/PhotoCameraFrontRounded'

function Stat({ title, value }) {
  return (
    <Card>
      <CardContent>
        <Typography variant="body2" color="text.secondary">{title}</Typography>
        <Typography variant="h5" sx={{ mt: 0.5 }}>{value}</Typography>
      </CardContent>
    </Card>
  )
}

export default function Dashboard() {
  const navigate = useNavigate()
  const [overview, setOverview] = useState(null)

  useEffect(() => { fetchGovernanceOverview().then(setOverview) }, [])

  return (
    <Stack spacing={2}>
      <Card sx={{ overflow: 'hidden' }}>
        <Box
          sx={{
            p: { xs: 2.5, md: 3 },
            backgroundImage: 'linear-gradient(135deg, rgba(30,58,138,0.18), rgba(180,83,9,0.08))'
          }}
        >
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ xs: 'flex-start', md: 'center' }} justifyContent="space-between">
            <Box sx={{ maxWidth: 760 }}>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
                <Chip size="small" label="Multi‑Agent" sx={{ bgcolor: 'rgba(30,58,138,0.10)' }} />
                <Chip size="small" label="RAG + 多模态" sx={{ bgcolor: 'rgba(180,83,9,0.10)' }} />
                <Chip size="small" label="审计可追溯" sx={{ bgcolor: 'rgba(15,23,42,0.06)' }} />
              </Stack>
              <Typography variant="h4">企业级法律顾问平台</Typography>
              <Typography variant="body1" color="text.secondary" sx={{ mt: 1, lineHeight: 1.8 }}>
                面向合同审查、尽调、合规风控与诉讼策略的 Multi‑Agent 工作台：一处对话，统一调度专业 Agent、工具与多模态能力，产出可引用、可审计的结论。
              </Typography>
            </Box>
            <Stack direction="row" spacing={1}>
              <Button variant="contained" onClick={() => navigate('/chat')} startIcon={<AutoAwesomeRoundedIcon />}>开始咨询</Button>
              <Button variant="outlined" onClick={() => navigate('/multimodal')} startIcon={<PhotoCameraFrontRoundedIcon />}>上传材料</Button>
              <Button variant="outlined" onClick={() => navigate('/governance')} startIcon={<PolicyRoundedIcon />}>查看治理</Button>
            </Stack>
          </Stack>
        </Box>
        <Divider />
        <CardContent>
          <Grid container spacing={2}>
            <Grid item xs={12} md={3}><Stat title="总请求" value={overview?.totalRequests ?? '—'} /></Grid>
            <Grid item xs={12} md={3}><Stat title="成功请求" value={overview?.successRequests ?? '—'} /></Grid>
            <Grid item xs={12} md={3}><Stat title="失败请求" value={overview?.failedRequests ?? '—'} /></Grid>
            <Grid item xs={12} md={3}><Stat title="平均耗时(ms)" value={overview?.avgCostMs ?? '—'} /></Grid>
          </Grid>
        </CardContent>
      </Card>

      <Grid container spacing={2}>
        <Grid item xs={12} md={8}>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Agent 编队</Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                典型企业法律场景建议拆成多个专业 Agent 并行协作：先做风险识别与证据整理，再给结论与可落地建议。
              </Typography>
              <Grid container spacing={2}>
                {[
                  { icon: <GavelRoundedIcon />, title: '合同审查 Agent', desc: '条款风险、修改建议、对方要点' },
                  { icon: <FactCheckRoundedIcon />, title: '尽调 Agent', desc: '材料清单、核查点、红黄旗' },
                  { icon: <PolicyRoundedIcon />, title: '合规风控 Agent', desc: '合规要求、处罚风险、落地流程' },
                  { icon: <BalanceRoundedIcon />, title: '诉讼策略 Agent', desc: '案情要点、证据链、策略路径' }
                ].map(a => (
                  <Grid key={a.title} item xs={12} sm={6}>
                    <Card variant="outlined" sx={{ height: '100%' }}>
                      <CardContent>
                        <Stack direction="row" spacing={1.5} alignItems="center">
                          <AvatarChip icon={a.icon} />
                          <Box>
                            <Typography variant="subtitle1">{a.title}</Typography>
                            <Typography variant="body2" color="text.secondary">{a.desc}</Typography>
                          </Box>
                        </Stack>
                      </CardContent>
                    </Card>
                  </Grid>
                ))}
              </Grid>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={4}>
          <Card sx={{ mb: 2 }}>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>一键工作流</Typography>
              <Stack spacing={1.25}>
                <WorkflowItem title="合同红线扫描" desc="上传合同 → 提取条款 → 风险分级 → 修改建议" />
                <WorkflowItem title="尽调材料清单" desc="输入行业/标的 → 生成清单 → 缺失项提醒 → 风险点" />
                <WorkflowItem title="合规问答 + 引用" desc="问答 → RAG 检索 → 引用证据 → 审计留痕" />
              </Stack>
            </CardContent>
          </Card>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>体验入口</Typography>
              <Stack spacing={1}>
                <Button variant="outlined" fullWidth onClick={() => navigate('/tool-agent')}>工具 Agent（自动选工具）</Button>
                <Button variant="outlined" fullWidth onClick={() => navigate('/multimodal')}>多模态（图片/视频方案）</Button>
                <Button variant="outlined" fullWidth onClick={() => navigate('/governance')}>治理中心（审计/健康）</Button>
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Stack>
  )
}

function AvatarChip({ icon }) {
  return (
    <Box
      sx={{
        width: 38,
        height: 38,
        borderRadius: 2,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        bgcolor: 'rgba(30,58,138,0.10)',
        color: 'primary.main',
        border: '1px solid rgba(15, 23, 42, 0.10)'
      }}
    >
      {icon}
    </Box>
  )
}

function WorkflowItem({ title, desc }) {
  return (
    <Box sx={{ p: 1.25, borderRadius: 2, border: '1px solid rgba(15, 23, 42, 0.10)', bgcolor: 'rgba(255,255,255,0.62)' }}>
      <Typography variant="subtitle2">{title}</Typography>
      <Typography variant="body2" color="text.secondary">{desc}</Typography>
    </Box>
  )
}
