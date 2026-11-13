import { Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'

export default function DataTable({ columns, rows, rowKey, emptyText = '暂无数据' }) {
  const renderValue = (value) => {
    if (Array.isArray(value)) return value.join(', ')
    if (value && typeof value === 'object') return JSON.stringify(value)
    return value ?? ''
  }

  if (!rows?.length) {
    return <Box sx={{ p: 3, border: '1px dashed', borderColor: 'divider', borderRadius: 1, textAlign: 'center' }}><Typography variant="body2" color="text.secondary">{emptyText}</Typography></Box>
  }

  return <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, maxHeight: 520 }}>
    <Table size="small" stickyHeader>
      <TableHead><TableRow>{columns.map(column => <TableCell key={column.key} sx={{ fontWeight: 800, whiteSpace: 'nowrap' }}>{column.label}</TableCell>)}</TableRow></TableHead>
      <TableBody>{rows.map((row, index) => <TableRow key={rowKey ? rowKey(row, index) : index} hover>{columns.map(column => <TableCell key={column.key} sx={{ maxWidth: column.maxWidth || 280, verticalAlign: 'top', wordBreak: 'break-word' }}>{column.render ? column.render(row[column.key], row) : renderValue(row[column.key])}</TableCell>)}</TableRow>)}</TableBody>
    </Table>
  </TableContainer>
}
