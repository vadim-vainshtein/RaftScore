import {useEffect, useRef, useState} from 'react'
import type {FormEvent} from 'react'
import {apiRequest, ApiError} from './api/client'
import {useTranslation} from 'react-i18next'
import './i18n/index'
import {locales, persistLocale, supportedLocale} from './i18n/supported-locales'
import type {Locale} from './i18n/supported-locales'
import {createTheme, ThemeProvider} from '@mui/material/styles'
import {AppBar, Box, Button, CssBaseline, Drawer, List, ListItem, ListItemButton, ListItemText, Toolbar, Typography, MenuItem, TextField, useMediaQuery, useTheme} from '@mui/material'

const routes = [
  {path: '/competitions', label: 'navigation.competitions', readOnly: true},
  {path: '/categories', label: 'navigation.categories', readOnly: false},
  {path: '/users', label: 'navigation.users', readOnly: false},
  {path: '/backups', label: 'navigation.backups', readOnly: false},
] as const

const drawerWidth = 240

function App() {
  const {i18n} = useTranslation()
  const locale = supportedLocale(i18n.resolvedLanguage) ?? 'en'
  useEffect(() => { document.documentElement.lang = locale }, [locale])
  return <ThemeProvider theme={createTheme({}, locales[locale].mui)}><Shell locale={locale}/></ThemeProvider>
}

type Principal = {username: string, role: 'FULL_ACCESS' | 'READ_ONLY'}
type Csrf = {headerName: string, token: string}

function safeRoute(path: string, role?: Principal['role']) {
  const route = routes.find(route => path === route.path || path.startsWith(`${route.path}/`))
  return route && (role !== 'READ_ONLY' || route.readOnly) ? path : '/competitions'
}

async function csrfPost(path: string, body?: object) {
  const csrf = await apiRequest<Csrf>('/api/auth/csrf')
  await apiRequest(path, {method: 'POST', headers: {[csrf.headerName]: csrf.token, ...(body ? {'Content-Type': 'application/json'} : {})}, body: body ? JSON.stringify(body) : undefined})
}

function Shell({locale}: {locale: Locale}) {
  const {t: auth} = useTranslation('auth')
  const [principal, setPrincipal] = useState<Principal | null>(null)
  const [checking, setChecking] = useState(true)
  const [error, setError] = useState<'invalid' | 'error' | null>(null)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [returnRoute, setReturnRoute] = useState(() => safeRoute(window.location.hash.slice(1)))
  const generation = useRef(0)

  useEffect(() => {
    let active = true
    const current = generation.current
    const confirmSession = () => {
      const requestGeneration = generation.current
      apiRequest<Principal>('/api/auth/principal').catch(error => {
        if (active && generation.current === requestGeneration && error instanceof ApiError && error.status === 401) {
          generation.current++
          setReturnRoute(safeRoute(window.location.hash.slice(1)))
          setPrincipal(null)
          setChecking(false)
        }
      })
    }
    window.addEventListener('session-check', confirmSession)
    apiRequest<Principal>('/api/auth/principal').then(value => { if (active && generation.current === current) setPrincipal(value) })
      .catch(error => { if (active && generation.current === current && !(error instanceof ApiError && error.status === 401)) setError('error') })
      .finally(() => { if (active && generation.current === current) setChecking(false) })
    return () => { active = false; window.removeEventListener('session-check', confirmSession) }
  }, [])

  const login = async (event: FormEvent) => {
    event.preventDefault()
    generation.current++
    setBusy(true); setError(null)
    try {
      await csrfPost('/api/auth/login', {username, password})
      const user = await apiRequest<Principal>('/api/auth/principal')
      const destination = safeRoute(returnRoute, user.role)
      window.history.replaceState(window.history.state, '', `#${destination}`)
      generation.current++
      setPath(destination); setPrincipal(user); setPassword('')
    } catch (error) { setError(error instanceof ApiError && error.status === 401 ? 'invalid' : 'error') }
    finally { setBusy(false) }
  }
  const logout = async () => {
    const current = ++generation.current
    setBusy(true); setError(null)
    try { await csrfPost('/api/auth/logout'); generation.current++; setReturnRoute(safeRoute(window.location.hash.slice(1))); setPrincipal(null) }
    catch {
      try { await apiRequest<Principal>('/api/auth/principal'); if (generation.current === current) setError('error') }
      catch (error) {
        if (generation.current === current && error instanceof ApiError && error.status === 401) {
          setReturnRoute(safeRoute(window.location.hash.slice(1))); setPrincipal(null)
        } else if (generation.current === current) setError('error')
      }
    }
    finally { setBusy(false) }
  }
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

  if (checking) return <Typography>{auth('loading')}</Typography>
  if (!principal) return <Box component="main" sx={{maxWidth: 400, mx: 'auto', p: 3}}>
    <CssBaseline/>
    <Typography component="h1" variant="h4">{auth('login')}</Typography>
    <Box component="form" onSubmit={login} sx={{display: 'grid', gap: 2, mt: 2}}>
      <TextField label={auth('username')} value={username} onChange={event => setUsername(event.target.value)} required autoComplete="username"/>
      <TextField label={auth('password')} type="password" value={password} onChange={event => setPassword(event.target.value)} required autoComplete="current-password"/>
      {error && <Typography role="alert">{auth(error)}</Typography>}
      <Button type="submit" variant="contained" disabled={busy}>{auth('login')}</Button>
    </Box>
  </Box>

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
        <Toolbar sx={{flexWrap: 'wrap', py: 1, gap: 1}}>
          <Button color="inherit" aria-label={t('app.openNavigation')} aria-expanded={menuOpen && !isDesktop} onClick={() => setMenuOpen(true)} sx={{display: {md: 'none'}, mr: 2}}>{t('app.menu')}</Button>
          <Typography variant="h6" component="div" sx={{flexGrow: 1}}>{t('app.name')}</Typography>
          <Typography title={principal.username} sx={{minWidth: 0, maxWidth: {xs: 100, md: 240}, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}>{principal.username}</Typography>
          <Typography sx={{whiteSpace: 'nowrap'}}>{auth(`roles.${principal.role}`)}</Typography>
          <Button color="inherit" disabled={busy} onClick={logout}>{auth('logout')}</Button>
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
        <Box sx={{height: {xs: 128, md: 80}}}/>
        <Typography component="h1" variant="h4">{title}</Typography>
        {error && <Typography role="alert">{auth(error)}</Typography>}
        <Typography sx={{mt: 2}}>{activeRoute || path === '/' ? t('placeholder.unavailable') : t('placeholder.notFoundDescription')}</Typography>
      </Box>
    </Box>
  )
}

export default App
