import { useEffect, useState } from 'react'
import { Typography } from '@mui/material'
import DataTable from '../components/DataTable'
import { fetchRagOrders } from '../api/client'

export default function Rag() {
  const [rows, setRows] = useState([])
  useEffect(() => { fetchRagOrders().then(setRows) }, [])
  const columns = [
    { key: 'ragId', label: 'RAG ID' },
    { key: 'ragName', label: '名称' },
    { key: 'knowledgeTag', label: '标签' },
    { key: 'status', label: '状态' }
  ]
  return (
    <>
      <Typography variant="h5" sx={{ mb: 2 }}>知识库</Typography>
      <DataTable columns={columns} rows={rows} />
    </>
  )
}
