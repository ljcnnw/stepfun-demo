import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import './PressHoldRecorder.css'

interface PressHoldRecorderProps {
  disabled?: boolean
  onRecorded: (file: File) => Promise<void> | void
  onError?: (message: string) => void
}

function extensionFromMimeType(mimeType: string): string {
  if (mimeType.includes('ogg')) return '.ogg'
  if (mimeType.includes('mp4') || mimeType.includes('mpeg')) return '.m4a'
  if (mimeType.includes('wav')) return '.wav'
  return '.webm'
}

function createRecordedFile(blob: Blob, mimeType: string): File {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-')
  const extension = extensionFromMimeType(mimeType)
  return new File([blob], `recorded_${stamp}${extension}`, { type: mimeType })
}

export function PressHoldRecorder({ disabled = false, onRecorded, onError }: PressHoldRecorderProps) {
  const mediaRecorderRef = useRef<MediaRecorder | null>(null)
  const chunksRef = useRef<Blob[]>([])
  const releaseRequestedRef = useRef(false)
  const stoppingRef = useRef(false)

  const [supported, setSupported] = useState(true)
  const [recording, setRecording] = useState(false)
  const [starting, setStarting] = useState(false)

  useEffect(() => {
    const nextSupported = typeof window !== 'undefined'
      && typeof navigator !== 'undefined'
      && !!navigator.mediaDevices?.getUserMedia
      && typeof MediaRecorder !== 'undefined'
    setSupported(nextSupported)
  }, [])

  const stopRecording = useCallback(async () => {
    const recorder = mediaRecorderRef.current
    if (!recorder || recorder.state === 'inactive' || stoppingRef.current) return
    stoppingRef.current = true

    const finalized = await new Promise<File | null>((resolve) => {
      recorder.onstop = () => {
        recorder.stream.getTracks().forEach(track => track.stop())
        mediaRecorderRef.current = null
        setRecording(false)

        const chunks = chunksRef.current
        chunksRef.current = []
        stoppingRef.current = false

        if (chunks.length === 0) {
          resolve(null)
          return
        }

        const mimeType = recorder.mimeType || 'audio/webm'
        const blob = new Blob(chunks, { type: mimeType })
        resolve(createRecordedFile(blob, mimeType))
      }
      recorder.stop()
    })

    if (!finalized) return

    try {
      await onRecorded(finalized)
    } catch (error) {
      onError?.(error instanceof Error ? error.message : '录音写入失败')
    }
  }, [onError, onRecorded])

  const startRecording = useCallback(async () => {
    if (disabled || starting || recording) return
    if (!supported) {
      onError?.('当前浏览器不支持录音，请改用文件上传')
      return
    }

    releaseRequestedRef.current = false
    setStarting(true)
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg']
        .find(type => MediaRecorder.isTypeSupported(type)) ?? ''
      const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined)

      chunksRef.current = []
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunksRef.current.push(event.data)
      }
      recorder.onerror = () => {
        onError?.('录音过程中发生错误，请重试')
      }

      recorder.start(100)
      mediaRecorderRef.current = recorder
      setRecording(true)
    } catch (error) {
      onError?.(error instanceof Error ? error.message : '麦克风启动失败，请检查权限后重试')
    } finally {
      setStarting(false)
    }

    if (releaseRequestedRef.current) {
      await stopRecording()
    }
  }, [disabled, onError, recording, starting, stopRecording, supported])

  const requestStop = useCallback(async () => {
    releaseRequestedRef.current = true
    if (starting) return
    await stopRecording()
  }, [starting, stopRecording])

  useEffect(() => () => {
    const recorder = mediaRecorderRef.current
    if (recorder && recorder.state !== 'inactive') {
      recorder.stream.getTracks().forEach(track => track.stop())
      recorder.stop()
    }
  }, [])

  const hintText = useMemo(() => {
    if (!supported) return '当前浏览器不支持录音，请改用文件上传。'
    if (starting) return '正在请求麦克风权限...'
    if (recording) return '录音中，松手后自动结束。'
    return '按住开始录音，松手后会自动生成音频文件。'
  }, [recording, starting, supported])

  return (
    <div className="press-record">
      <button
        type="button"
        className={`press-record-btn ${recording ? 'recording' : ''}`}
        disabled={disabled || !supported}
        onContextMenu={(event) => event.preventDefault()}
        onPointerDown={async (event) => {
          if (event.button !== 0) return
          event.preventDefault()
          event.currentTarget.setPointerCapture(event.pointerId)
          await startRecording()
        }}
        onPointerUp={async (event) => {
          event.preventDefault()
          if (event.currentTarget.hasPointerCapture(event.pointerId)) {
            event.currentTarget.releasePointerCapture(event.pointerId)
          }
          await requestStop()
        }}
        onPointerCancel={async () => {
          await requestStop()
        }}
        onLostPointerCapture={async () => {
          await requestStop()
        }}
      >
        {recording ? '录音中... 松手结束' : '按住录音'}
      </button>
      <div className={`press-record-hint ${recording ? 'recording' : ''}`}>{hintText}</div>
    </div>
  )
}
