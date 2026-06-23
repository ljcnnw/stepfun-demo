import { useState } from 'react'
import { CallScreen } from './components/CallScreen'
import { BenchScreen } from './components/BenchScreen'

type Page = 'call' | 'bench'

function App() {
  const [page, setPage] = useState<Page>('call')

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%' }}>
      {page === 'call' ? <CallScreen /> : <BenchScreen />}
      <nav style={{
        position: 'fixed', bottom: 0, left: 0, right: 0,
        display: 'flex', background: '#111', borderTop: '1px solid #222',
        zIndex: 100,
      }}>
        <button
          onClick={() => setPage('call')}
          style={{
            flex: 1, padding: '12px 0', background: 'none', border: 'none',
            color: page === 'call' ? '#fff' : '#666', fontSize: 13, cursor: 'pointer',
          }}
        >📞 对话</button>
        <button
          onClick={() => setPage('bench')}
          style={{
            flex: 1, padding: '12px 0', background: 'none', border: 'none',
            color: page === 'bench' ? '#fff' : '#666', fontSize: 13, cursor: 'pointer',
          }}
        >📊 ASR 测试</button>
      </nav>
    </div>
  )
}

export default App
