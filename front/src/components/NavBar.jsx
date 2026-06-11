import { AppBar, Toolbar, Typography, IconButton, Box, TextField, InputAdornment, Chip, Avatar } from '@mui/material'
import MenuIcon from '@mui/icons-material/Menu'
import SearchIcon from '@mui/icons-material/Search'
import GavelIcon from '@mui/icons-material/Gavel'

export default function NavBar({ onMenu }) {
  return (
    <AppBar position="sticky">
      <Toolbar sx={{ gap: 2 }}>
        <IconButton onClick={onMenu} edge="start" sx={{ mr: 2 }}>
          <MenuIcon />
        </IconButton>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Avatar sx={{ width: 28, height: 28, bgcolor: 'primary.main' }}>
            <GavelIcon sx={{ fontSize: 18 }} />
          </Avatar>
          <Box>
            <Typography variant="subtitle1">LexiCounsel</Typography>
            <Typography variant="caption" color="text.secondary">企业级法律顾问 Multi‑Agent 平台</Typography>
          </Box>
        </Box>
        <Box sx={{ flex: 1 }} />
        <TextField
          placeholder="搜索：合同条款 / 案例 / 风险点 / 客户"
          sx={{ width: { xs: 0, md: 460 }, display: { xs: 'none', md: 'block' } }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon fontSize="small" />
              </InputAdornment>
            )
          }}
        />
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Chip size="small" label="SLA: OK" sx={{ bgcolor: 'rgba(16,185,129,0.12)' }} />
          <Chip size="small" label="Gov: On" sx={{ bgcolor: 'rgba(30,58,138,0.10)' }} />
          <Avatar sx={{ width: 32, height: 32, bgcolor: 'grey.900' }}>A</Avatar>
        </Box>
      </Toolbar>
    </AppBar>
  )
}
