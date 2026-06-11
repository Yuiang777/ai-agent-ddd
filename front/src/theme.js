import { createTheme } from '@mui/material/styles'

const theme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: '#1E3A8A' },
    secondary: { main: '#B45309' },
    background: { default: '#F6F7FB', paper: 'rgba(255,255,255,0.78)' },
    text: { primary: '#0B1220' }
  },
  shape: { borderRadius: 16 },
  typography: {
    fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial',
    h4: { fontWeight: 800, letterSpacing: '-0.02em' },
    h5: { fontWeight: 800, letterSpacing: '-0.02em' },
    h6: { fontWeight: 800, letterSpacing: '-0.01em' },
    subtitle1: { fontWeight: 700 },
    button: { textTransform: 'none', fontWeight: 700 }
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          backgroundImage: [
            'radial-gradient(1200px 800px at 10% 0%, rgba(30,58,138,0.18), transparent 60%)',
            'radial-gradient(900px 700px at 95% 10%, rgba(180,83,9,0.16), transparent 55%)',
            'radial-gradient(1200px 900px at 50% 110%, rgba(99,102,241,0.10), transparent 55%)'
          ].join(','),
          backgroundAttachment: 'fixed'
        }
      }
    },
    MuiAppBar: {
      defaultProps: { elevation: 0, color: 'transparent' },
      styleOverrides: {
        root: {
          backdropFilter: 'blur(14px)',
          borderBottom: '1px solid rgba(15, 23, 42, 0.10)'
        }
      }
    },
    MuiDrawer: {
      styleOverrides: {
        paper: {
          backgroundColor: 'rgba(255,255,255,0.72)',
          backdropFilter: 'blur(16px)',
          borderRight: '1px solid rgba(15, 23, 42, 0.10)'
        }
      }
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          border: '1px solid rgba(15, 23, 42, 0.10)',
          backgroundImage: 'linear-gradient(180deg, rgba(255,255,255,0.92), rgba(255,255,255,0.70))',
          backdropFilter: 'blur(16px)'
        }
      }
    },
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 12
        }
      }
    },
    MuiTextField: {
      defaultProps: { size: 'small' }
    }
  }
})

export default theme
