import { AppBar, Avatar, Box, Chip, IconButton, Toolbar, Tooltip, Typography } from '@mui/material'
import MenuIcon from '@mui/icons-material/Menu'
import GavelIcon from '@mui/icons-material/Gavel'

const roles = { LAWYER: '律师工作台', PARALEGAL: '协作工作台', CLIENT: '客户服务门户', SYSTEM: '系统运维' }

export default function NavBar({ onMenu, audience }) {
  const { context, error, loading } = audience
  const role = context?.role
  const client = role === 'CLIENT' || !role
  const label = loading ? '正在确认身份' : error ? '身份待确认' : `${context.securityContextEnabled ? '' : '离线 · '}${roles[role] || role}`
  return (
    <AppBar position="sticky">
      <Toolbar sx={{ gap: 1.5, minHeight: { xs: 58, md: 68 } }}>
        <IconButton onClick={onMenu} edge="start" aria-label="展开或收起导航"><MenuIcon /></IconButton>
        <Avatar sx={{ width: 34, height: 34, bgcolor: 'primary.main', display: { xs: 'none', sm: 'flex' } }}><GavelIcon sx={{ fontSize: 18 }} /></Avatar>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.2 }}>法律事务管理系统</Typography>
          <Typography variant="caption" color="text.secondary">{client ? '客户服务门户' : '法务协作与审核平台'}</Typography>
        </Box>
        <Box sx={{ flex: 1 }} />
        <Tooltip title={context ? `当前身份：${context.actorId}；租户：${context.tenantId}。权限以服务端核验为准。` : error || '正在读取服务端身份'}>
          <Chip size="small" label={label} color={error || (context && !context.securityContextEnabled) ? 'warning' : 'default'} variant="outlined" sx={{ bgcolor: 'background.paper' }} />
        </Tooltip>
      </Toolbar>
    </AppBar>
  )
}
