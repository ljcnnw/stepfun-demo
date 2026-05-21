import { useRef, useCallback } from 'react'

interface UseAudioCaptureOptions {
  onFrame: (pcmBuffer: ArrayBuffer) => void
  onVolume?: (volume: number) => void  // 0~1 的 RMS 音量，用于驱动音量动画
}

/**
 * 麦克风音频采集 Hook。
 * 使用 Web Audio API + AudioWorklet 采集 16kHz 单声道 PCM 音频，
 * 每 100ms 产生一帧（1600 个采样点）通过 onFrame 回调发送给后端。
 * 可选的 onVolume 回调通过 AnalyserNode 实时计算 RMS 音量，用于驱动 UI 动画。
 */
export function useAudioCapture({ onFrame, onVolume }: UseAudioCaptureOptions) {
  const audioContextRef = useRef<AudioContext | null>(null)
  const workletNodeRef = useRef<AudioWorkletNode | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  // requestAnimationFrame 的句柄，stop 时用于取消音量检测循环
  const animFrameRef = useRef<number | null>(null)
  /**
   * 开始采集麦克风音频。
   * 申请麦克风权限 → 创建 AudioContext → 加载 AudioWorklet → 连接节点。
   */
  const start = useCallback(async () => {
    // 申请麦克风权限，开启回声消除和降噪
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        sampleRate: 16000,
        channelCount: 1,
        echoCancellation: true,   // 消除 TTS 播放时的回声
        noiseSuppression: true,   // 抑制背景噪音
      },
    })
    streamRef.current = stream
    console.log('[AudioCapture] 麦克风已开启')

    const ctx = new AudioContext({ sampleRate: 16000 })
    audioContextRef.current = ctx

    // 加载 AudioWorklet 处理器（Float32 → Int16 PCM，每 1600 帧触发一次）
    await ctx.audioWorklet.addModule('worklet/pcm-processor.js')

    const source = ctx.createMediaStreamSource(stream)
    const workletNode = new AudioWorkletNode(ctx, 'pcm-processor')
    workletNodeRef.current = workletNode

    // Worklet 每产生一帧 PCM 数据就通过 onFrame 发送给后端
    workletNode.port.onmessage = (e: MessageEvent<ArrayBuffer>) => {
      onFrame(e.data)
    }

    // source → workletNode（不接 destination，避免麦克风声音从扬声器播出造成回声）
    source.connect(workletNode)

    // 可选：通过 AnalyserNode 实时计算 RMS 音量，驱动音量动画
    if (onVolume) {
      const analyser = ctx.createAnalyser()
      analyser.fftSize = 256
      source.connect(analyser)

      const dataArray = new Float32Array(analyser.fftSize)
      const tick = () => {
        analyser.getFloatTimeDomainData(dataArray)
        // 计算 RMS（均方根），反映当前帧的平均音量
        let sum = 0
        for (let i = 0; i < dataArray.length; i++) sum += dataArray[i] * dataArray[i]
        const rms = Math.sqrt(sum / dataArray.length)
        // 放大 4 倍并限制到 0~1（人声 RMS 通常在 0.01~0.3 之间，放大后更直观）
        onVolume(Math.min(rms * 2, 1))
        animFrameRef.current = requestAnimationFrame(tick)
      }
      animFrameRef.current = requestAnimationFrame(tick)
    }
  }, [onFrame, onVolume])

  /**
   * 停止采集，释放所有音频资源。
   */
  const stop = useCallback(() => {
    // 停止音量检测循环
    if (animFrameRef.current !== null) {
      cancelAnimationFrame(animFrameRef.current)
      animFrameRef.current = null
    }

    workletNodeRef.current?.disconnect()
    workletNodeRef.current = null

    audioContextRef.current?.close()
    audioContextRef.current = null

    // 停止麦克风轨道，释放系统麦克风占用
    streamRef.current?.getTracks().forEach(t => t.stop())
    streamRef.current = null
    console.log('[AudioCapture] 麦克风已关闭')
  }, [])

  /**
   * 动态更新音量阈值，通过 postMessage 传入 AudioWorklet。
   * 采集进行中也可实时生效，无需重启。
   */
  const setThreshold = useCallback((threshold: number) => {
    workletNodeRef.current?.port.postMessage({ threshold })
  }, [])

  return { start, stop, setThreshold }
}
