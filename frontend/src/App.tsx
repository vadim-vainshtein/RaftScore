import {useEffect, useState} from 'react'
import {useTranslation} from 'react-i18next'
import './i18n/index'
import {locales, persistLocale, supportedLocale} from './i18n/supported-locales'
import type {Locale} from './i18n/supported-locales'
import {createTheme, ThemeProvider} from '@mui/material/styles'
import {AppBar, Box, Button, CssBaseline, Drawer, List, ListItem, ListItemButton, ListItemText, Toolbar, Typography, MenuItem, TextField, useMediaQuery, useTheme} from '@mui/material'

const routes = [
  {path: '/competitions', label: 'navigation.competitions'},
  {path: '/categories', label: 'navigation.categories'},
  {path: '/users', label: 'navigation.users'},
  {path: '/backups', label: 'navigation.backups'},
] as const

const drawerWidth = 240

function App() {
  const {i18n} = useTranslation()
  const locale = supportedLocale(i18n.resolvedLanguage) ?? 'en'
  useEffect(() => { document.documentElement.lang = locale }, [locale])
  return <ThemeProvider theme={createTheme({}, locales[locale].mui)}><Shell locale={locale}/></ThemeProvider>
}

function Shell({locale}: {locale: Locale}) {
  const {t, i18n} = useTranslation()
  const changeLanguage = (value: string) => {
    const next = supportedLocale(value)
    if (!next) return
    persistLocale(next)
    void i18n.changeLanguage(next)
  }
  const [path, setPath] = useState(window.location.hash.slice(1) || '/')
  const [menuOpen, setMenuOpen] = useState(false)
  const theme = useTheme()
  const desktopQuery = theme.breakpoints.up('md').replace(/^@media\s*/, '')
  const isDesktop = useMediaQuery(desktopQuery)

  useEffect(() => {
    const media = window.matchMedia(desktopQuery)
    const closeOnDesktop = (event: MediaQueryListEvent) => {
      if (event.matches) setMenuOpen(false)
    }
    media.addEventListener('change', closeOnDesktop)
    return () => media.removeEventListener('change', closeOnDesktop)
  }, [desktopQuery])

  useEffect(() => {
    const onPopState = () => setPath(window.location.hash.slice(1) || '/')
    window.addEventListener('hashchange', onPopState)
    return () => window.removeEventListener('hashchange', onPopState)
  }, [])

  const activeRoute = routes.find(route => path === route.path || path.startsWith(`${route.path}/`))
  const title = path === '/' ? t('navigation.competitions') : activeRoute ? t(activeRoute.label) : t('placeholder.notFound')

  const navigation = (
    <Box component="nav" aria-label={t('app.navigation')} sx={{width: drawerWidth}}>
      <Toolbar><Typography variant="h6">{t('app.name')}</Typography></Toolbar>
      <List>
        {routes.map(route => (
          <ListItem key={route.path} disablePadding>
          <ListItemButton
            component="a"
            href={`#${route.path}`}
            selected={activeRoute?.path === route.path || (path === '/' && route.path === '/competitions')}
            sx={{borderLeft: '4px solid transparent', '&.Mui-selected': {borderLeftColor: 'currentColor', '& .MuiListItemText-primary': {fontWeight: 'bold'}}}}
            aria-current={activeRoute?.path === route.path || (path === '/' && route.path === '/competitions') ? 'page' : undefined}
            onClick={event => {
              if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
              event.preventDefault()
              window.history.pushState(null, '', `#${route.path}`)
              setPath(route.path)
              setMenuOpen(false)
            }}
          >
            <ListItemText primary={t(route.label)}/>
          </ListItemButton>
          </ListItem>
        ))}
      </List>
    </Box>
  )

  return (
    <Box sx={{display: 'flex', minHeight: '100vh'}}>
      <CssBaseline/>
      <AppBar position="fixed" sx={{width: {md: `calc(100% - ${drawerWidth}px)`}, ml: {md: `${drawerWidth}px`}}}>
        <Toolbar>
          <Button color="inherit" aria-label={t('app.openNavigation')} aria-expanded={menuOpen && !isDesktop} onClick={() => setMenuOpen(true)} sx={{display: {md: 'none'}, mr: 2}}>{t('app.menu')}</Button>
          <Typography variant="h6" component="div" sx={{flexGrow: 1}}>{t('app.name')}</Typography>
          <TextField select size="small" label={t('app.language')} value={locale} onChange={event => changeLanguage(event.target.value)} sx={{minWidth: 120, backgroundColor: 'background.paper', borderRadius: 1}}>
            {Object.entries(locales).map(([code, config]) => <MenuItem key={code} value={code} lang={code}>{config.autonym}</MenuItem>)}
          </TextField>
        </Toolbar>
      </AppBar>
      <Drawer variant="temporary" slotProps={{paper: {"aria-label": t('app.navigation')}}} open={menuOpen && !isDesktop} onClose={() => setMenuOpen(false)} sx={{display: {xs: 'block', md: 'none'}}}>
        {navigation}
      </Drawer>
      <Drawer variant="permanent" open sx={{display: {xs: 'none', md: 'block'}, width: drawerWidth, flexShrink: 0, '& .MuiDrawer-paper': {width: drawerWidth, boxSizing: 'border-box'}}}>
        {navigation}
      </Drawer>
      <Box component="main" sx={{flexGrow: 1, minWidth: 0, p: {xs: 2, sm: 3}}}>
        <Toolbar/>
        <Typography component="h1" variant="h4">{title}</Typography>
        <Typography sx={{mt: 2}}>{activeRoute || path === '/' ? t('placeholder.unavailable') : t('placeholder.notFoundDescription')}</Typography>
      </Box>
    </Box>
  )
}

export default App
