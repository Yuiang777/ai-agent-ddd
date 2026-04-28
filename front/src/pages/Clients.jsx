import { useEffect, useState } from 'react'
import { Typography } from '@mui/material'
import DataTable from '../components/DataTable'
import { fetchClients } from '../api/client'

export default function Clients() {
  const [rows, setRows] = useState([])
  useEffect(() => { fetchClients().then(setRows) }, [])
  const columns = [
    { key: 'clientId', label: 'Client ID' },
    { key: 'clientName', label: '名称' },
    { key: 'modelId', label: '模型ID' },
    { key: 'description', label: '描述' }
  ]
  return (
    <>
      <Typography variant="h5" sx={{ mb: 2 }}>客户端</Typography>
      <DataTable columns={columns} rows={rows} />
    </>
  )
}
