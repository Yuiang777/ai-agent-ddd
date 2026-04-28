import { Grid, Card, CardContent, Typography, Button, Stack } from '@mui/material'
import { useNavigate } from 'react-router-dom'

function Stat({ title, value }) {
  return (
    <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider' }}>
      <CardContent>
        <Typography variant="body2" color="text.secondary">{title}</Typography>
        <Typography variant="h5">{value}</Typography>
      </CardContent>
    </Card>
  )
}

export default function Dashboard() {
  const navigate = useNavigate()
  return (
    <Stack spacing={2}>
      <Grid container spacing={2}>
        <Grid item xs={12} md={3}><Stat title="已配置客户端" value="—" /></Grid>
        <Grid item xs={12} md={3}><Stat title="可用模型" value="—" /></Grid>
        <Grid item xs={12} md={3}><Stat title="MCP 工具" value="—" /></Grid>
        <Grid item xs={12} md={3}><Stat title="提示词与顾问" value="—" /></Grid>
      </Grid>
      <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider' }}>
        <CardContent>
          <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between">
            <div>
              <Typography variant="h5">开始配置与对话</Typography>
              <Typography variant="body2" color="text.secondary">进入对话页配置 MCP，并准备后续接入 Agent 对话接口</Typography>
            </div>
            <Button variant="contained" onClick={() => navigate('/chat')}>进入对话</Button>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  )
}
