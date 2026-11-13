import { Box, Stack, Typography } from '@mui/material'

export default function PageShell({ title, subtitle, action, children, maxWidth = 1440 }) {
  return (
    <Stack spacing={{ xs: 2, md: 2.75 }} useFlexGap sx={{ maxWidth, mx: 'auto', width: '100%' }}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ xs: 'flex-start', md: 'center' }} sx={{ pb: 0.5 }}>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography variant="h5" sx={{ color: 'text.primary' }}>{title}</Typography>
          {subtitle ? <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75, maxWidth: 820 }}>{subtitle}</Typography> : null}
        </Box>
        {action ? <Box sx={{ width: { xs: '100%', md: 'auto' } }}>{action}</Box> : null}
      </Stack>
      {children}
    </Stack>
  )
}
