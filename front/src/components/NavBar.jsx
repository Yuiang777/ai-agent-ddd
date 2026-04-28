import { AppBar, Toolbar, Typography, IconButton, Box } from '@mui/material'
import MenuIcon from '@mui/icons-material/Menu'

export default function NavBar({ onMenu }) {
  return (
    <AppBar elevation={0} color="transparent" position="static">
      <Toolbar sx={{ borderBottom: '1px solid', borderColor: 'divider' }}>
        <IconButton onClick={onMenu} edge="start" sx={{ mr: 2 }}>
          <MenuIcon />
        </IconButton>
        <Typography variant="h6" sx={{ flexGrow: 1 }}>AI Agent 控制台</Typography>
        <Box sx={{ display: 'flex', gap: 2 }}></Box>
      </Toolbar>
    </AppBar>
  )
}
