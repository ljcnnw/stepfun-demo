const BASE = 'http://localhost:8080/asr-bench/cases'

export interface TestCase {
  id: string
  name: string
  note: string
  originalFileName: string
  audioExt: string
  durationSeconds: number
  createdAt: string
}

export async function listCases(): Promise<TestCase[]> {
  const res = await fetch(BASE)
  if (!res.ok) throw new Error(`列表请求失败: ${res.status}`)
  return res.json()
}

export async function saveCase(
  audio: Blob,
  originalFileName: string,
  name: string,
  note: string,
  durationSeconds: number,
): Promise<TestCase> {
  const form = new FormData()
  form.append('audio', new File([audio], originalFileName))
  form.append('name', name)
  form.append('note', note)
  form.append('durationSeconds', String(durationSeconds))
  const res = await fetch(BASE, { method: 'POST', body: form })
  if (!res.ok) throw new Error(`保存失败: ${res.status}`)
  return res.json()
}

export async function deleteCase(id: string): Promise<void> {
  const res = await fetch(`${BASE}/${id}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`删除失败: ${res.status}`)
}

export async function fetchCaseAudio(id: string): Promise<ArrayBuffer> {
  const res = await fetch(`${BASE}/${id}/audio`)
  if (!res.ok) throw new Error(`下载失败: ${res.status}`)
  return res.arrayBuffer()
}
