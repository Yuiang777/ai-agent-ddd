import { Drawer, List, ListItemButton, ListItemText, Toolbar } from '@mui/material'
import { useNavigate, useLocation } from 'react-router-dom'

const items = [
  { label: '仪表盘', path: '/' },
  { label: '对话', path: '/chat' },
  { label: 'Agent', path: '/agents' },
  { label: '客户端', path: '/clients' },
  { label: '模型', path: '/models' },
  { label: 'MCP 工具', path: '/tools' },
  { label: '提示词', path: '/prompts' },
  { label: '顾问', path: '/advisors' },
  { label: '知识库', path: '/rag' }
]

export default function SideNav({ open }) {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  return (
    <Drawer variant="persistent" open={open} sx={{ width: 240, flexShrink: 0, '& .MuiDrawer-paper': { width: 240, boxSizing: 'border-box' } }}>
      <Toolbar />
      <List>
        {items.map(i => (
          <ListItemButton key={i.path} selected={pathname === i.path} onClick={() => navigate(i.path)}>
            <ListItemText primary={i.label} />
          </ListItemButton>
        ))}
      </List>
    </Drawer>
  )
}
