import { useEffect, useState } from 'react'
import { Typography } from '@mui/material'
import DataTable from '../components/DataTable'
import { fetchAdvisors } from '../api/client'

export default function Advisors() {
  const [rows, setRows] = useState([])
  useEffect(() => { fetchAdvisors().then(setRows) }, [])
  const columns = [
    { key: 'advisorId', label: 'Advisor ID' },
    { key: 'advisorName', label: '名称' },
    { key: 'advisorType', label: '类型' },
    { key: 'orderNum', label: '顺序' }
  ]
  return (
    <>
      <Typography variant="h5" sx={{ mb: 2 }}>顾问</Typography>
      <DataTable columns={columns} rows={rows} />
    </>
  )
}
