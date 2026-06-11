import { Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'

export default function DataTable({ columns, rows, rowKey, emptyText = '暂无数据' }) {
  const renderValue = (v) => {
    if (Array.isArray(v)) return v.join(', ')
    if (v && typeof v === 'object') return JSON.stringify(v)
    return v ?? ''
  }
  if (!rows?.length) {
    return (
      <Box sx={{ p: 3, border: '1px dashed', borderColor: 'divider', borderRadius: 2, textAlign: 'center' }}>
        <Typography variant="body2" color="text.secondary">{emptyText}</Typography>
      </Box>
    )
  }
  return (
    <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2, maxHeight: 520 }}>
      <Table size="small" stickyHeader>
        <TableHead>
          <TableRow>
            {columns.map(c => <TableCell key={c.key} sx={{ fontWeight: 800, bgcolor: 'grey.50', whiteSpace: 'nowrap' }}>{c.label}</TableCell>)}
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((r, idx) => (
            <TableRow key={rowKey ? rowKey(r, idx) : idx} hover>
              {columns.map(c => (
                <TableCell key={c.key} sx={{ maxWidth: c.maxWidth || 280, verticalAlign: 'top', wordBreak: 'break-word' }}>
                  {renderValue(r[c.key])}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
