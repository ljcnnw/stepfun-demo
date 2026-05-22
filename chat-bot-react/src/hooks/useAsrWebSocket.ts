import { useRef, useState, useCallback } from 'react'
import type { ServerEvent, ChatMessage } from '../types/asr'

// const WS_URL = 'wss://invited-mileage-tribal-especially.trycloudflare.com/asr'
const WS_URL = 'ws://localhost:8080/asr'

interface UseAsrWebSocketOptions {
  onVadSpeechStarted: () => void
  onTtsAudioDelta: (base64: string) => void
  onTtsAudioDone: () => void
}

/**
 * 管理与后端的 WebSocket 连接，处理所有服务端事件。
 *
 * 事件流向：
 *   后端 → 前端：ASR 转录增量/完成、LLM 文字流、TTS 音频块、VAD/打断信号
 *   前端 → 后端：PCM 音频帧（二进制）、tts.interrupt 控制指令
 */
export function useAsrWebSocket(options: UseAsrWebSocketOptions) {
  const { onVadSpeechStarted, onTtsAudioDelta, onTtsAudioDone } = options
  const wsRef = useRef<WebSocket | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [connected, setConnected] = useState(false)
  // 当前正在流式输出的 assistant 消息 ID，用于 tts.audio.done 时 finalize 对应气泡
  const currentAssistantIdRef = useRef<string | null>(null)
  // 用 ref 而非 state 追踪播放状态，避免 onmessage 闭包捕获过期值
  const isSpeakingRef = useRef(false)
  // VAD 触发后等待 ASR 首个 delta 确认是真实说话，再执行打断
  // speech_started 时置 true，第一个 delta 时消费（compareAndSet 语义）
  const pendingInterruptRef = useRef(false)

  // 供 CallScreen 在 TTS 音频事件回调中同步更新播放状态
  const setIsSpeaking = useCallback((val: boolean) => {
    isSpeakingRef.current = val
  }, [])

  const connect = useCallback((initialProvider: 'sierra' | 'stepfun' = 'sierra') => {
    const ws = new WebSocket(WS_URL)
    ws.binaryType = 'arraybuffer'
    wsRef.current = ws

    ws.onopen = () => {
      console.log('[WebSocket] 已连接后端')
      setConnected(true)
      ws.send(JSON.stringify({ type: 'llm.provider', provider: initialProvider }))
    }

    ws.onmessage = (e: MessageEvent<string>) => {
      let event: ServerEvent
      try {
        event = JSON.parse(e.data) as ServerEvent
      } catch {
        return
      }

      // ── ASR 转录增量：实时追加到用户气泡的 interim 文本 ──────────────
      if (event.type === 'conversation.item.input_audio_transcription.delta') {
        const { item_id, text } = event

        // 首个有实际内容的 delta 到来，才确认是真实说话并执行打断
        // delta 可能是空字符串或纯标点（ASR 预热），此时不触发
        if (pendingInterruptRef.current && text && text.trim().length > 0) {
          pendingInterruptRef.current = false
          // 只有 TTS 正在播放时才打断，避免正常说话完成后误取消刚启动的 LLM
          if (isSpeakingRef.current) {
            console.log('[ASR] delta 有内容，TTS 播放中，执行打断:', text)
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify({ type: 'tts.interrupt' }))
            }
            isSpeakingRef.current = false
            setMessages(prev => prev.map(m =>
              m.role === 'assistant'
                ? { ...m, playing: false, final: m.final !== null ? m.final : m.interim }
                : m
            ))
            currentAssistantIdRef.current = null
            onVadSpeechStarted()
          } else {
            console.log('[ASR] delta 有内容，当前未在播放，跳过打断:', text)
          }
        }

        setMessages(prev => {
          const idx = prev.findIndex(m => m.id === item_id)
          if (idx === -1) {
            // 第一个 delta，创建新的用户气泡
            return [...prev, { id: item_id, role: 'user', interim: text, final: null, playing: false }]
          }
          const next = [...prev]
          next[idx] = { ...next[idx], interim: next[idx].interim + text }
          return next
        })
        return
      }

      // ── ASR 转录完成：将用户气泡 finalize ────────────────────────────
      if (event.type === 'conversation.item.input_audio_transcription.completed') {
        const { item_id, transcript } = event
        // transcript 为空表示后端过滤掉了无意义内容（如纯标点），删除对应临时气泡
        if (!transcript || transcript.trim() === '') {
          console.log('[ASR] 识别结果无意义，删除临时气泡，item_id:', item_id)
          setMessages(prev => prev.filter(m => m.id !== item_id))
          return
        }
        console.log('[ASR] 识别完成：', transcript)
        setMessages(prev => {
          const idx = prev.findIndex(m => m.id === item_id)
          if (idx === -1) {
            return [...prev, { id: item_id, role: 'user', interim: '', final: transcript, playing: false }]
          }
          const next = [...prev]
          next[idx] = { ...next[idx], final: transcript }
          return next
        })
        return
      }

      // ── VAD 检测到用户开始说话：标记待确认，等 ASR delta 再打断 ────────
      if (event.type === 'input_audio_buffer.speech_started') {
        console.log('[VAD] 检测到用户说话，等待 ASR delta 确认后再打断')
        // 不立刻打断，只标记 pending，等第一个 transcription.delta 到来时才执行
        pendingInterruptRef.current = true
        return
      }

      // ── 后端确认打断完成：前端再次执行停止（双保险） ─────────────────
      if (event.type === 'tts.interrupted') {
        console.log('[TTS] 后端确认打断，停止音频播放')
        isSpeakingRef.current = false
        setMessages(prev => prev.map(m =>
          m.role === 'assistant'
            ? { ...m, playing: false, final: m.final !== null ? m.final : m.interim }
            : m
        ))
        currentAssistantIdRef.current = null
        onVadSpeechStarted()
        return
      }

      // ── TTS 开始处理新句子：提前标记为播放中 ────────────────────────
      // tts.text.delta 比 tts.audio.delta 早到，提前设置 isSpeakingRef
      // 确保 VAD 在音频还未到达时也能触发打断
      if (event.type === 'tts.text.delta') {
        isSpeakingRef.current = true
        return
      }

      // ── LLM 文字增量：追加到当前 assistant 气泡 ──────────────────────
      if (event.type === 'llm.text.delta') {
        setMessages(prev => {
          // 找最后一条未完成的 assistant 消息
          const lastIdx = [...prev].reverse().findIndex(
            m => m.role === 'assistant' && m.final === null
          )
          if (lastIdx === -1) {
            // 没有未完成的 assistant 消息，创建新气泡
            const newId = 'llm_' + Date.now()
            currentAssistantIdRef.current = newId
            return [...prev, { id: newId, role: 'assistant', interim: event.text, final: null, playing: true }]
          }
          const realIdx = prev.length - 1 - lastIdx
          const next = [...prev]
          next[realIdx] = { ...next[realIdx], interim: next[realIdx].interim + event.text }
          return next
        })
        return
      }

      // ── LLM 推理完成：finalize 所有未完成的 assistant 气泡 ──────────────
      // 用 map 而非只找最后一条，防止多轮对话中旧气泡光标残留
      if (event.type === 'llm.text.done') {
        setMessages(prev => prev.map(m =>
          m.role === 'assistant' && m.final === null
            ? { ...m, final: m.interim }
            : m
        ))
        return
      }

      // ── TTS 音频块：转发给 useTtsPlayer 播放 ─────────────────────────
      if (event.type === 'tts.audio.delta') {
        onTtsAudioDelta(event.audio)
        return
      }

      // ── TTS 音频播放完毕：重置播放状态，finalize 当前 assistant 气泡 ─
      if (event.type === 'tts.audio.done') {
        console.log('[TTS] 音频播放完毕')
        isSpeakingRef.current = false
        if (currentAssistantIdRef.current) {
          const id = currentAssistantIdRef.current
          setMessages(prev => prev.map(m =>
            m.id === id ? { ...m, final: m.interim, playing: false } : m
          ))
          currentAssistantIdRef.current = null
        }
        onTtsAudioDone()
      }
    }

    ws.onclose = () => {
      console.log('[WebSocket] 连接已断开')
      setConnected(false)
      wsRef.current = null
    }
  }, [onVadSpeechStarted, onTtsAudioDelta, onTtsAudioDone])

  const disconnect = useCallback(() => {
    wsRef.current?.close()
  }, [])

  // 发送 PCM 音频帧给后端（二进制）
  const sendAudio = useCallback((buffer: ArrayBuffer) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(buffer)
    }
  }, [])

  // 通知后端切换 LLM provider
  const setProvider = useCallback((provider: 'sierra' | 'stepfun') => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: 'llm.provider', provider }))
    }
  }, [])

  // 重置对话状态（开始新通话时调用）
  const reset = useCallback(() => {
    setMessages([])
    currentAssistantIdRef.current = null
    isSpeakingRef.current = false
    pendingInterruptRef.current = false
  }, [])

  return { connect, disconnect, sendAudio, setProvider, messages, connected, reset, setIsSpeaking }
}
