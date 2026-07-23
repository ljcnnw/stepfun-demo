const BASE = 'http://localhost:8080/asr-bench/cases'

export function getCaseAudioUrl(id: string): string {
  return `${BASE}/${id}/audio`
}

export interface TestCase {
  id: string
  name: string
  note: string
  originalFileName?: string
  audioExt?: string
  hasAudio?: boolean
  durationSeconds: number
  createdAt: string
  caseType?: string
  referenceText?: string
  cantoneseTraditionalReferenceText?: string
  criticalTermsText?: string
  acceptableTextsText?: string
  passRuleType?: string
  passThreshold?: number
  enabled?: boolean
  sourceCaseId?: string
  noiseProfile?: string
  noiseType?: string
  targetSnrDb?: number
}

export interface CaseMetaUpdate {
  name?: string
  note?: string
  caseType?: string
  referenceText?: string
  cantoneseTraditionalReferenceText?: string
  criticalTermsText?: string
  acceptableTextsText?: string
  passRuleType?: string
  passThreshold?: number
  enabled?: boolean
  durationSeconds?: number
  sourceCaseId?: string
  noiseProfile?: string
  noiseType?: string
  targetSnrDb?: number
}

export async function listCases(): Promise<TestCase[]> {
  const res = await fetch(BASE)
  if (!res.ok) throw new Error(`列表请求失败: ${res.status}`)
  return res.json()
}

export async function saveCase(
  audio: Blob | null,
  originalFileName: string,
  name: string,
  note: string,
  durationSeconds: number,
  extra?: CaseMetaUpdate,
): Promise<TestCase> {
  const form = new FormData()
  if (audio) form.append('audio', new File([audio], originalFileName, { type: audio.type }))
  form.append('name', name)
  form.append('note', note)
  form.append('durationSeconds', String(durationSeconds))
  if (extra?.caseType) form.append('caseType', extra.caseType)
  if (extra?.referenceText !== undefined) form.append('referenceText', extra.referenceText)
  if (extra?.cantoneseTraditionalReferenceText !== undefined) form.append('cantoneseTraditionalReferenceText', extra.cantoneseTraditionalReferenceText)
  if (extra?.criticalTermsText !== undefined) form.append('criticalTermsText', extra.criticalTermsText)
  if (extra?.acceptableTextsText !== undefined) form.append('acceptableTextsText', extra.acceptableTextsText)
  if (extra?.passRuleType) form.append('passRuleType', extra.passRuleType)
  if (extra?.passThreshold !== undefined) form.append('passThreshold', String(extra.passThreshold))
  if (extra?.enabled !== undefined) form.append('enabled', String(extra.enabled))
  const res = await fetch(BASE, { method: 'POST', body: form })
  if (!res.ok) throw new Error(`保存失败: ${res.status}`)
  return res.json()
}

export async function updateCaseAudio(
  id: string,
  audio: Blob,
  originalFileName: string,
  durationSeconds: number,
): Promise<TestCase> {
  const form = new FormData()
  form.append('audio', new File([audio], originalFileName, { type: audio.type }))
  form.append('durationSeconds', String(durationSeconds))
  const res = await fetch(`${BASE}/${id}/audio`, { method: 'PUT', body: form })
  if (!res.ok) throw new Error(`音频保存失败: ${res.status}`)
  return res.json()
}

export async function updateCase(id: string, payload: CaseMetaUpdate): Promise<TestCase> {
  const res = await fetch(`${BASE}/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  })
  if (!res.ok) throw new Error(`更新失败: ${res.status}`)
  return res.json()
}

export async function deleteCase(id: string): Promise<void> {
  const res = await fetch(`${BASE}/${id}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`删除失败: ${res.status}`)
}

export async function fetchCaseAudio(id: string, signal?: AbortSignal): Promise<ArrayBuffer> {
  const res = await fetch(getCaseAudioUrl(id), { signal })
  if (!res.ok) throw new Error(`下载失败: ${res.status}`)
  return res.arrayBuffer()
}
