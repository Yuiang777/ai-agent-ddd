import { lazy, Suspense, useMemo } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { Alert, Box, CircularProgress, Stack, Typography } from '@mui/material'
import Layout from './components/Layout'
import { useWorkspaceAudience } from './components/WorkspaceAudienceContext'
import { canAccessWorkspacePath } from './navigation'

const Chat = lazy(() => import('./pages/Chat'))
const Dashboard = lazy(() => import('./pages/Dashboard'))
const Governance = lazy(() => import('./pages/Governance'))
const ToolAgent = lazy(() => import('./pages/ToolAgent'))
const Multimodal = lazy(() => import('./pages/Multimodal'))
const Workflow = lazy(() => import('./pages/Workflow'))
const Matters = lazy(() => import('./pages/Matters'))
const ContractReview = lazy(() => import('./pages/ContractReview'))
const RiskInspection = lazy(() => import('./pages/RiskInspection'))
const KnowledgeBase = lazy(() => import('./pages/KnowledgeBase'))
const LawLibrary = lazy(() => import('./pages/LawLibrary'))
const AuditLog = lazy(() => import('./pages/AuditLog'))
const Settings = lazy(() => import('./pages/Settings'))
const AgentStudio = lazy(() => import('./pages/AgentStudio'))
const Templates = lazy(() => import('./pages/Templates'))

function PageFallback() {
  return <Box sx={{ minHeight: 240, display: 'grid', placeItems: 'center' }}><CircularProgress size={28} /></Box>
}

function AudienceUnavailable({ message }) {
  return <Stack spacing={1.5} sx={{ maxWidth: 720, mx: 'auto', py: { xs: 5, md: 10 } }}>
    <Typography variant="h5">暂时无法确认工作区身份</Typography>
    <Alert severity="warning">{message || '身份上下文不可用，请刷新页面或联系管理员。'}</Alert>
  </Stack>
}

function AudienceRoute({ path, children }) {
  const audience = useWorkspaceAudience()
  if (audience.loading) return <PageFallback />
  if (audience.error) return <AudienceUnavailable message={audience.error} />
  if (!canAccessWorkspacePath(audience.context?.role, path)) return <Navigate to="/dashboard" replace />
  return children
}

export default function App() {
  const routes = useMemo(() => [
    { path: '/', element: <Dashboard /> },
    { path: '/chat', element: <Chat /> },
    { path: '/dashboard', element: <Dashboard /> },
    { path: '/matters', element: <Matters /> },
    { path: '/governance', element: <Governance /> },
    { path: '/tool-agent', element: <ToolAgent /> },
    { path: '/multimodal', element: <Multimodal /> },
    { path: '/workflow', element: <Workflow /> },
    { path: '/contract-review', element: <ContractReview /> },
    { path: '/risk-inspection', element: <RiskInspection /> },
    { path: '/knowledge-base', element: <KnowledgeBase /> },
    { path: '/law-library', element: <LawLibrary /> },
    { path: '/audit', element: <AuditLog /> },
    { path: '/settings', element: <Settings /> },
    { path: '/agent-studio', element: <AgentStudio /> },
    { path: '/templates', element: <Templates /> }
  ], [])

  return (
    <Layout>
      <Suspense fallback={<PageFallback />}>
        <Routes>
          {routes.map(r => <Route key={r.path} path={r.path} element={<AudienceRoute path={r.path}>{r.element}</AudienceRoute>} />)}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </Layout>
  )
}
