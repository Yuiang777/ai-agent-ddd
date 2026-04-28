import { useEffect, useState } from 'react'
import { Typography } from '@mui/material'
import DataTable from '../components/DataTable'
import { fetchPrompts } from '../api/client'

export default function Prompts() {
  const [rows, setRows] = useState([])
  useEffect(() => { fetchPrompts().then(setRows) }, [])
  const columns = [
    { key: 'promptId', label: 'Prompt ID' },
    { key: 'promptName', label: '名称' },
    { key: 'description', label: '描述' },
    { key: 'status', label: '状态' }
  ]
  return (
    <>
      <Typography variant="h5" sx={{ mb: 2 }}>提示词</Typography>
      <DataTable columns={columns} rows={rows} />
    </>
  )
}
