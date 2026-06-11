import { Box, Stack, Typography } from '@mui/material'

export default function PageShell({ title, subtitle, action, children, maxWidth = 1440 }) {
  return (
    <Stack spacing={2.5} sx={{ maxWidth, mx: 'auto', width: '100%' }}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ xs: 'flex-start', md: 'center' }}>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography variant="h5" sx={{ fontWeight: 800, letterSpacing: '-0.02em' }}>{title}</Typography>
          {subtitle ? <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>{subtitle}</Typography> : null}
        </Box>
        {action ? <Box>{action}</Box> : null}
      </Stack>
      {children}
    </Stack>
  )
}
