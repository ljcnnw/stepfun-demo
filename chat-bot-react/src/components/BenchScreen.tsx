import { useState, useRef, useCallback, useEffect } from 'react'
import { useBenchWebSocket } from '../hooks/useBenchWebSocket'
import { useAudioCapture } from '../hooks/useAudioCapture'
import { useAudioFilePlayer } from '../hooks/useAudioFilePlayer'
import type { BenchProvider, BenchMode, BenchProviderStatus } from '../hooks/useBenchWebSocket'
import { listCases, saveCase, deleteCase, fetchCaseAudio } from '../api/benchCases'
import type { TestCase } from '../api/benchCases'
import './BenchScreen.css'

const PROVIDERS: { key: BenchProvider; label: string }[] = [
  { key: 'stepfun', label: 'Stepfun' },
  { key: 'aliyun', label: 'Paraformer' },
  { key: 'volc', label: '豆包ASR' },
  { key: 'fano', label: 'FANO' },
]

interface ProviderRecord {
  itemId: string
  status: BenchProviderStatus
  streamingText: string
  transcript: string
  total_ms: number | null
  done: boolean
  errorMsg?: string
}

function makeInitialRecord(itemId: string): ProviderRecord {
  return { itemId, status: 'waiting', streamingText: '', transcript: '', total_ms: null, done: false }
}

// columns: Record<provider, records[]> — 最新在前
type Columns = Record<string, ProviderRecord[]>

function updateRecord(
  prev: Columns,
  provider: string,
  itemId: string,
  updater: (r: ProviderRecord) => ProviderRecord
): Columns {
  const list = prev[provider] ?? []
  const idx = list.findIndex(r => r.itemId === itemId)
  if (idx === -1) return prev
  const updated = [...list]
  updated[idx] = updater(updated[idx])
  return { ...prev, [provider]: updated }
}

export function BenchScreen() {
  const [selectedProviders, setSelectedProviders] = useState<BenchProvider[]>(['stepfun', 'aliyun', 'volc'])
  const [mode, setMode] = useState<BenchMode>('call')
  const [columns, setColumns] = useState<Columns>({})
  const [callActive, setCallActive] = useState(false)
  const [fileLoading, setFileLoading] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // test case state
  const [fileTab, setFileTab] = useState<'local' | 'saved'>('local')
  const [caseName, setCaseName] = useState('')
  const [caseNote, setCaseNote] = useState('')
  const [caseSaving, setCaseSaving] = useState(false)
  const [cases, setCases] = useState<TestCase[]>([])
  const [casesLoading, setCasesLoading] = useState(false)
  const localFileRef = useRef<File | null>(null)
  const [vadState, setVadState] = useState<'idle' | 'speaking'>('idle')
  const [ready, setReady] = useState(false)
  const [connecting, setConnecting] = useState(false)
  const [volume, setVolume] = useState(0)

  // recording state
  const [recording, setRecording] = useState(false)
  const mediaRecorderRef = useRef<MediaRecorder | null>(null)
  const recordingChunksRef = useRef<Blob[]>([])

  // playback state for saved cases (preview, not ASR)
  const [playingCaseId, setPlayingCaseId] = useState<string | null>(null)
  const previewAudioRef = useRef<HTMLAudioElement | null>(null)
  const previewBlobUrlRef = useRef<string | null>(null)

  const selectedProvidersRef = useRef(selectedProviders)
  selectedProvidersRef.current = selectedProviders

  const addRecords = useCallback((providers: BenchProvider[], itemId: string) => {
    setColumns(prev => {
      const next = { ...prev }
      providers.forEach(p => {
        next[p] = [makeInitialRecord(itemId), ...(prev[p] ?? [])]
      })
      return next
    })
  }, [])

  const hasHistory = Object.values(columns).some(list => list.length > 0)

  const { connect, disconnect, sendAudio, callStart, callStop, connected } =
    useBenchWebSocket({
      onReady: () => {
        setReady(true)
        setConnecting(false)
      },
      onDone: (result) => {
        setColumns(prev => updateRecord(prev, result.provider, result.item_id, r => ({
          ...r,
          transcript: result.transcript,
          streamingText: '',
          total_ms: result.total_ms,
          done: true,
          status: 'done',
        })))
      },
      onVad: (event, itemId) => {
        setVadState(event === 'speech_start' ? 'speaking' : 'idle')
        if (event === 'speech_start' && itemId) {
          addRecords(selectedProvidersRef.current, itemId)
        }
      },
      onTranscriptDelta: (delta) => {
        setColumns(prev => updateRecord(prev, delta.provider, delta.item_id, r => {
          if (r.done) return r
          return { ...r, streamingText: r.streamingText + delta.delta }
        }))
      },
      onStatus: (evt) => {
        if (!evt.item_id) return
        setColumns(prev => {
          const list = prev[evt.provider] ?? []
          const exact = list.findIndex(r => r.itemId === evt.item_id)
          if (exact === -1 && evt.status === 'recognizing') {
            const waitingIdx = list.findIndex(r => r.status === 'waiting')
            if (waitingIdx !== -1) {
              const updated = [...list]
              updated[waitingIdx] = { ...updated[waitingIdx], itemId: evt.item_id!, status: 'recognizing' }
              return { ...prev, [evt.provider]: updated }
            }
          }
          return updateRecord(prev, evt.provider, evt.item_id!, r => {
            if (r.done) return r
            return { ...r, status: evt.status, errorMsg: evt.message }
          })
        })
      },
    })

  const { start: startAudio, stop: stopAudio } = useAudioCapture({
    onFrame: sendAudio,
    onVolume: setVolume,
  })

  const filePlayer = useAudioFilePlayer({
    onFrame: sendAudio,
    onFinish: useCallback(() => {
      callStop()
      setCallActive(false)
      setVadState('idle')
      setVolume(0)
    }, [callStop]),
  })

  const toggleProvider = (p: BenchProvider) => {
    setSelectedProviders(prev =>
      prev.includes(p) ? prev.filter(x => x !== p) : [...prev, p]
    )
  }

  const handleConnect = useCallback(() => {
    setConnecting(true)
    setReady(false)
    const backendMode = mode === 'file' ? 'call' : mode
    connect(selectedProvidersRef.current, backendMode)
  }, [connect, mode])

  const handleDisconnect = useCallback(() => {
    if (callActive) callStop()
    stopAudio()
    filePlayer.stop()
    disconnect()
    setReady(false)
    setConnecting(false)
    setCallActive(false)
    setVadState('idle')
    setVolume(0)
  }, [stopAudio, disconnect, callActive, callStop, filePlayer])

  const handleCallToggle = useCallback(async () => {
    if (!callActive) {
      try {
        setCallActive(true)
        callStart()
        await startAudio()
      } catch (err) {
        console.error('[Bench] 麦克风启动失败:', err)
        setCallActive(false)
        callStop()
        alert('麦克风启动失败，请检查麦克风权限后重试。')
      }
    } else {
      setCallActive(false)
      stopAudio()
      callStop()
      setVadState('idle')
      setVolume(0)
    }
  }, [callActive, startAudio, stopAudio, callStart, callStop])

  const handleFileSelect = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setFileLoading(true)
    try {
      localFileRef.current = file
      await filePlayer.loadFile(file)
      setCaseName(file.name.replace(/\.[^.]+$/, ''))
    } catch (err) {
      console.error('[Bench] 音频解码失败:', err)
      alert('音频文件解码失败，请选择有效的音频文件。')
    } finally {
      setFileLoading(false)
      if (e.target) e.target.value = ''
    }
  }, [filePlayer])

  // --- Recording handlers ---
  const handleRecordStart = useCallback(async (e: React.PointerEvent) => {
    e.preventDefault()
    if (recording) return
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg']
        .find(t => MediaRecorder.isTypeSupported(t)) ?? ''
      const mr = new MediaRecorder(stream, { mimeType })
      recordingChunksRef.current = []
      mr.ondataavailable = (ev) => {
        if (ev.data.size > 0) recordingChunksRef.current.push(ev.data)
      }
      mr.start(100)
      mediaRecorderRef.current = mr
      setRecording(true)
    } catch (err) {
      console.error('[Bench] 录音启动失败:', err)
      alert('麦克风启动失败，请检查麦克风权限后重试。')
    }
  }, [recording])

  const handleRecordStop = useCallback(async () => {
    const mr = mediaRecorderRef.current
    if (!mr || !recording) return
    setRecording(false)

    await new Promise<void>(resolve => {
      mr.onstop = () => {
        // 停止麦克风流（在 onstop 回调里停，确保所有数据已收集）
        mr.stream.getTracks().forEach(t => t.stop())
        resolve()
      }
      mr.stop()
    })

    const chunks = recordingChunksRef.current
    if (chunks.length === 0) return

    const mimeType = mr.mimeType || 'audio/webm'
    const ext = mimeType.includes('ogg') ? '.ogg' : '.webm'
    const blob = new Blob(chunks, { type: mimeType })
    const file = new File([blob], `录音_${new Date().toLocaleTimeString()}${ext}`, { type: mimeType })

    setFileLoading(true)
    try {
      localFileRef.current = file
      await filePlayer.loadFile(file)
      setCaseName(`录音_${new Date().toLocaleString()}`)
    } catch (err) {
      console.error('[Bench] 录音解码失败:', err)
      alert('录音解码失败。')
    } finally {
      setFileLoading(false)
    }
  }, [recording, filePlayer])

  // --- Preview playback for saved cases ---
  const stopPreview = useCallback(() => {
    if (previewAudioRef.current) {
      previewAudioRef.current.pause()
      previewAudioRef.current.src = ''
      previewAudioRef.current = null
    }
    if (previewBlobUrlRef.current) {
      URL.revokeObjectURL(previewBlobUrlRef.current)
      previewBlobUrlRef.current = null
    }
    setPlayingCaseId(null)
  }, [])

  const handlePreviewCase = useCallback(async (tc: TestCase, e: React.MouseEvent) => {
    e.stopPropagation()
    if (playingCaseId === tc.id) {
      stopPreview()
      return
    }
    stopPreview()
    try {
      const buf = await fetchCaseAudio(tc.id)
      const blob = new Blob([buf])
      const url = URL.createObjectURL(blob)
      previewBlobUrlRef.current = url
      const audio = new Audio(url)
      previewAudioRef.current = audio
      audio.onended = () => {
        URL.revokeObjectURL(url)
        previewBlobUrlRef.current = null
        previewAudioRef.current = null
        setPlayingCaseId(null)
      }
      audio.play()
      setPlayingCaseId(tc.id)
    } catch (err) {
      console.error('[Bench] 预览播放失败:', err)
      alert('播放失败，请检查后端服务。')
    }
  }, [playingCaseId, stopPreview])

  const handleSaveCase = useCallback(async () => {
    const file = localFileRef.current
    if (!file || !caseName.trim()) return
    setCaseSaving(true)
    try {
      const saved = await saveCase(file, file.name, caseName.trim(), caseNote.trim(), filePlayer.state.duration)
      setCases(prev => [saved, ...prev])
      alert(`已保存为用例：${saved.name}`)
    } catch (err) {
      console.error('[Bench] 保存用例失败:', err)
      alert('保存失败，请检查后端服务是否运行。')
    } finally {
      setCaseSaving(false)
    }
  }, [caseName, caseNote, filePlayer.state.duration])

  const handleLoadCases = useCallback(async () => {
    setCasesLoading(true)
    try {
      const list = await listCases()
      setCases(list)
    } catch (err) {
      console.error('[Bench] 加载用例失败:', err)
    } finally {
      setCasesLoading(false)
    }
  }, [])

  const handleLoadCase = useCallback(async (tc: TestCase) => {
    setFileLoading(true)
    try {
      const buf = await fetchCaseAudio(tc.id)
      const file = new File([buf], tc.originalFileName)
      localFileRef.current = file
      await filePlayer.loadFile(file)
      setCaseName(tc.name)
      setCaseNote(tc.note)
      setFileTab('local')
    } catch (err) {
      console.error('[Bench] 加载用例音频失败:', err)
      alert('加载失败，请检查后端服务。')
    } finally {
      setFileLoading(false)
    }
  }, [filePlayer])

  const handleDeleteCase = useCallback(async (id: string, e: React.MouseEvent) => {
    e.stopPropagation()
    if (!confirm('确认删除这个测试用例？')) return
    if (playingCaseId === id) stopPreview()
    try {
      await deleteCase(id)
      setCases(prev => prev.filter(c => c.id !== id))
    } catch (err) {
      console.error('[Bench] 删除用例失败:', err)
      alert('删除失败')
    }
  }, [playingCaseId, stopPreview])

  useEffect(() => {
    if (fileTab === 'saved') handleLoadCases()
  }, [fileTab, handleLoadCases])

  const handleFilePlay = useCallback(() => {
    if (!ready || filePlayer.state.playing) return
    setCallActive(true)
    callStart()
    filePlayer.play()
  }, [ready, filePlayer, callStart])

  const handleFileStop = useCallback(() => {
    filePlayer.stop()
    callStop()
    setCallActive(false)
    setVadState('idle')
    setVolume(0)
  }, [filePlayer, callStop])

  const handleClear = useCallback(() => {
    setColumns({})
  }, [])

  useEffect(() => {
    return () => {
      stopAudio()
      filePlayer.stop()
      disconnect()
      stopPreview()
    }
  }, [])

  const activeProviders = PROVIDERS.filter(p => selectedProviders.includes(p.key))

  return (
    <div className="bench-screen">
      <div className="bench-header">
        <h1 className="bench-title">ASR 基准测试</h1>
        <div className="bench-header-actions">
          {hasHistory && (
            <button className="bench-clear-btn" onClick={handleClear}>清空</button>
          )}
          {connected && (
            <button className="bench-disconnect-btn" onClick={handleDisconnect}>断开</button>
          )}
        </div>
      </div>

      {!connected ? (
        <div className="bench-setup">
          <div className="bench-section">
            <div className="bench-section-label">测试模式</div>
            <div className="bench-toggle-row">
              <button
                className={`bench-toggle-btn ${mode === 'call' ? 'active' : ''}`}
                onClick={() => setMode('call')}
              >通话模式</button>
              <button
                className={`bench-toggle-btn ${mode === 'file' ? 'active' : ''}`}
                onClick={() => setMode('file')}
              >上传音频</button>
            </div>
          </div>

          <div className="bench-section">
            <div className="bench-section-label">选择 ASR（可多选）</div>
            <div className="bench-toggle-row">
              {PROVIDERS.map(p => (
                <button
                  key={p.key}
                  className={`bench-toggle-btn ${selectedProviders.includes(p.key) ? 'active' : ''}`}
                  onClick={() => toggleProvider(p.key)}
                >{p.label}</button>
              ))}
            </div>
          </div>

          {mode === 'file' && (
            <div className="bench-section">
              <div className="bench-file-tabs">
                <button
                  className={`bench-file-tab ${fileTab === 'local' ? 'active' : ''}`}
                  onClick={() => setFileTab('local')}
                >本地文件</button>
                <button
                  className={`bench-file-tab ${fileTab === 'saved' ? 'active' : ''}`}
                  onClick={() => setFileTab('saved')}
                >已保存用例</button>
              </div>

              {fileTab === 'local' ? (
                <div className="bench-file-local">
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="audio/*"
                    style={{ display: 'none' }}
                    onChange={handleFileSelect}
                  />
                  {/* File picker button */}
                  <button
                    className="bench-toggle-btn"
                    onClick={() => fileInputRef.current?.click()}
                    disabled={fileLoading || recording}
                    style={{ width: '100%', borderRadius: 10, marginBottom: 8 }}
                  >
                    {fileLoading ? '解码中…' : filePlayer.state.fileName ?? '点击选择文件'}
                  </button>
                  {/* Record button */}
                  <button
                    className={`bench-record-btn${recording ? ' recording' : ''}`}
                    onPointerDown={handleRecordStart}
                    onPointerUp={handleRecordStop}
                    onPointerLeave={handleRecordStop}
                    disabled={fileLoading}
                  >
                    {recording ? '🔴 录音中… 松开停止' : '🎙 按住录音'}
                  </button>
                  {filePlayer.state.fileName && !fileLoading && (
                    <>
                      <div style={{ fontSize: 12, color: '#888', margin: '10px 0' }}>
                        时长：{filePlayer.state.duration.toFixed(1)}s
                      </div>
                      <div className="bench-case-save-row">
                        <input
                          className="bench-case-input"
                          placeholder="用例名称"
                          value={caseName}
                          onChange={e => setCaseName(e.target.value)}
                        />
                        <input
                          className="bench-case-input"
                          placeholder="备注（可选）"
                          value={caseNote}
                          onChange={e => setCaseNote(e.target.value)}
                        />
                        <button
                          className="bench-case-save-btn"
                          onClick={handleSaveCase}
                          disabled={caseSaving || !caseName.trim()}
                        >
                          {caseSaving ? '保存中…' : '保存为用例'}
                        </button>
                      </div>
                    </>
                  )}
                </div>
              ) : (
                <div className="bench-case-list">
                  {casesLoading ? (
                    <div className="bench-case-empty">加载中…</div>
                  ) : cases.length === 0 ? (
                    <div className="bench-case-empty">暂无已保存用例</div>
                  ) : cases.map(tc => (
                    <div
                      key={tc.id}
                      className="bench-case-item"
                      onClick={() => handleLoadCase(tc)}
                    >
                      <div className="bench-case-item-main">
                        <div className="bench-case-item-name">{tc.name}</div>
                        {tc.note && <div className="bench-case-item-note">{tc.note}</div>}
                        <div className="bench-case-item-meta">
                          {tc.durationSeconds.toFixed(1)}s · {tc.createdAt.slice(0, 10)}
                        </div>
                      </div>
                      <button
                        className={`bench-case-play-btn${playingCaseId === tc.id ? ' playing' : ''}`}
                        onClick={e => handlePreviewCase(tc, e)}
                        title={playingCaseId === tc.id ? '停止播放' : '播放'}
                      >
                        {playingCaseId === tc.id ? '■' : '▶'}
                      </button>
                      <button
                        className="bench-case-delete-btn"
                        onClick={e => handleDeleteCase(tc.id, e)}
                      >✕</button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          <button
            className="bench-connect-btn"
            onClick={handleConnect}
            disabled={connecting || selectedProviders.length === 0 || (mode === 'file' && !filePlayer.state.fileName)}
          >
            {connecting ? '连接中…' : '开始测试'}
          </button>
        </div>
      ) : (
        <div className="bench-main">
          <div className={`bench-status-bar ${vadState === 'speaking' ? 'speaking' : ''}`}>
            {mode === 'file'
              ? filePlayer.state.playing ? '▶ 播放中' : ready ? '就绪，点击开始' : '连接中…'
              : vadState === 'speaking' ? '🎙 检测到说话' : callActive ? '👂 监听中' : '待机'
            }
          </div>

          {hasHistory && (
            <div className="bench-columns">
              {activeProviders.map(p => (
                <div key={p.key} className="bench-column">
                  <div className="bench-column-header">{p.label}</div>
                  <div className="bench-column-body">
                    {(columns[p.key] ?? []).map(record => (
                      <div
                        key={record.itemId}
                        className={`bench-record bench-record-${record.status}`}
                      >
                        <div className="bench-record-status-row">
                          <span className={`bench-status-dot bench-status-dot-${record.status}`} />
                          <span className="bench-status-label">{statusLabel(record.status)}</span>
                          {record.total_ms !== null && (
                            <span className="bench-record-ms">{record.total_ms}ms</span>
                          )}
                        </div>
                        {record.status === 'error' && record.errorMsg && (
                          <div className="bench-record-error-msg">{record.errorMsg}</div>
                        )}
                        {record.done ? (
                          <div className="bench-record-text">
                            {record.transcript || '（空）'}
                          </div>
                        ) : record.streamingText ? (
                          <div className="bench-record-text bench-record-text-streaming">
                            {record.streamingText}
                            {record.status === 'recognizing' && (
                              <span className="bench-cursor">▋</span>
                            )}
                          </div>
                        ) : record.status === 'waiting' ? (
                          <div className="bench-record-text bench-record-text-muted">等待中…</div>
                        ) : null}
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}

          {mode === 'file' ? (
            <div className="bench-call-area">
              {filePlayer.state.fileName && (
                <div className="bench-file-progress">
                  <div className="bench-file-name">{filePlayer.state.fileName}</div>
                  <div className="bench-file-bar">
                    <div
                      className="bench-file-bar-fill"
                      style={{
                        width: filePlayer.state.duration > 0
                          ? `${(filePlayer.state.currentTime / filePlayer.state.duration) * 100}%`
                          : '0%'
                      }}
                    />
                  </div>
                  <div className="bench-file-time">
                    {filePlayer.state.currentTime.toFixed(1)}s / {filePlayer.state.duration.toFixed(1)}s
                  </div>
                </div>
              )}
              <button
                className={`bench-call-btn ${filePlayer.state.playing ? 'active' : ''}`}
                onClick={filePlayer.state.playing ? handleFileStop : handleFilePlay}
                disabled={!ready || !filePlayer.state.fileName}
              >
                {filePlayer.state.playing ? '停止播放' : '开始测试'}
              </button>
            </div>
          ) : (
            <div className="bench-call-area">
              {callActive && (
                <div className="bench-volume-bar">
                  {Array.from({ length: 20 }).map((_, i) => (
                    <div
                      key={i}
                      className="bench-volume-bar-item"
                      style={{ opacity: volume * 20 > i ? 1 : 0.12 }}
                    />
                  ))}
                </div>
              )}
              <button
                className={`bench-call-btn ${callActive ? 'active' : ''}`}
                onClick={handleCallToggle}
                disabled={!ready}
              >
                {callActive ? '结束监听' : '开始监听'}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function statusLabel(status: BenchProviderStatus): string {
  switch (status) {
    case 'waiting': return '等待'
    case 'recognizing': return '识别中'
    case 'done': return '完成'
    case 'error': return '错误'
  }
}
