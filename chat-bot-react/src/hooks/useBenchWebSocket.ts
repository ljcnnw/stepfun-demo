import { useRef, useState, useCallback } from 'react'

const BENCH_WS_URL = 'ws://localhost:8080/asr-bench'

export type BenchProvider = 'stepfun' | 'fano' | 'aliyun' | 'volc'
export type BenchMode = 'ptt' | 'call' | 'file'

export interface BenchResult {
  provider: BenchProvider
  total_ms: number
  transcript: string
  item_id: string
}

export interface BenchTranscriptDelta {
  provider: BenchProvider
  delta: string
  item_id: string
}

export type BenchProviderStatus = 'waiting' | 'recognizing' | 'done' | 'error'

export interface BenchStatusEvent {
  provider: BenchProvider
  status: BenchProviderStatus
  item_id?: string
  message?: string
}

interface UseBenchWebSocketOptions {
  onReady?: () => void
  onDone?: (result: BenchResult) => void
  onVad?: (event: 'speech_start' | 'speech_end', itemId?: string) => void
  onTranscriptDelta?: (delta: BenchTranscriptDelta) => void
  onStatus?: (evt: BenchStatusEvent) => void
}

export function useBenchWebSocket(options: UseBenchWebSocketOptions = {}) {
  const { onReady, onDone, onVad, onTranscriptDelta, onStatus } = options
  const wsRef = useRef<WebSocket | null>(null)
  const [connected, setConnected] = useState(false)

  const connect = useCallback((providers: BenchProvider[], mode: BenchMode) => {
    if (wsRef.current) return
    const ws = new WebSocket(BENCH_WS_URL)
    ws.binaryType = 'arraybuffer'
    wsRef.current = ws

    ws.onopen = () => {
      console.log('[Bench WS] 已连接，发送 bench.config')
      setConnected(true)
      ws.send(JSON.stringify({ type: 'bench.config', providers, mode }))
    }

    ws.onmessage = (e: MessageEvent<string>) => {
      let msg: Record<string, unknown>
      try { msg = JSON.parse(e.data) } catch { return }

      if (msg.type === 'bench.ready') {
        onReady?.()
      } else if (msg.type === 'bench.done') {
        onDone?.({
          provider: msg.provider as BenchProvider,
          total_ms: msg.total_ms as number,
          transcript: msg.transcript as string,
          item_id: msg.item_id as string,
        })
      } else if (msg.type === 'bench.vad') {
        onVad?.(msg.event as 'speech_start' | 'speech_end', msg.item_id as string | undefined)
      } else if (msg.type === 'bench.transcript.delta') {
        onTranscriptDelta?.({
          provider: msg.provider as BenchProvider,
          delta: msg.delta as string,
          item_id: msg.item_id as string,
        })
      } else if (msg.type === 'bench.status') {
        onStatus?.({
          provider: msg.provider as BenchProvider,
          status: msg.status as BenchProviderStatus,
          item_id: msg.item_id as string | undefined,
          message: msg.message as string | undefined,
        })
      }
    }

    ws.onclose = () => {
      console.log('[Bench WS] 已断开')
      setConnected(false)
      wsRef.current = null
    }
  }, [onReady, onDone, onVad, onTranscriptDelta, onStatus])

  const disconnect = useCallback(() => {
    wsRef.current?.close()
  }, [])

  const configure = useCallback((providers: BenchProvider[], mode: BenchMode) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: 'bench.config', providers, mode }))
    }
  }, [])

  const sendAudio = useCallback((buffer: ArrayBuffer) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(buffer)
    }
  }, [])

  const callStart = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: 'bench.call.start' }))
    }
  }, [])

  const callStop = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: 'bench.call.stop' }))
    }
  }, [])

  const stop = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: 'bench.stop' }))
    }
  }, [])

  return { connect, disconnect, configure, sendAudio, stop, callStart, callStop, connected }
}
