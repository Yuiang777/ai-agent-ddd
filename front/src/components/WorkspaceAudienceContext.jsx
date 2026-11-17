import { createContext, useContext } from 'react'

export const WorkspaceAudienceContext = createContext({ context: null, loading: true, error: '' })

export function useWorkspaceAudience() {
  return useContext(WorkspaceAudienceContext)
}
