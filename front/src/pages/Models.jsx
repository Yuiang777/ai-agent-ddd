import { useEffect, useState } from 'react'
import { Typography } from '@mui/material'
import DataTable from '../components/DataTable'
import { fetchModels } from '../api/client'

export default function Models() {
  const [rows, setRows] = useState([])
  useEffect(() => { fetchModels().then(setRows) }, [])
  const columns = [
    { key: 'modelId', label: 'Model ID' },
    { key: 'modelName', label: '模型名称' },
    { key: 'modelType', label: '类型' },
    { key: 'apiId', label: 'API ID' }
  ]
  return (
    <>
      <Typography variant="h5" sx={{ mb: 2 }}>模型</Typography>
      <DataTable columns={columns} rows={rows} />
    </>
  )
}
