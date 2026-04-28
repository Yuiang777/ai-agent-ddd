import { useMemo } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
import Chat from './pages/Chat'
import Dashboard from './pages/Dashboard'
import Agents from './pages/Agents'
import Clients from './pages/Clients'
import Models from './pages/Models'
import McpTools from './pages/McpTools'
import Prompts from './pages/Prompts'
import Advisors from './pages/Advisors'
import Rag from './pages/Rag'

export default function App() {
  const routes = useMemo(() => [
    { path: '/', element: <Chat /> },
    { path: '/chat', element: <Chat /> },
    { path: '/dashboard', element: <Dashboard /> },
    { path: '/agents', element: <Agents /> },
    { path: '/clients', element: <Clients /> },
    { path: '/models', element: <Models /> },
    { path: '/tools', element: <McpTools /> },
    { path: '/prompts', element: <Prompts /> },
    { path: '/advisors', element: <Advisors /> },
    { path: '/rag', element: <Rag /> }
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
