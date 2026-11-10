import {useEffect, useState} from 'react'
import en from './en.json'
import {AppBar, Box, Button, CssBaseline, Drawer, List, ListItem, ListItemButton, ListItemText, Toolbar, Typography, useMediaQuery, useTheme} from '@mui/material'

const routes = [
  {path: '/competitions', label: en.navigation.competitions},
  {path: '/categories', label: en.navigation.categories},
  {path: '/users', label: en.navigation.users},
  {path: '/backups', label: en.navigation.backups},
] as const

const drawerWidth = 240

function App() {
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
  const title = path === '/' ? en.navigation.competitions : activeRoute?.label ?? en.placeholder.notFound

  const navigation = (
    <Box component="nav" aria-label={en.app.navigation} sx={{width: drawerWidth}}>
      <Toolbar><Typography variant="h6">{en.app.name}</Typography></Toolbar>
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
            <ListItemText primary={route.label}/>
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
          <Button color="inherit" aria-label={en.app.openNavigation} aria-expanded={menuOpen && !isDesktop} onClick={() => setMenuOpen(true)} sx={{display: {md: 'none'}, mr: 2}}>{en.app.menu}</Button>
          <Typography variant="h6" component="div">{en.app.name}</Typography>
        </Toolbar>
      </AppBar>
      <Drawer variant="temporary" slotProps={{paper: {"aria-label": en.app.navigation}}} open={menuOpen && !isDesktop} onClose={() => setMenuOpen(false)} sx={{display: {xs: 'block', md: 'none'}}}>
        {navigation}
      </Drawer>
      <Drawer variant="permanent" open sx={{display: {xs: 'none', md: 'block'}, width: drawerWidth, flexShrink: 0, '& .MuiDrawer-paper': {width: drawerWidth, boxSizing: 'border-box'}}}>
        {navigation}
      </Drawer>
      <Box component="main" sx={{flexGrow: 1, minWidth: 0, p: {xs: 2, sm: 3}}}>
        <Toolbar/>
        <Typography component="h1" variant="h4">{title}</Typography>
        <Typography sx={{mt: 2}}>{activeRoute || path === '/' ? en.placeholder.unavailable : en.placeholder.notFoundDescription}</Typography>
      </Box>
    </Box>
  )
}

export default App
