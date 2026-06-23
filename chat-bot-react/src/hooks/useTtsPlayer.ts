import { useRef, useCallback } from 'react'

/**
 * TTS 音频播放器。
 * 将后端流式返回的 base64 PCM 音频块解码后，通过 Web Audio API 无缝拼接播放。
 * 支持立刻打断：suspend AudioContext 截断硬件缓冲区中已排队的帧。
 */
export function useTtsPlayer() {
  const audioCtxRef = useRef<AudioContext | null>(null)
  // 所有已创建但尚未播放完的 source node，打断时需要逐一 stop
  const sourceNodesRef = useRef<AudioBufferSourceNode[]>([])
  // 下一个音频块的计划开始时间，用于无缝拼接（避免块与块之间出现静音间隙）
  const nextPlayTimeRef = useRef<number>(0)

  // 获取或创建 AudioContext，被打断后重新创建
  const getCtx = (): AudioContext => {
    if (!audioCtxRef.current || audioCtxRef.current.state === 'closed') {
      audioCtxRef.current = new AudioContext({ sampleRate: 16000 })
      nextPlayTimeRef.current = 0
    }
    return audioCtxRef.current
  }

  /**
   * 播放一个 base64 编码的 PCM 音频块。
   * 解码流程：base64 → Uint8Array → Int16Array → Float32Array → AudioBuffer
   */
  const playChunk = useCallback((base64Pcm: string) => {
    const ctx = getCtx()

    // base64 解码为字节数组
    const binary = atob(base64Pcm)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)

    // Int16 PCM → Float32（Web Audio API 要求 Float32 格式）
    const int16 = new Int16Array(bytes.buffer)
    const float32 = new Float32Array(int16.length)
    for (let i = 0; i < int16.length; i++) {
      float32[i] = int16[i] / (int16[i] < 0 ? 0x8000 : 0x7fff)
    }

    if (float32.length === 0) return
    const buffer = ctx.createBuffer(1, float32.length, 16000)
    buffer.copyToChannel(float32, 0)

    const source = ctx.createBufferSource()
    source.buffer = buffer
    source.connect(ctx.destination)

    // 从上一块结束时间开始播放，实现无缝拼接
    const startAt = Math.max(ctx.currentTime, nextPlayTimeRef.current)
    source.start(startAt)
    nextPlayTimeRef.current = startAt + buffer.duration

    sourceNodesRef.current.push(source)
    // 播放结束后从列表中移除，避免内存泄漏
    source.onended = () => {
      sourceNodesRef.current = sourceNodesRef.current.filter(n => n !== source)
    }
  }, [])

  /**
   * 立刻停止所有音频播放。
   * 先 suspend 暂停硬件输出（截断已写入硬件缓冲区的帧），再 close 释放资源。
   * 比直接 stop source node 更彻底，能消除硬件缓冲区中已预排的音频。
   */
  const interrupt = useCallback(() => {
    const ctx = audioCtxRef.current
    if (ctx && ctx.state !== 'closed') {
      console.log('[TtsPlayer] 打断音频播放')
      // suspend 立刻暂停硬件输出，截断已写入缓冲区的帧
      ctx.suspend().catch(() => {})
      sourceNodesRef.current.forEach(n => {
        try { n.stop() } catch (_) { /* 已停止的节点 stop 会抛异常，忽略即可 */ }
      })
      ctx.close().catch(() => {})
      audioCtxRef.current = null
    }
    sourceNodesRef.current = []
    nextPlayTimeRef.current = 0
  }, [])

  // 组件卸载时释放资源
  const dispose = useCallback(() => {
    interrupt()
  }, [interrupt])

  return { playChunk, interrupt, dispose }
}
