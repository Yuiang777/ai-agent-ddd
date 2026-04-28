import { useState } from 'react'
import { Box } from '@mui/material'
import NavBar from './NavBar'
import SideNav from './SideNav'

export default function Layout({ children }) {
  const [open, setOpen] = useState(true)
  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      <SideNav open={open} onToggle={() => setOpen(v => !v)} />
      <Box sx={{ flex: 1 }}>
        <NavBar onMenu={() => setOpen(v => !v)} />
        <Box sx={{ p: 3 }}>{children}</Box>
      </Box>
    </Box>
  )
}
