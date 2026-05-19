// AudioWorklet 处理器：将 Float32 PCM 转为 Int16 PCM，并按音量阈值过滤后发送到主线程
class PcmProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this._buffer = []
    this._bufferSize = 1600  // 100ms @ 16kHz
    this._threshold = 0      // 音量阈值（RMS，0~1），0 表示不过滤

    // 接收主线程发来的阈值更新
    this.port.onmessage = (e) => {
      if (typeof e.data.threshold === 'number') {
        this._threshold = e.data.threshold
      }
    }
  }

  process(inputs) {
    const input = inputs[0]
    if (!input || !input[0]) return true

    const float32 = input[0]
    for (let i = 0; i < float32.length; i++) {
      this._buffer.push(float32[i])
    }

    while (this._buffer.length >= this._bufferSize) {
      const chunk = this._buffer.splice(0, this._bufferSize)

      // 计算当前帧的 RMS 音量，乘以 4 放大（与 UI 音量显示的计算方式保持一致）
      let sum = 0
      for (let i = 0; i < chunk.length; i++) sum += chunk[i] * chunk[i]
      const rms = Math.sqrt(sum / chunk.length) * 4

      const int16 = new Int16Array(chunk.length)

      if (rms >= this._threshold) {
        // 超过阈值：发送真实音频数据
        for (let i = 0; i < chunk.length; i++) {
          const s = Math.max(-1, Math.min(1, chunk[i]))
          int16[i] = s < 0 ? s * 0x8000 : s * 0x7fff
        }
      }
      // 低于阈值：发送全零帧，让后端 VAD 能正确检测到静音/说话结束
      // 不能直接丢弃，否则后端 VAD 永远等不到 speech_stopped，导致光标一直闪

      this.port.postMessage(int16.buffer, [int16.buffer])
    }

    return true
  }
}

registerProcessor('pcm-processor', PcmProcessor)
