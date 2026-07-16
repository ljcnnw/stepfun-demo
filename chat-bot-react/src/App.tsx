import { useEffect, useMemo, useState } from 'react'
import { CallScreen } from './components/CallScreen'
import { BenchScreen } from './components/BenchScreen'
import { EvalPage } from './pages/EvalPage'
import { EvalCasesPage } from './pages/EvalCasesPage'
import { EvalRunPage } from './pages/EvalRunPage'
import { stripAppBase } from './lib/appRoutes'

type Route = 'call' | 'bench' | 'eval' | 'eval-cases' | 'eval-run'

function getRouteFromPath(pathname: string): Route {
  const path = stripAppBase(pathname)
  if (/^\/eval\/runs\/[^/]+$/.test(path)) return 'eval-run'
  if (path === '/eval/cases') return 'eval-cases'
  if (path === '/eval') return 'eval'
  if (path === '/bench') return 'bench'
  if (path === '/call') return 'call'
  return 'call'
}

function getRunIdFromPath(pathname: string): string | null {
  const match = stripAppBase(pathname).match(/^\/eval\/runs\/([^/]+)$/)
  return match ? match[1] : null
}

function App() {
  const [route, setRoute] = useState<Route>(() => {
    if (typeof window === 'undefined') return 'call'
    return getRouteFromPath(window.location.pathname)
  })
  const [runId, setRunId] = useState<string | null>(() => {
    if (typeof window === 'undefined') return null
    return getRunIdFromPath(window.location.pathname)
  })

  useEffect(() => {
    const onPopState = () => {
      setRoute(getRouteFromPath(window.location.pathname))
      setRunId(getRunIdFromPath(window.location.pathname))
    }

    window.addEventListener('popstate', onPopState)
    return () => {
      window.removeEventListener('popstate', onPopState)
    }
  }, [])

  const content = useMemo(() => {
    if (route === 'call') return <CallScreen />
    if (route === 'bench') return <BenchScreen />
    if (route === 'eval-cases') return <EvalCasesPage />
    if (route === 'eval-run' && runId) return <EvalRunPage key={runId} runId={runId} />
    return <EvalPage />
  }, [route, runId])

  return (
    <div style={{ minHeight: '100%' }}>
      {content}
    </div>
  )
}

export default App
