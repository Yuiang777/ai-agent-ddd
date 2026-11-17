import { createTheme } from '@mui/material/styles'

const theme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: '#173B63', dark: '#102A46', light: '#E8EFF6', contrastText: '#FFFFFF' },
    secondary: { main: '#8A6B43', light: '#F1EBE1' },
    error: { main: '#B54745' },
    warning: { main: '#B77932' },
    success: { main: '#34745B' },
    info: { main: '#3C6F91' },
    background: { default: '#F3F5F7', paper: '#FFFFFF' },
    text: { primary: '#202A36', secondary: '#66717D' },
    divider: '#DDE3E8'
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: 'Inter, "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, Segoe UI, sans-serif',
    h4: { fontWeight: 650, letterSpacing: '-0.035em', lineHeight: 1.2 },
    h5: { fontWeight: 650, letterSpacing: '-0.025em', lineHeight: 1.25 },
    h6: { fontWeight: 650, letterSpacing: '-0.015em', lineHeight: 1.35 },
    subtitle1: { fontWeight: 600 },
    subtitle2: { fontWeight: 600 },
    body1: { lineHeight: 1.7 },
    body2: { lineHeight: 1.65 },
    button: { textTransform: 'none', fontWeight: 600, letterSpacing: 0 }
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { backgroundColor: '#F3F5F7' },
        '*': { boxSizing: 'border-box' },
        '::selection': { backgroundColor: '#DCE6EF' }
      }
    },
    MuiAppBar: {
      defaultProps: { elevation: 0, color: 'transparent' },
      styleOverrides: { root: { backgroundColor: '#FFFFFF', borderBottom: '1px solid #DDE3E8', color: '#202A36' } }
    },
    MuiDrawer: {
      styleOverrides: { paper: { backgroundColor: '#FFFFFF', borderRight: '1px solid #DDE3E8' } }
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: { root: { border: '1px solid #DDE3E8', backgroundImage: 'none', borderRadius: 8, boxShadow: '0 1px 3px rgba(28,39,50,0.04)' } }
    },
    MuiButton: {
      styleOverrides: { root: { borderRadius: 6, minHeight: 38, paddingInline: 15, boxShadow: 'none' } }
    },
    MuiTextField: { defaultProps: { size: 'small', variant: 'outlined' } },
    MuiOutlinedInput: { styleOverrides: { root: { borderRadius: 6, backgroundColor: '#FFFFFF' } } },
    MuiChip: { styleOverrides: { root: { borderRadius: 7, fontWeight: 600 } } },
    MuiTableCell: {
      styleOverrides: { head: { fontWeight: 650, color: '#46515D', backgroundColor: '#F5F7F9' }, root: { borderColor: '#E4E9ED' } }
    },
    MuiPaper: { styleOverrides: { rounded: { borderRadius: 8 } } },
    MuiAlert: { styleOverrides: { root: { borderRadius: 8 } } }
  }
})

export default theme
