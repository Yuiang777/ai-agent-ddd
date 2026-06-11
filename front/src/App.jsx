import { useMemo } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
import Chat from './pages/Chat'
import Dashboard from './pages/Dashboard'
import Governance from './pages/Governance'
import ToolAgent from './pages/ToolAgent'
import Multimodal from './pages/Multimodal'
import Workflow from './pages/Workflow'
export default function App() {
  const routes = useMemo(() => [
    { path: '/', element: <Dashboard /> },
    { path: '/chat', element: <Chat /> },
    { path: '/dashboard', element: <Dashboard /> },
    { path: '/governance', element: <Governance /> },
    { path: '/tool-agent', element: <ToolAgent /> },
    { path: '/multimodal', element: <Multimodal /> },
    { path: '/workflow', element: <Workflow /> }
  ], [])

  return (
    <Layout>
      <Routes>
        {routes.map(r => <Route key={r.path} path={r.path} element={r.element} />)}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Layout>
  )
}
