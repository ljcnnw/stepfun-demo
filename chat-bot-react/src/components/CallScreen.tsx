import React, { useState, useCallback, useEffect, useRef } from 'react'
import { WaveAnimation } from './WaveAnimation'
import type { WaveAnimationHandle } from './WaveAnimation'
import { TranscriptPanel } from './TranscriptPanel'
import { SettingsPanel } from './SettingsPanel'
import type { SettingsPanelHandle } from './SettingsPanel'
import { useAsrWebSocket } from '../hooks/useAsrWebSocket'
import type { ConfigurableAsrProvider } from '../hooks/useAsrWebSocket'
import { useAudioCapture } from '../hooks/useAudioCapture'
import { useTtsPlayer } from '../hooks/useTtsPlayer'
import './CallScreen.css'

const THRESHOLD_KEY = 'mic_threshold'
const DEFAULT_THRESHOLD = 0.05
const VAD_DURATION_KEY = 'asr_vad_silence_durations'
const DEFAULT_VAD_DURATIONS: Record<ConfigurableAsrProvider, number> = {
  stepfun: 1000,
  aliyun: 1000,
  volc: 1000,
}

function loadVadDurations(): Record<ConfigurableAsrProvider, number> {
  try {
    const saved = JSON.parse(localStorage.getItem(VAD_DURATION_KEY) || '{}') as Partial<Record<ConfigurableAsrProvider, number>>
    return {
      stepfun: typeof saved.stepfun === 'number' ? saved.stepfun : DEFAULT_VAD_DURATIONS.stepfun,
      aliyun: typeof saved.aliyun === 'number' ? saved.aliyun : DEFAULT_VAD_DURATIONS.aliyun,
      volc: typeof saved.volc === 'number' ? saved.volc : DEFAULT_VAD_DURATIONS.volc,
    }
  } catch {
    return DEFAULT_VAD_DURATIONS
  }
}

type CallState = 'idle' | 'listening' | 'speaking' | 'error'

export const CallScreen: React.FC = () => {
  const [callState, setCallState] = useState<CallState>('idle')
  const [errorMsg, setErrorMsg] = useState('')
  const [duration, setDuration] = useState(0)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [provider, setProviderState] = useState<'sierra' | 'stepfun'>('sierra')
  const [asrProvider, setAsrProviderState] = useState<'stepfun' | 'fano' | 'aliyun' | 'volc'>('stepfun')
  const [threshold, setThresholdState] = useState<number>(() => {
    const saved = localStorage.getItem(THRESHOLD_KEY)
    const val = saved !== null ? parseFloat(saved) : DEFAULT_THRESHOLD
    return isNaN(val) ? DEFAULT_THRESHOLD : val
  })
  const [vadDurations, setVadDurations] = useState<Record<ConfigurableAsrProvider, number>>(loadVadDurations)
  const timerRef = useRef<number | null>(null)
  const setIsSpeakingRef = useRef<((val: boolean) => void) | null>(null)
  const waveRef = useRef<WaveAnimationHandle | null>(null)
  const settingsPanelRef = useRef<SettingsPanelHandle | null>(null)
  const vadDurationsRef = useRef(vadDurations)

  const { playChunk, interrupt, dispose } = useTtsPlayer()

  const handleVadSpeechStarted = useCallback(() => {
    interrupt()
    setCallState('listening')
  }, [interrupt])

  const handleTtsAudioDelta = useCallback((base64: string) => {
    setCallState('speaking')
    setIsSpeakingRef.current?.(true)
    playChunk(base64)
  }, [playChunk])

  const handleTtsAudioDone = useCallback(() => {
    setIsSpeakingRef.current?.(false)
    setCallState('listening')
  }, [])

  const { connect, disconnect, sendAudio, setProvider, setAsrProvider, setAsrVadDuration, messages, reset, setIsSpeaking } = useAsrWebSocket({
    onVadSpeechStarted: handleVadSpeechStarted,
    onTtsAudioDelta: handleTtsAudioDelta,
    onTtsAudioDone: handleTtsAudioDone,
  })

  setIsSpeakingRef.current = setIsSpeaking

  const handleVolume = useCallback((vol: number) => {
    waveRef.current?.setVolume(vol >= threshold ? vol : 0)
    settingsPanelRef.current?.setVolume(vol)
  }, [threshold])

  const { start: startCapture, stop: stopCapture, setThreshold } = useAudioCapture({
    onFrame: sendAudio,
    onVolume: handleVolume,
  })

  const handleThresholdChange = useCallback((val: number) => {
    setThresholdState(val)
    setThreshold(val)
    localStorage.setItem(THRESHOLD_KEY, String(val))
  }, [setThreshold])

  const handleVadDurationChange = useCallback((provider: ConfigurableAsrProvider, value: number) => {
    const duration = Math.max(200, Math.min(5000, Math.round(value / 100) * 100))
    const next = { ...vadDurationsRef.current, [provider]: duration }
    vadDurationsRef.current = next
    setVadDurations(next)
    localStorage.setItem(VAD_DURATION_KEY, JSON.stringify(next))
    setAsrVadDuration(provider, duration)
  }, [setAsrVadDuration])

  const handleProviderChange = useCallback((val: 'sierra' | 'stepfun') => {
    setProviderState(val)
    setProvider(val)
  }, [setProvider])

  const handleAsrProviderChange = useCallback((val: 'stepfun' | 'fano' | 'aliyun' | 'volc') => {
    setAsrProviderState(val)
    setAsrProvider(val)
  }, [setAsrProvider])

  const startCall = useCallback(async () => {
    setErrorMsg('')
    reset()
    try {
      const savedVadDurations = Object.entries(vadDurationsRef.current) as Array<[ConfigurableAsrProvider, number]>
      savedVadDurations.forEach(([provider, duration]) => {
        setAsrVadDuration(provider, duration)
      })
      connect(provider, asrProvider)
      await startCapture()
      setThreshold(threshold)
      setCallState('listening')
      setDuration(0)
      timerRef.current = window.setInterval(() => setDuration(d => d + 1), 1000)
    } catch {
      setErrorMsg('无法获取麦克风权限，请在浏览器中允许麦克风访问')
      setCallState('error')
    }
  }, [connect, startCapture, reset, setThreshold, setAsrVadDuration, threshold, provider, asrProvider])

  const endCall = useCallback(() => {
    interrupt()
    setIsSpeakingRef.current?.(false)
    stopCapture()
    disconnect()
    setCallState('idle')
    waveRef.current?.setVolume(0)
    settingsPanelRef.current?.setVolume(0)
    if (timerRef.current !== null) {
      clearInterval(timerRef.current)
      timerRef.current = null
    }
  }, [interrupt, stopCapture, disconnect])

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) clearInterval(timerRef.current)
      dispose()
    }
  }, [dispose])

  const formatDuration = (s: number) => {
    const m = Math.floor(s / 60).toString().padStart(2, '0')
    const sec = (s % 60).toString().padStart(2, '0')
    return `${m}:${sec}`
  }

  const statusLabel = () => {
    if (callState === 'speaking') return '复述中'
    if (callState === 'listening') return '监听中'
    return '待机'
  }

  const isActive = callState === 'listening' || callState === 'speaking'

  return (
    <div className="call-screen">
      <div className="call-header">
        <span className={`status-dot ${callState === 'speaking' ? 'speaking' : isActive ? 'active' : 'idle'}`} />
        <span className="status-text">{statusLabel()}</span>
        {isActive && <span className="duration">{formatDuration(duration)}</span>}
        <button className="btn-settings" style={isActive ? {} : { marginLeft: 'auto' }} onClick={() => setSettingsOpen(true)}>⚙</button>
      </div>

      <TranscriptPanel messages={messages} isSpeaking={callState === 'speaking'} />

      <div className="call-footer">
        <WaveAnimation ref={waveRef} active={callState === 'listening'} />

        {errorMsg && <p className="error-msg">{errorMsg}</p>}

        {callState === 'idle' || callState === 'error' ? (
          <button className="btn-call btn-answer" onClick={startCall}>
            接听
          </button>
        ) : (
          <button className="btn-call btn-hangup" onClick={endCall}>
            挂断
          </button>
        )}
      </div>

      <SettingsPanel
        ref={settingsPanelRef}
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        threshold={threshold}
        onThresholdChange={handleThresholdChange}
        vadDurations={vadDurations}
        onVadDurationChange={handleVadDurationChange}
        provider={provider}
        onProviderChange={handleProviderChange}
        asrProvider={asrProvider}
        onAsrProviderChange={handleAsrProviderChange}
      />
    </div>
  )
}
