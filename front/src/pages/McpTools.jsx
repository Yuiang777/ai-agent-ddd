import { useEffect, useState } from 'react'
import { Typography } from '@mui/material'
import DataTable from '../components/DataTable'
import { fetchMcpTools } from '../api/client'

export default function McpTools() {
  const [rows, setRows] = useState([])
  useEffect(() => { fetchMcpTools().then(setRows) }, [])
  const columns = [
    { key: 'mcpId', label: 'MCP ID' },
    { key: 'mcpName', label: '名称' },
    { key: 'transportType', label: '传输' },
    { key: 'requestTimeout', label: '超时' }
  ]
  return (
    <>
      <Typography variant="h5" sx={{ mb: 2 }}>MCP 工具</Typography>
      <DataTable columns={columns} rows={rows} />
    </>
  )
}
