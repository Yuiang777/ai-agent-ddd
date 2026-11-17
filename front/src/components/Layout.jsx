import { useEffect, useState } from 'react'
import { Box, useMediaQuery, useTheme } from '@mui/material'
import NavBar from './NavBar'
import SideNav from './SideNav'
import { getMatterContext, errorMessage } from '../api/matters'
import { WorkspaceAudienceContext } from './WorkspaceAudienceContext'

export default function Layout({ children }) {
  const desktop = useMediaQuery(useTheme().breakpoints.up('md'))
  const [open, setOpen] = useState(true)
  const [mobileOpen, setMobileOpen] = useState(false)
  const [audience, setAudience] = useState({ context: null, loading: true, error: '' })

  useEffect(() => {
    let active = true
    getMatterContext()
      .then(context => { if (active) setAudience({ context, loading: false, error: '' }) })
      .catch(error => { if (active) setAudience({ context: null, loading: false, error: errorMessage(error) }) })
    return () => { active = false }
  }, [])

  const role = audience.context?.role
  return (
    <WorkspaceAudienceContext.Provider value={audience}>
      <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
        <SideNav role={role} open={desktop ? open : mobileOpen} variant={desktop ? 'persistent' : 'temporary'} onClose={() => setMobileOpen(false)} />
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <NavBar audience={audience} onMenu={() => desktop ? setOpen(value => !value) : setMobileOpen(value => !value)} />
          <Box component="main" sx={{ px: { xs: 2, sm: 3, lg: 5 }, py: { xs: 2.5, md: 4 }, minWidth: 0 }}>
            <Box sx={{ maxWidth: 1480, mx: 'auto', width: '100%' }}>{children}</Box>
          </Box>
        </Box>
      </Box>
    </WorkspaceAudienceContext.Provider>
  )
}
