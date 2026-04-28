import { useEffect, useState } from 'react'
import { Typography } from '@mui/material'
import DataTable from '../components/DataTable'
import { fetchAgents } from '../api/client'

export default function Agents() {
  const [rows, setRows] = useState([])
  useEffect(() => { fetchAgents().then(setRows) }, [])
  const columns = [
    { key: 'agentId', label: 'Agent ID' },
    { key: 'agentName', label: '名称' },
    { key: 'channel', label: '渠道' },
    { key: 'status', label: '状态' }
  ]
  return (
    <>
      <Typography variant="h5" sx={{ mb: 2 }}>Agent</Typography>
      <DataTable columns={columns} rows={rows} />
    </>
  )
}
