import { Box, Divider, Drawer, List, ListItemButton, ListItemIcon, ListItemText, Toolbar, Typography } from '@mui/material'
import { useLocation, useNavigate } from 'react-router-dom'
import DashboardRoundedIcon from '@mui/icons-material/DashboardRounded'
import ForumRoundedIcon from '@mui/icons-material/ForumRounded'
import DescriptionRoundedIcon from '@mui/icons-material/DescriptionRounded'
import WarningAmberRoundedIcon from '@mui/icons-material/WarningAmberRounded'
import WorkRoundedIcon from '@mui/icons-material/WorkRounded'
import MenuBookRoundedIcon from '@mui/icons-material/MenuBookRounded'
import LibraryBooksRoundedIcon from '@mui/icons-material/LibraryBooksRounded'
import ApprovalRoundedIcon from '@mui/icons-material/ApprovalRounded'
import AccountTreeRoundedIcon from '@mui/icons-material/AccountTreeRounded'
import SettingsRoundedIcon from '@mui/icons-material/SettingsRounded'
import { isClientAudience, navigationForRole } from '../navigation'

const icons = {
  dashboard: DashboardRoundedIcon,
  chat: ForumRoundedIcon,
  document: DescriptionRoundedIcon,
  risk: WarningAmberRoundedIcon,
  matters: WorkRoundedIcon,
  book: MenuBookRoundedIcon,
  library: LibraryBooksRoundedIcon,
  approval: ApprovalRoundedIcon,
  workflow: AccountTreeRoundedIcon,
  settings: SettingsRoundedIcon
}

export default function SideNav({ role, open, variant = 'persistent', onClose }) {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const client = isClientAudience(role)
  const groups = navigationForRole(role)
  return (
    <Drawer variant={variant} open={open} onClose={onClose} sx={{ width: variant === 'persistent' && open ? 264 : 0, flexShrink: 0, '& .MuiDrawer-paper': { width: 264, boxSizing: 'border-box' } }}>
      <Toolbar />
      <Box sx={{ px: 2.5, py: 2.5 }}>
        <Box sx={{ width: 34, height: 34, mb: 1.75, display: 'grid', placeItems: 'center', borderRadius: 2, bgcolor: 'primary.main', color: 'primary.contrastText', fontFamily: 'Georgia, serif', fontSize: 18 }}>E</Box>
        <Typography variant="overline" color="secondary.main" sx={{ letterSpacing: 1.5, fontWeight: 700 }}>法律事务管理</Typography>
        <Typography variant="subtitle1" sx={{ color: 'primary.dark', fontWeight: 700, mt: 0.25 }}>{client ? '客户服务门户' : '法律服务工作台'}</Typography>
        <Typography variant="caption" color="text.secondary">{client ? '事项进展 · 专业咨询 · 法律资料' : '专业协作 · 事项管理 · 审核治理'}</Typography>
      </Box>
      <Divider />
      <Box sx={{ py: 1.5, overflowY: 'auto' }}>
        {groups.map(group => (
          <Box key={group.label} sx={{ mb: 1.5 }}>
            <Typography variant="overline" color="text.secondary" sx={{ px: 2.5, py: 0.75, display: 'block', fontSize: 10, letterSpacing: 1.1, fontWeight: 700 }}>
              {group.label}
            </Typography>
            <List disablePadding>
              {group.items.map(item => {
                const selected = pathname === item.path || (item.path !== '/dashboard' && pathname.startsWith(`${item.path}/`))
                const Icon = icons[item.icon]
                return (
                  <ListItemButton
                    key={item.path}
                    selected={selected}
                    onClick={() => { navigate(item.path); if (variant === 'temporary') onClose?.() }}
                    sx={{
                      mx: 1.25, my: 0.35, minHeight: 43, borderRadius: 2,
                      '& .MuiListItemIcon-root': { color: selected ? 'primary.main' : 'text.secondary' },
                      '&.Mui-selected': { bgcolor: 'primary.light', color: 'primary.dark', '&::before': { content: '""', position: 'absolute', left: 0, top: 10, bottom: 10, width: 3, borderRadius: 3, bgcolor: 'secondary.main' } },
                      '&.Mui-selected:hover': { bgcolor: 'primary.light' },
                      '&:hover': { bgcolor: 'rgba(23,59,99,0.045)' }
                    }}
                  >
                    <ListItemIcon sx={{ minWidth: 36 }}><Icon fontSize="small" /></ListItemIcon>
                    <ListItemText primary={item.label} primaryTypographyProps={{ fontSize: 13.5, fontWeight: selected ? 650 : 500 }} />
                  </ListItemButton>
                )
              })}
            </List>
          </Box>
        ))}
      </Box>
      <Box sx={{ mt: 'auto', p: 1.5 }}>
        <Box sx={{ p: 1.5, bgcolor: '#F2F0EB', border: '1px solid', borderColor: 'divider', borderRadius: 2.5 }}>
          <Typography variant="caption" sx={{ fontWeight: 650, display: 'block' }}>{client ? '安心与隐私' : '服务原则'}</Typography>
          <Typography variant="caption" color="text.secondary">{client ? '您的事项按服务端身份与授权范围访问。' : '权限由服务端核验，重要结论保留人工复核。'}</Typography>
        </Box>
      </Box>
    </Drawer>
  )
}
