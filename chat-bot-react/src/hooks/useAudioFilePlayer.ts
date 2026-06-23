import { useRef, useCallback, useState } from 'react'

const FRAME_SIZE = 3200        // 100ms @ 16kHz mono Int16 = 3200 bytes
const FRAME_INTERVAL_MS = 100
const TARGET_SAMPLE_RATE = 16000
const SILENCE_TAIL_FRAMES = 10  // 音频结束后补发 1000ms 静音，触发 Stepfun server_vad 的 silence_duration(800ms)

export interface AudioFilePlayerState {
  fileName: string | null
  duration: number            // seconds
  currentTime: number         // seconds
  playing: boolean
}

interface UseAudioFilePlayerOptions {
  onFrame: (buffer: ArrayBuffer) => void
  onFinish: () => void
}

export function useAudioFilePlayer({ onFrame, onFinish }: UseAudioFilePlayerOptions) {
  const [state, setState] = useState<AudioFilePlayerState>({
    fileName: null,
    duration: 0,
    currentTime: 0,
    playing: false,
  })

  const pcmRef = useRef<Int16Array | null>(null)
  const frameIndexRef = useRef(0)
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const silenceTailRef = useRef(0)  // 已发送的静音尾帧计数

  const stop = useCallback(() => {
    if (intervalRef.current !== null) {
      clearInterval(intervalRef.current)
      intervalRef.current = null
    }
    setState(prev => ({ ...prev, playing: false }))
  }, [])

  const loadFile = useCallback(async (file: File) => {
    const arrayBuffer = await file.arrayBuffer()

    const audioCtx = new AudioContext()
    const decoded = await audioCtx.decodeAudioData(arrayBuffer)
    await audioCtx.close()

    const targetLength = Math.ceil(decoded.duration * TARGET_SAMPLE_RATE)
    const offline = new OfflineAudioContext(1, targetLength, TARGET_SAMPLE_RATE)
    const src = offline.createBufferSource()
    src.buffer = decoded
    src.connect(offline.destination)
    src.start(0)
    const rendered = await offline.startRendering()

    const float32 = rendered.getChannelData(0)
    const int16 = new Int16Array(float32.length)
    for (let i = 0; i < float32.length; i++) {
      const clamped = Math.max(-1, Math.min(1, float32[i]))
      int16[i] = clamped < 0 ? clamped * 32768 : clamped * 32767
    }

    pcmRef.current = int16
    frameIndexRef.current = 0

    setState({
      fileName: file.name,
      duration: decoded.duration,
      currentTime: 0,
      playing: false,
    })
  }, [])

  const play = useCallback(() => {
    const pcm = pcmRef.current
    if (!pcm || intervalRef.current !== null) return

    const totalFrames = Math.ceil(pcm.byteLength / FRAME_SIZE)
    frameIndexRef.current = 0
    silenceTailRef.current = 0
    setState(prev => ({ ...prev, playing: true }))

    intervalRef.current = setInterval(() => {
      const idx = frameIndexRef.current
      if (idx >= totalFrames) {
        // 音频帧播完后补发静音帧，让 Stepfun server_vad 检测到静音并触发 speech_stopped
        if (silenceTailRef.current < SILENCE_TAIL_FRAMES) {
          onFrame(new ArrayBuffer(FRAME_SIZE))
          silenceTailRef.current += 1
          return
        }
        stop()
        onFinish()
        return
      }

      const byteStart = idx * FRAME_SIZE
      const byteEnd = Math.min(byteStart + FRAME_SIZE, pcm.byteLength)
      // pad last frame to full FRAME_SIZE so backend stays happy
      const frame = new ArrayBuffer(FRAME_SIZE)
      new Uint8Array(frame).set(new Uint8Array(pcm.buffer, byteStart, byteEnd - byteStart))
      onFrame(frame)

      frameIndexRef.current = idx + 1
      const currentTime = Math.min(((idx + 1) * FRAME_INTERVAL_MS) / 1000, pcmRef.current!.byteLength / 2 / TARGET_SAMPLE_RATE)
      setState(prev => ({ ...prev, currentTime }))
    }, FRAME_INTERVAL_MS)
  }, [onFrame, onFinish, stop])

  const reset = useCallback(() => {
    stop()
    pcmRef.current = null
    frameIndexRef.current = 0
    setState({ fileName: null, duration: 0, currentTime: 0, playing: false })
  }, [stop])

  return { state, loadFile, play, stop, reset }
}
