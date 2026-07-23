import { createIdleVendorResult, type EvalTextMode, type Vendor, type VendorEvalResult, type PassRuleType, type CaseType } from '../lib/asrEval'

const BASE = 'http://localhost:8080/asr-eval/runs'

export interface EvalRunLogLine {
  time: string
  level?: 'info' | 'warn' | 'error' | 'debug' | 'log'
  text: string
}

export interface EvalRunVendorSummary {
  vendor: Vendor
  completed: number
  passed: number
  timeoutCount?: number
  failureCount?: number
  avgCer: number | null
  avgWer: number | null
  avgFirstLatencyMs: number | null
  avgFinalLatencyMs: number | null
  entityAccuracy: number | null
  passRate: number | null
  timeoutRate?: number | null
  failureRate?: number | null
}

export interface EvalRunSummary {
  totalCases: number
  completedCases: number
  passedCases: number
  allPassedCases?: number
  anyPassedCases?: number
  noPassedCases?: number
  failedCases?: number
  timeoutCases?: number
  totalVendors: number
  doneVendors: number
  passedVendors: number
  timeoutVendors: number
  failureVendors: number
  vendors: EvalRunVendorSummary[]
}

export interface EvalRunCaseRecord {
  id: string
  source: 'backend' | 'local'
  backendId?: string
  audioDataUrl?: string
  name: string
  note: string
  caseType: CaseType
  referenceText: string
  cantoneseTraditionalReferenceText?: string
  criticalTermsText: string
  acceptableTextsText?: string
  sourceCaseId?: string
  noiseProfile?: string
  noiseType?: string
  targetSnrDb?: number
  passRuleType: PassRuleType
  passThreshold: number
  enabled: boolean
  durationSeconds?: number
  audioFileName?: string
  audioMimeType?: string
  backendAudioExt?: string
  selected?: boolean
  vendors: Record<Vendor, VendorEvalResult>
}

export interface EvalRunRecord {
  runId: string
  name: string
  status: 'idle' | 'running' | 'pausing' | 'paused' | 'completed' | 'stopped' | 'failed'
  evaluationMode: EvalTextMode
  selectedVendors: Vendor[]
  selectedCaseIds: string[]
  startedAt: string
  updatedAt: string
  finishedAt?: string | null
  logs: EvalRunLogLine[]
  cases: EvalRunCaseRecord[]
  summary: EvalRunSummary
}

export interface EvalRunListItem {
  runId: string
  name: string
  status: EvalRunRecord['status']
  evaluationMode: EvalTextMode
  selectedVendors: Vendor[]
  selectedCaseIds: string[]
  startedAt: string
  updatedAt: string
  finishedAt?: string | null
  summary: EvalRunSummary
}

export interface EvalRunCreatePayload {
  name: string
  evaluationMode: EvalTextMode
  selectedVendors: Vendor[]
  selectedCaseIds: string[]
  audioPcmDataUrls: Record<string, string>
}

function normalizeVendorResult(vendor: Vendor, raw: Partial<VendorEvalResult> | null | undefined): VendorEvalResult {
  return {
    ...createIdleVendorResult(vendor),
    ...(raw ?? {}),
    vendor,
    entityMissedTerms: Array.isArray(raw?.entityMissedTerms) ? raw.entityMissedTerms : [],
  }
}

function normalizeVendorSummary(vendor: Vendor, raw?: Partial<EvalRunVendorSummary> | null): EvalRunVendorSummary {
  return {
    vendor,
    completed: typeof raw?.completed === 'number' ? raw.completed : 0,
    passed: typeof raw?.passed === 'number' ? raw.passed : 0,
    timeoutCount: typeof raw?.timeoutCount === 'number' ? raw.timeoutCount : 0,
    failureCount: typeof raw?.failureCount === 'number' ? raw.failureCount : 0,
    avgCer: typeof raw?.avgCer === 'number' ? raw.avgCer : null,
    avgWer: typeof raw?.avgWer === 'number' ? raw.avgWer : null,
    avgFirstLatencyMs: typeof raw?.avgFirstLatencyMs === 'number' ? raw.avgFirstLatencyMs : null,
    avgFinalLatencyMs: typeof raw?.avgFinalLatencyMs === 'number' ? raw.avgFinalLatencyMs : null,
    entityAccuracy: typeof raw?.entityAccuracy === 'number' ? raw.entityAccuracy : null,
    passRate: typeof raw?.passRate === 'number' ? raw.passRate : null,
    timeoutRate: typeof raw?.timeoutRate === 'number' ? raw.timeoutRate : null,
    failureRate: typeof raw?.failureRate === 'number' ? raw.failureRate : null,
  }
}

function normalizeRunSummary(raw: Partial<EvalRunSummary> | null | undefined, selectedVendors: Vendor[], totalCases: number): EvalRunSummary {
  const vendorMap = new Map((raw?.vendors ?? []).map(summary => [summary.vendor, summary]))
  return {
    totalCases: typeof raw?.totalCases === 'number' ? raw.totalCases : totalCases,
    completedCases: typeof raw?.completedCases === 'number' ? raw.completedCases : 0,
    passedCases: typeof raw?.passedCases === 'number' ? raw.passedCases : 0,
    allPassedCases: typeof raw?.allPassedCases === 'number' ? raw.allPassedCases : (typeof raw?.passedCases === 'number' ? raw.passedCases : 0),
    anyPassedCases: typeof raw?.anyPassedCases === 'number' ? raw.anyPassedCases : 0,
    noPassedCases: typeof raw?.noPassedCases === 'number' ? raw.noPassedCases : 0,
    failedCases: typeof raw?.failedCases === 'number' ? raw.failedCases : 0,
    timeoutCases: typeof raw?.timeoutCases === 'number' ? raw.timeoutCases : 0,
    totalVendors: typeof raw?.totalVendors === 'number' ? raw.totalVendors : totalCases * selectedVendors.length,
    doneVendors: typeof raw?.doneVendors === 'number' ? raw.doneVendors : 0,
    passedVendors: typeof raw?.passedVendors === 'number' ? raw.passedVendors : 0,
    timeoutVendors: typeof raw?.timeoutVendors === 'number' ? raw.timeoutVendors : 0,
    failureVendors: typeof raw?.failureVendors === 'number' ? raw.failureVendors : 0,
    vendors: selectedVendors.map(vendor => normalizeVendorSummary(vendor, vendorMap.get(vendor))),
  }
}

function normalizeRunRecord(raw: EvalRunRecord): EvalRunRecord {
  const selectedVendors = (Array.isArray(raw.selectedVendors) ? raw.selectedVendors : []).filter(Boolean) as Vendor[]
  const cases = (Array.isArray(raw.cases) ? raw.cases : []).map((item) => {
    const vendorMap = item?.vendors ?? {} as Record<Vendor, Partial<VendorEvalResult>>
    return {
      ...item,
      vendors: Object.fromEntries(selectedVendors.map(vendor => [vendor, normalizeVendorResult(vendor, vendorMap[vendor])])) as Record<Vendor, VendorEvalResult>,
    }
  })

  return {
    ...raw,
    selectedVendors,
    selectedCaseIds: Array.isArray(raw.selectedCaseIds) ? raw.selectedCaseIds : [],
    logs: Array.isArray(raw.logs) ? raw.logs : [],
    cases,
    summary: normalizeRunSummary(raw.summary, selectedVendors, cases.length),
  }
}

function normalizeRunListItem(raw: EvalRunListItem): EvalRunListItem {
  const selectedVendors = (Array.isArray(raw.selectedVendors) ? raw.selectedVendors : []).filter(Boolean) as Vendor[]
  const totalCases = typeof raw.summary?.totalCases === 'number' ? raw.summary.totalCases : 0
  return {
    ...raw,
    selectedVendors,
    selectedCaseIds: Array.isArray(raw.selectedCaseIds) ? raw.selectedCaseIds : [],
    summary: normalizeRunSummary(raw.summary, selectedVendors, totalCases),
  }
}

export async function listEvalRuns(): Promise<EvalRunListItem[]> {
  const res = await fetch(BASE)
  if (!res.ok) throw new Error(`评估历史加载失败: ${res.status}`)
  return (await res.json()).map(normalizeRunListItem)
}

export async function getEvalRun(runId: string): Promise<EvalRunRecord> {
  const res = await fetch(`${BASE}/${runId}`)
  if (!res.ok) throw new Error(`评估任务加载失败: ${res.status}`)
  return normalizeRunRecord(await res.json())
}

export async function createEvalRun(payload: EvalRunCreatePayload): Promise<EvalRunRecord> {
  const res = await fetch(BASE, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  })
  if (!res.ok) throw new Error(`创建评估任务失败: ${res.status}`)
  return normalizeRunRecord(await res.json())
}

async function commandEvalRun(runId: string, command: 'pause' | 'resume' | 'stop'): Promise<EvalRunRecord> {
  const res = await fetch(`${BASE}/${runId}/${command}`, { method: 'POST' })
  if (!res.ok) throw new Error(`任务${command}失败: ${res.status}`)
  return normalizeRunRecord(await res.json())
}

export function pauseEvalRun(runId: string): Promise<EvalRunRecord> {
  return commandEvalRun(runId, 'pause')
}

export function resumeEvalRun(runId: string): Promise<EvalRunRecord> {
  return commandEvalRun(runId, 'resume')
}

export function stopEvalRun(runId: string): Promise<EvalRunRecord> {
  return commandEvalRun(runId, 'stop')
}

export async function rerunEvalCase(runId: string, caseId: string): Promise<EvalRunRecord> {
  const res = await fetch(`${BASE}/${runId}/cases/${caseId}/rerun`, { method: 'POST' })
  if (!res.ok) throw new Error(`重跑 case 失败: ${res.status}`)
  return normalizeRunRecord(await res.json())
}

export async function rescoreEvalRun(runId: string): Promise<EvalRunRecord> {
  const res = await fetch(`${BASE}/${runId}/rescore`, { method: 'POST' })
  if (!res.ok) throw new Error(`重新评分失败: ${res.status}`)
  return normalizeRunRecord(await res.json())
}

export async function updateEvalRun(runId: string, payload: EvalRunRecord): Promise<EvalRunRecord> {
  const res = await fetch(`${BASE}/${runId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  })
  if (!res.ok) throw new Error(`保存评估任务失败: ${res.status}`)
  return normalizeRunRecord(await res.json())
}

export async function deleteEvalRun(runId: string): Promise<void> {
  const res = await fetch(`${BASE}/${runId}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`删除评估任务失败: ${res.status}`)
}

export async function exportEvalRun(runId: string, format: 'json' | 'csv' = 'json'): Promise<Blob> {
  const res = await fetch(`${BASE}/${runId}/export?format=${format}`)
  if (!res.ok) throw new Error(`导出失败: ${res.status}`)
  return res.blob()
}
