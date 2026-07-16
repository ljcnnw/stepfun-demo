const FRAME_INTERVAL_MS = 100
const TARGET_SAMPLE_RATE = 16000
const FRAME_SAMPLES = TARGET_SAMPLE_RATE / 10
const FRAME_BYTES = FRAME_SAMPLES * 2
const SILENCE_TAIL_FRAMES = 10

export interface DecodedAudioFrames {
  frames: ArrayBuffer[]
  durationSeconds: number
  mimeType: string
}

export interface DecodedPcmAudio {
  pcm: ArrayBuffer
  durationSeconds: number
  mimeType: string
}

export function mimeFromName(name: string): string {
  const lower = name.toLowerCase()
  if (lower.endsWith('.mp3')) return 'audio/mpeg'
  if (lower.endsWith('.wav')) return 'audio/wav'
  if (lower.endsWith('.ogg')) return 'audio/ogg'
  if (lower.endsWith('.mp4') || lower.endsWith('.m4a')) return 'audio/mp4'
  return 'audio/webm'
}

function toInt16(samples: Float32Array): Int16Array {
  const int16 = new Int16Array(samples.length)
  for (let i = 0; i < samples.length; i += 1) {
    const clamped = Math.max(-1, Math.min(1, samples[i]))
    int16[i] = clamped < 0 ? clamped * 32768 : clamped * 32767
  }

  return int16
}

function toInt16Frames(int16: Int16Array): ArrayBuffer[] {
  const frames: ArrayBuffer[] = []
  for (let offset = 0; offset < int16.length; offset += FRAME_SAMPLES) {
    const frame = new ArrayBuffer(FRAME_BYTES)
    const bytes = new DataView(frame)
    const end = Math.min(offset + FRAME_SAMPLES, int16.length)
    let writeOffset = 0
    for (let i = offset; i < end; i += 1) {
      bytes.setInt16(writeOffset, int16[i], true)
      writeOffset += 2
    }
    frames.push(frame)
  }

  if (frames.length === 0) {
    frames.push(new ArrayBuffer(FRAME_BYTES))
  }

  return frames
}

export async function decodeAudioBlobToPcm(blob: Blob): Promise<DecodedPcmAudio> {
  const arrayBuffer = await blob.arrayBuffer()
  const audioContext = new AudioContext()
  const decoded = await audioContext.decodeAudioData(arrayBuffer.slice(0))
  await audioContext.close()

  const targetLength = Math.ceil(decoded.duration * TARGET_SAMPLE_RATE)
  const offline = new OfflineAudioContext(1, targetLength, TARGET_SAMPLE_RATE)
  const source = offline.createBufferSource()
  source.buffer = decoded
  source.connect(offline.destination)
  source.start(0)
  const rendered = await offline.startRendering()
  const int16 = toInt16(rendered.getChannelData(0))
  const pcm = new Uint8Array(int16.byteLength)
  pcm.set(new Uint8Array(int16.buffer, int16.byteOffset, int16.byteLength))

  return {
    pcm: pcm.buffer,
    durationSeconds: decoded.duration,
    mimeType: blob.type || 'audio/webm',
  }
}

export async function decodeAudioBlobToFrames(blob: Blob): Promise<DecodedAudioFrames> {
  const decoded = await decodeAudioBlobToPcm(blob)

  return {
    frames: [...toInt16Frames(new Int16Array(decoded.pcm)), ...Array.from({ length: SILENCE_TAIL_FRAMES }, () => new ArrayBuffer(FRAME_BYTES))],
    durationSeconds: decoded.durationSeconds,
    mimeType: decoded.mimeType,
  }
}

export function pcmToDataUrl(pcm: ArrayBuffer): string {
  const bytes = new Uint8Array(pcm)
  let binary = ''
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(bytes.length, offset + 0x8000)))
  }
  return `data:audio/L16;rate=16000;channels=1;base64,${btoa(binary)}`
}

export async function getAudioDurationSeconds(blob: Blob): Promise<number> {
  const arrayBuffer = await blob.arrayBuffer()
  const audioContext = new AudioContext()
  const decoded = await audioContext.decodeAudioData(arrayBuffer.slice(0))
  await audioContext.close()
  return decoded.duration
}

export async function blobToDataUrl(blob: Blob): Promise<string> {
  const buffer = await blob.arrayBuffer()
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let i = 0; i < bytes.length; i += 1) {
    binary += String.fromCharCode(bytes[i])
  }
  return `data:${blob.type || 'application/octet-stream'};base64,${btoa(binary)}`
}

export function frameIntervalMs(): number {
  return FRAME_INTERVAL_MS
}
