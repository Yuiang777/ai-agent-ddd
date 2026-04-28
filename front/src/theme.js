import { createTheme } from '@mui/material/styles'

const theme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: '#6366F1' },
    secondary: { main: '#22D3EE' },
    background: { default: '#F8FAFC', paper: '#FFFFFF' }
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial',
    h5: { fontWeight: 700 }
  }
})

export default theme
