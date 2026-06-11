import { Card, CardContent, Divider, Stack, Typography } from '@mui/material'

export default function SectionCard({ title, description, action, children, sx }) {
  return (
    <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 3, bgcolor: 'background.paper', ...sx }}>
      <CardContent sx={{ p: { xs: 2, md: 2.5 }, '&:last-child': { pb: { xs: 2, md: 2.5 } } }}>
        {(title || description || action) ? (
          <>
            <Stack direction="row" spacing={1.5} alignItems="flex-start" justifyContent="space-between">
              <Stack spacing={0.4} sx={{ minWidth: 0 }}>
                {title ? <Typography variant="h6" sx={{ fontWeight: 800 }}>{title}</Typography> : null}
                {description ? <Typography variant="body2" color="text.secondary">{description}</Typography> : null}
              </Stack>
              {action || null}
            </Stack>
            <Divider sx={{ my: 1.75 }} />
          </>
        ) : null}
        {children}
      </CardContent>
    </Card>
  )
}
