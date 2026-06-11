import { Drawer, List, ListItemButton, ListItemIcon, ListItemText, Toolbar, Box, Typography, Divider } from '@mui/material'
import { useNavigate, useLocation } from 'react-router-dom'
import DashboardRoundedIcon from '@mui/icons-material/DashboardRounded'
import ForumRoundedIcon from '@mui/icons-material/ForumRounded'
import HandymanRoundedIcon from '@mui/icons-material/HandymanRounded'
import PhotoCameraFrontRoundedIcon from '@mui/icons-material/PhotoCameraFrontRounded'
import VerifiedUserRoundedIcon from '@mui/icons-material/VerifiedUserRounded'
import AccountTreeRoundedIcon from '@mui/icons-material/AccountTreeRounded'

const items = [
  { label: '工作台', path: '/dashboard', icon: <DashboardRoundedIcon fontSize="small" /> },
  { label: '工作流', path: '/workflow', icon: <AccountTreeRoundedIcon fontSize="small" /> },
  { label: '法律对话', path: '/chat', icon: <ForumRoundedIcon fontSize="small" /> },
  { label: '工具 Agent', path: '/tool-agent', icon: <HandymanRoundedIcon fontSize="small" /> },
  { label: '多模态', path: '/multimodal', icon: <PhotoCameraFrontRoundedIcon fontSize="small" /> },
  { label: '治理中心', path: '/governance', icon: <VerifiedUserRoundedIcon fontSize="small" /> }
]

export default function SideNav({ open }) {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  return (
    <Drawer variant="persistent" open={open} sx={{ width: 280, flexShrink: 0, '& .MuiDrawer-paper': { width: 280, boxSizing: 'border-box' } }}>
      <Toolbar />
      <Box sx={{ px: 2, pb: 1 }}>
        <Typography variant="overline" color="text.secondary">Workspace</Typography>
        <Typography variant="subtitle1">法律顾问控制台</Typography>
      </Box>
      <Divider />
      <List>
        {items.map(i => (
          <ListItemButton
            key={i.path}
            selected={pathname === i.path}
            onClick={() => navigate(i.path)}
            sx={{
              mx: 1,
              my: 0.5,
              borderRadius: 2,
              '&.Mui-selected': {
                bgcolor: 'rgba(30,58,138,0.10)'
              }
            }}
          >
            <ListItemIcon sx={{ minWidth: 34, color: 'text.secondary' }}>{i.icon}</ListItemIcon>
            <ListItemText primary={i.label} primaryTypographyProps={{ fontWeight: 700 }} />
          </ListItemButton>
        ))}
      </List>
    </Drawer>
  )
}
