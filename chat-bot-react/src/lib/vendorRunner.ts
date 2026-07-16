import { decodeAudioBlobToFrames, frameIntervalMs } from './audioFrames'
import type { PassRuleType, Vendor } from './asrEval'

const WS_URL = 'ws://localhost:8080/asr-bench'
const RUN_TIMEOUT_MS = 180000

export interface VendorRunOutcome {
  transcript: string
  firstLatencyMs: number | null
  finalLatencyMs: number | null
  errorMsg: string
}

function delay(ms: number): Promise<void> {
  return new Promise(resolve => {
    window.setTimeout(resolve, ms)
  })
}

function safeSend(ws: WebSocket, payload: unknown) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload))
  }
}

async function waitForOpen(ws: WebSocket, signal?: AbortSignal): Promise<void> {
  if (ws.readyState === WebSocket.OPEN) return
  await new Promise<void>((resolve, reject) => {
    const onOpen = () => {
      cleanup()
      resolve()
    }
    const onError = () => {
      cleanup()
      reject(new Error('WebSocket 连接失败'))
    }
    const onAbort = () => {
      cleanup()
      reject(new Error('任务已取消'))
    }
    const cleanup = () => {
      ws.removeEventListener('open', onOpen)
      ws.removeEventListener('error', onError)
      signal?.removeEventListener('abort', onAbort)
    }
    ws.addEventListener('open', onOpen)
    ws.addEventListener('error', onError)
    signal?.addEventListener('abort', onAbort)
  })
}

async function streamFrames(
  ws: WebSocket,
  frames: ArrayBuffer[],
  signal?: AbortSignal,
  shouldStop?: () => boolean,
): Promise<void> {
  const interval = frameIntervalMs()
  for (const frame of frames) {
    if (signal?.aborted) throw new Error('任务已取消')
    if (shouldStop?.()) break
    if (ws.readyState !== WebSocket.OPEN) throw new Error('WebSocket 已断开')
    ws.send(frame)
    await delay(interval)
  }
}

export async function runVendorCase(params: {
  vendor: Vendor
  audioBlob: Blob
  signal?: AbortSignal
  onLog?: (message: string) => void
}): Promise<VendorRunOutcome> {
  const { vendor, audioBlob, signal, onLog } = params
  const { frames } = await decodeAudioBlobToFrames(audioBlob)

  const ws = new WebSocket(WS_URL)
  ws.binaryType = 'arraybuffer'

  const startedAt = performance.now()
  let firstLatencyMs: number | null = null
  let finalLatencyMs: number | null = null
  let transcript = ''
  let errorMsg = ''
  let doneReceived = false
  let resolveResult: ((value: VendorRunOutcome) => void) | null = null
  let rejectResult: ((reason?: unknown) => void) | null = null

  const resultPromise = new Promise<VendorRunOutcome>((resolve, reject) => {
    resolveResult = resolve
    rejectResult = reject
  })

  const timeout = window.setTimeout(() => {
    if (!doneReceived) {
      errorMsg = '任务超时'
      cleanup()
      rejectResult?.(new Error(errorMsg))
    }
  }, RUN_TIMEOUT_MS)

  const cleanup = () => {
    window.clearTimeout(timeout)
    if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
      try {
        ws.close()
      } catch {
        // ignore
      }
    }
  }

  const finishWithError = (message: string) => {
    if (doneReceived) return
    errorMsg = message
    cleanup()
    rejectResult?.(new Error(message))
  }

  ws.addEventListener('open', () => {
    onLog?.(`连接 ${vendor} 的评估通道`)
    safeSend(ws, { type: 'bench.config', providers: [vendor], mode: 'call' })
  })

  ws.addEventListener('message', (event) => {
    let msg: Record<string, unknown>
    try {
      msg = JSON.parse(String(event.data))
    } catch {
      return
    }

    const type = msg.type as string | undefined
    if (type === 'bench.ready') {
      onLog?.(`${vendor} 已就绪，开始推送音频`)
      safeSend(ws, { type: 'bench.call.start' })
      return
    }

    if (type === 'bench.status' && msg.provider === vendor && msg.status === 'recognizing' && firstLatencyMs === null) {
      firstLatencyMs = performance.now() - startedAt
      onLog?.(`${vendor} 首包就绪 ${Math.round(firstLatencyMs)}ms`)
      return
    }

    if (type === 'bench.status' && msg.provider === vendor && msg.status === 'error') {
      finishWithError((msg.message as string) || `${vendor} 识别失败`)
      return
    }

    if (type === 'bench.done' && msg.provider === vendor) {
      doneReceived = true
      transcript = (msg.transcript as string) || ''
      finalLatencyMs = typeof msg.total_ms === 'number' ? msg.total_ms : performance.now() - startedAt
      onLog?.(`${vendor} 完成，耗时 ${Math.round(finalLatencyMs)}ms`)
      resolveResult?.({
        transcript,
        firstLatencyMs,
        finalLatencyMs,
        errorMsg,
      })
      cleanup()
      return
    }
  })

  ws.addEventListener('error', () => {
    finishWithError(`${vendor} WebSocket 连接错误`)
  })

  ws.addEventListener('close', () => {
    if (!doneReceived && !errorMsg) {
      errorMsg = `${vendor} 连接已关闭`
      rejectResult?.(new Error(errorMsg))
    }
  })

  let streamController: AbortController | null = null
  let abortHandler: (() => void) | null = null

  try {
    await waitForOpen(ws, signal)

    if (signal?.aborted) {
      throw new Error('任务已取消')
    }

    streamController = new AbortController()
    const streamSignal = streamController.signal

    abortHandler = () => {
      streamController?.abort()
      cleanup()
      rejectResult?.(new Error('任务已取消'))
    }
    signal?.addEventListener('abort', abortHandler, { once: true })

    const streamTask = streamFrames(
      ws,
      frames,
      signal,
      () => doneReceived || streamSignal.aborted,
    ).then(() => {
      if (!doneReceived && ws.readyState === WebSocket.OPEN) {
        safeSend(ws, { type: 'bench.call.stop' })
      }
    })

    const result = await Promise.race([
      resultPromise,
      streamTask.then(async () => {
        if (!doneReceived && ws.readyState === WebSocket.OPEN) {
          safeSend(ws, { type: 'bench.call.stop' })
        }

        return new Promise<VendorRunOutcome>((resolve, reject) => {
          const checkDone = window.setInterval(() => {
            if (doneReceived) {
              window.clearInterval(checkDone)
              resolve({
                transcript,
                firstLatencyMs,
                finalLatencyMs,
                errorMsg,
              })
            }
            if (ws.readyState === WebSocket.CLOSED && !doneReceived && errorMsg) {
              window.clearInterval(checkDone)
              reject(new Error(errorMsg))
            }
          }, 50)

          window.setTimeout(() => {
            window.clearInterval(checkDone)
            if (!doneReceived) {
              reject(new Error(errorMsg || `${vendor} 未返回最终结果`))
            }
          }, 15000)
        })
      }),
    ])

    return result
  } finally {
    if (abortHandler) {
      signal?.removeEventListener('abort', abortHandler)
    }
    streamController?.abort()
    cleanup()
  }
}

export function mapVendorError(err: unknown): string {
  if (err instanceof Error) return err.message
  return '未知错误'
}

export function canPassByRule(
  rule: PassRuleType,
  cer: number,
  entityAccuracy: number | null,
  threshold: number,
): boolean {
  if (rule === 'entity') return entityAccuracy === null ? cer <= threshold : entityAccuracy === 1
  if (rule === 'mixed') return cer <= threshold && (entityAccuracy === null || entityAccuracy === 1)
  return cer <= threshold
}
