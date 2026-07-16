import type { TestCase } from '../api/benchCases'

export type Vendor = 'stepfun' | 'volc' | 'aliyun'
export type CaseType = 'number' | 'money' | 'name' | 'sentence' | 'mixed' | 'noise' | 'custom'
export type PassRuleType = 'cer' | 'entity' | 'mixed'
export type EvalTextMode = 'strict' | 'loose'
export type CaseSource = 'backend' | 'local'
export type RunStatus = 'idle' | 'running' | 'pausing' | 'paused' | 'completed' | 'stopped' | 'failed'
export type VendorResultStatus = 'idle' | 'running' | 'done' | 'failed' | 'timeout' | 'paused'
export type CasePhase = 'queued' | 'fetching_audio' | 'sending_audio' | 'recognizing' | 'done' | 'failed' | 'timeout' | 'paused'

export const VENDOR_LIST: Array<{ key: Vendor; label: string }> = [
  { key: 'stepfun', label: 'Stepfun' },
  { key: 'volc', label: '豆包 / 火山' },
  { key: 'aliyun', label: '阿里云' },
]

export const CASE_TYPE_LIST: Array<{ key: CaseType; label: string }> = [
  { key: 'sentence', label: '普通句子' },
  { key: 'number', label: '数字' },
  { key: 'money', label: '金额' },
  { key: 'name', label: '人名' },
  { key: 'mixed', label: '中英混合' },
  { key: 'noise', label: '噪声' },
  { key: 'custom', label: '自定义' },
]

export const PASS_RULE_LIST: Array<{ key: PassRuleType; label: string }> = [
  { key: 'cer', label: 'CER 阈值' },
  { key: 'entity', label: '关键实体' },
  { key: 'mixed', label: 'CER + 关键实体' },
]

export const CASE_PHASE_LABELS: Record<CasePhase, string> = {
  queued: '排队中',
  fetching_audio: '取音频',
  sending_audio: '发送中',
  recognizing: '识别中',
  done: '完成',
  failed: '失败',
  timeout: '超时',
  paused: '已暂停',
}

export interface EvalCaseConfig {
  id: string
  source: CaseSource
  backendId?: string
  name: string
  note: string
  caseType: CaseType
  referenceText: string
  criticalTermsText: string
  acceptableTextsText: string
  sourceCaseId?: string
  noiseProfile?: string
  noiseType?: string
  targetSnrDb?: number
  passRuleType: PassRuleType
  passThreshold: number
  enabled: boolean
  audioFile?: File
  audioFileName?: string
  audioMimeType?: string
  durationSeconds?: number
  backendAudioExt?: string
  hasAudio: boolean
}

export interface VendorEvalResult {
  vendor: Vendor
  status: VendorResultStatus
  phase: CasePhase
  textMode: EvalTextMode
  transcript: string
  normalizedTranscript: string
  normalizedReference: string
  firstLatencyMs: number | null
  finalLatencyMs: number | null
  cer: number | null
  wer: number | null
  sentenceAccuracy: boolean | null
  entityAccuracy: number | null
  entityMatchedCount: number | null
  entityTotalCount: number | null
  entityMissedTerms: string[]
  characterSubstitutions: number | null
  characterInsertions: number | null
  characterDeletions: number | null
  wordSubstitutions: number | null
  wordInsertions: number | null
  wordDeletions: number | null
  pass: boolean | null
  passReason?: string | null
  errorMsg: string
  normalizerVersion?: string
  scoringVersion?: string
  referenceVariantUsed?: string
  failureStage?: 'decode' | 'recognition' | 'scoring'
}

export interface CaseEvalRecord {
  caseId: string
  caseName: string
  referenceText: string
  caseType: CaseType
  vendors: Record<Vendor, VendorEvalResult>
}

export interface VendorSummary {
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
  winCount?: number
  score?: number | null
  rank?: number
}

export function guessCaseType(name: string): CaseType {
  const lower = name.toLowerCase()
  if (/金额|价格|付款|转账|钱|price|amount/.test(name)) return 'money'
  if (/数字|号码|电话|phone|mobile|number/.test(name) || /\d/.test(name) && name.length < 20) return 'number'
  if (/姓名|人名|name|联系人/.test(name)) return 'name'
  if (/中英|mixed|english|英文/.test(lower)) return 'mixed'
  if (/噪音|noise|环境/.test(lower)) return 'noise'
  return 'sentence'
}

export function defaultPassRule(caseType: CaseType): PassRuleType {
  if (caseType === 'number' || caseType === 'money' || caseType === 'name') return 'entity'
  if (caseType === 'mixed') return 'mixed'
  return 'cer'
}

export function createBlankCase(): EvalCaseConfig {
  return {
    id: `local_${crypto.randomUUID()}`,
    source: 'local',
    name: '新 case',
    note: '',
    caseType: 'sentence',
    referenceText: '',
    criticalTermsText: '',
    acceptableTextsText: '',
    passRuleType: 'cer',
    passThreshold: 0.2,
    enabled: true,
    hasAudio: false,
  }
}

export function backendCaseToEvalCase(item: TestCase): EvalCaseConfig {
  const guessedCaseType = guessCaseType(item.name)
  const caseType = (item.caseType as CaseType | undefined) ?? guessedCaseType
  return {
    id: item.id,
    source: 'backend',
    backendId: item.id,
    name: item.name,
    note: item.note ?? '',
    caseType,
    referenceText: item.referenceText ?? '',
    criticalTermsText: item.criticalTermsText ?? '',
    acceptableTextsText: item.acceptableTextsText ?? '',
    sourceCaseId: item.sourceCaseId,
    noiseProfile: item.noiseProfile,
    noiseType: item.noiseType,
    targetSnrDb: item.targetSnrDb,
    passRuleType: (item.passRuleType as PassRuleType | undefined) ?? defaultPassRule(caseType),
    passThreshold: typeof item.passThreshold === 'number' ? item.passThreshold : 0.2,
    enabled: item.enabled ?? true,
    durationSeconds: item.durationSeconds,
    audioFileName: item.hasAudio === false ? undefined : (item.originalFileName || undefined),
    backendAudioExt: item.hasAudio === false ? undefined : (item.audioExt || undefined),
    hasAudio: item.hasAudio ?? Boolean(item.originalFileName && item.audioExt),
  }
}

export function getVendorLabel(vendor: Vendor): string {
  return VENDOR_LIST.find(item => item.key === vendor)?.label ?? vendor
}

export function getCaseTypeLabel(caseType: CaseType): string {
  return CASE_TYPE_LIST.find(item => item.key === caseType)?.label ?? caseType
}

export function getPassRuleLabel(rule: PassRuleType): string {
  return PASS_RULE_LIST.find(item => item.key === rule)?.label ?? rule
}

export function parseCriticalTerms(text: string): string[] {
  return text
    .split(/[\n,，;；|]/g)
    .map(item => item.trim())
    .filter(Boolean)
}

export interface EntityScore {
  totalCount: number
  matchedCount: number
  accuracy: number | null
  missedTerms: string[]
}

export function calculateEntityScore(_reference: string, hypothesis: string, criticalTerms: string[], mode: EvalTextMode = 'loose'): EntityScore | null {
  const terms = criticalTerms.map(item => normalizeForCharacterScore(item, mode)).filter(Boolean)
  if (terms.length === 0) return null

  const normalizedHypothesis = normalizeForCharacterScore(hypothesis, mode)
  const missedTerms: string[] = []
  let matchedCount = 0

  for (const term of terms) {
    if (normalizedHypothesis.includes(term)) {
      matchedCount += 1
    } else {
      missedTerms.push(term)
    }
  }

  return {
    totalCount: terms.length,
    matchedCount,
    accuracy: terms.length > 0 ? matchedCount / terms.length : null,
    missedTerms,
  }
}

export function normalizeLooseText(input: string): string {
  return input
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[“”‘’'"]/g, '')
    .replace(/[，。！？、：；,.!?;:()\[\]{}<>《》【】]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

export function normalizeStrictText(input: string): string {
  return input.normalize('NFKC').trim()
}

export function normalizeText(input: string): string {
  return normalizeLooseText(input)
}

function normalizeByMode(input: string, mode: EvalTextMode): string {
  return mode === 'strict' ? normalizeStrictText(input) : normalizeLooseText(input)
}

function normalizeForCharacterScore(input: string, mode: EvalTextMode): string {
  return normalizeByMode(input, mode).replace(/\s+/g, '')
}

function tokenizeForWer(input: string, mode: EvalTextMode): string[] {
  const normalized = normalizeByMode(input, mode)
  if (!normalized) return []

  const tokens: string[] = []
  let buffer = ''

  const flush = () => {
    if (buffer) {
      tokens.push(buffer)
      buffer = ''
    }
  }

  for (const char of normalized) {
    if (/\s/.test(char)) {
      flush()
      continue
    }
    if (/[\u4e00-\u9fff]/.test(char)) {
      flush()
      tokens.push(char)
      continue
    }
    if (/[a-z0-9]/.test(char)) {
      buffer += char
      continue
    }
    flush()
  }

  flush()
  return tokens
}

interface EditStats {
  substitutions: number
  insertions: number
  deletions: number
  distance: number
}

function calculateEditStats(reference: string[], hypothesis: string[]): EditStats {
  const m = reference.length
  const n = hypothesis.length
  const dp = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0))
  const op = Array.from({ length: m + 1 }, () => new Array<'none' | 'equal' | 'sub' | 'ins' | 'del'>(n + 1).fill('none'))

  for (let i = 1; i <= m; i += 1) {
    dp[i][0] = i
    op[i][0] = 'del'
  }

  for (let j = 1; j <= n; j += 1) {
    dp[0][j] = j
    op[0][j] = 'ins'
  }

  for (let i = 1; i <= m; i += 1) {
    for (let j = 1; j <= n; j += 1) {
      if (reference[i - 1] === hypothesis[j - 1]) {
        dp[i][j] = dp[i - 1][j - 1]
        op[i][j] = 'equal'
        continue
      }

      const sub = dp[i - 1][j - 1] + 1
      const del = dp[i - 1][j] + 1
      const ins = dp[i][j - 1] + 1
      const min = Math.min(sub, del, ins)

      dp[i][j] = min
      if (min === sub) {
        op[i][j] = 'sub'
      } else if (min === del) {
        op[i][j] = 'del'
      } else {
        op[i][j] = 'ins'
      }
    }
  }

  let substitutions = 0
  let insertions = 0
  let deletions = 0
  let i = m
  let j = n

  while (i > 0 || j > 0) {
    const current = op[i][j]
    if (current === 'equal' || current === 'sub') {
      if (current === 'sub') substitutions += 1
      i -= 1
      j -= 1
      continue
    }
    if (current === 'del') {
      deletions += 1
      i -= 1
      continue
    }
    if (current === 'ins') {
      insertions += 1
      j -= 1
      continue
    }

    if (i > 0) {
      deletions += 1
      i -= 1
    } else if (j > 0) {
      insertions += 1
      j -= 1
    }
  }

  return {
    substitutions,
    insertions,
    deletions,
    distance: dp[m][n],
  }
}

function safeRate(distance: number, base: number): number {
  if (base <= 0) return 0
  return distance / base
}

export function calculateCer(reference: string, hypothesis: string, mode: EvalTextMode = 'loose'): number {
  const ref = normalizeForCharacterScore(reference, mode).split('').filter(Boolean)
  const hyp = normalizeForCharacterScore(hypothesis, mode).split('').filter(Boolean)
  if (ref.length === 0) return hyp.length === 0 ? 0 : 1
  return safeRate(calculateEditStats(ref, hyp).distance, ref.length)
}

export function calculateWer(reference: string, hypothesis: string, mode: EvalTextMode = 'loose'): number {
  const ref = tokenizeForWer(reference, mode)
  const hyp = tokenizeForWer(hypothesis, mode)
  if (ref.length === 0) return hyp.length === 0 ? 0 : 1
  return safeRate(calculateEditStats(ref, hyp).distance, ref.length)
}

export function calculateSentenceAccuracy(reference: string, hypothesis: string, mode: EvalTextMode = 'loose'): boolean {
  return normalizeByMode(reference, mode) === normalizeByMode(hypothesis, mode)
}

export function calculateEntityAccuracy(reference: string, hypothesis: string, criticalTerms: string[], mode: EvalTextMode = 'loose'): number | null {
  return calculateEntityScore(reference, hypothesis, criticalTerms, mode)?.accuracy ?? null
}

export function derivePass(
  passRuleType: PassRuleType,
  threshold: number,
  metrics: { cer: number; entityAccuracy: number | null; sentenceAccuracy: boolean }
): boolean {
  if (passRuleType === 'entity') {
    return metrics.entityAccuracy === null ? metrics.cer <= threshold : metrics.entityAccuracy === 1
  }

  if (passRuleType === 'mixed') {
    return metrics.cer <= threshold && (metrics.entityAccuracy === null || metrics.entityAccuracy === 1)
  }

  return metrics.cer <= threshold
}

export function evaluateVendorResult(params: {
  vendor: Vendor
  transcript: string
  referenceText: string
  criticalTerms: string[]
  passRuleType: PassRuleType
  passThreshold: number
  textMode?: EvalTextMode
  firstLatencyMs: number | null
  finalLatencyMs: number | null
  errorMsg?: string
}): VendorEvalResult {
  const textMode = params.textMode ?? 'loose'
  const normalizedTranscript = normalizeByMode(params.transcript, textMode)
  const normalizedReference = normalizeByMode(params.referenceText, textMode)
  const cer = calculateCer(params.referenceText, params.transcript, textMode)
  const wer = calculateWer(params.referenceText, params.transcript, textMode)
  const sentenceAccuracy = calculateSentenceAccuracy(params.referenceText, params.transcript, textMode)
  const entityScore = calculateEntityScore(params.referenceText, params.transcript, params.criticalTerms, textMode)
  const entityAccuracy = entityScore?.accuracy ?? null
  const pass = params.errorMsg
    ? false
    : derivePass(params.passRuleType, params.passThreshold, { cer, entityAccuracy, sentenceAccuracy })

  return {
    vendor: params.vendor,
    status: params.errorMsg ? 'failed' : 'done',
    phase: params.errorMsg ? 'failed' : 'done',
    textMode,
    transcript: params.transcript,
    normalizedTranscript,
    normalizedReference,
    firstLatencyMs: params.firstLatencyMs,
    finalLatencyMs: params.finalLatencyMs,
    cer: Number.isFinite(cer) ? cer : null,
    wer: Number.isFinite(wer) ? wer : null,
    sentenceAccuracy,
    entityAccuracy,
    entityMatchedCount: entityScore?.matchedCount ?? null,
    entityTotalCount: entityScore?.totalCount ?? null,
    entityMissedTerms: entityScore?.missedTerms ?? [],
    characterSubstitutions: null,
    characterInsertions: null,
    characterDeletions: null,
    wordSubstitutions: null,
    wordInsertions: null,
    wordDeletions: null,
    pass,
    errorMsg: params.errorMsg ?? '',
  }
}

export function createIdleVendorResult(vendor: Vendor): VendorEvalResult {
  return {
    vendor,
    status: 'idle',
    phase: 'queued',
    textMode: 'loose',
    transcript: '',
    normalizedTranscript: '',
    normalizedReference: '',
    firstLatencyMs: null,
    finalLatencyMs: null,
    cer: null,
    wer: null,
    sentenceAccuracy: null,
    entityAccuracy: null,
    entityMatchedCount: null,
    entityTotalCount: null,
    entityMissedTerms: [],
    characterSubstitutions: null,
    characterInsertions: null,
    characterDeletions: null,
    wordSubstitutions: null,
    wordInsertions: null,
    wordDeletions: null,
    pass: null,
    errorMsg: '',
  }
}

export function createProgressVendorResult(vendor: Vendor, phase: CasePhase, message = ''): VendorEvalResult {
  const status: VendorResultStatus = phase === 'paused' ? 'paused' : phase === 'failed' ? 'failed' : phase === 'timeout' ? 'timeout' : 'running'
  return {
    ...createIdleVendorResult(vendor),
    status,
    phase,
    errorMsg: message,
  }
}

export function createCaseRecord(caseItem: EvalCaseConfig): CaseEvalRecord {
  return {
    caseId: caseItem.id,
    caseName: caseItem.name,
    referenceText: caseItem.referenceText,
    caseType: caseItem.caseType,
    vendors: {
      stepfun: createIdleVendorResult('stepfun'),
      volc: createIdleVendorResult('volc'),
      aliyun: createIdleVendorResult('aliyun'),
    },
  }
}

export function computeVendorSummaries(records: CaseEvalRecord[], vendors: Vendor[]): VendorSummary[] {
  return vendors.map((vendor) => {
    const vendorResults = records
      .map(record => record.vendors[vendor])
      .filter(result => result.status === 'done' || result.status === 'failed' || result.status === 'timeout')

    const completed = vendorResults.length
    const passed = vendorResults.filter(result => result.pass).length
    const timeoutCount = vendorResults.filter(result => result.status === 'timeout').length
    const failureCount = vendorResults.filter(result => result.status === 'failed').length

    const cerValues = vendorResults.map(result => result.cer).filter((value): value is number => typeof value === 'number')
    const werValues = vendorResults.map(result => result.wer).filter((value): value is number => typeof value === 'number')
    const firstLatencyValues = vendorResults.map(result => result.firstLatencyMs).filter((value): value is number => typeof value === 'number')
    const finalLatencyValues = vendorResults.map(result => result.finalLatencyMs).filter((value): value is number => typeof value === 'number')
    const entityValues = vendorResults.map(result => result.entityAccuracy).filter((value): value is number => typeof value === 'number')

    const avg = (values: number[]): number | null => {
      if (values.length === 0) return null
      return values.reduce((sum, value) => sum + value, 0) / values.length
    }

    return {
      vendor,
      completed,
      passed,
      timeoutCount,
      failureCount,
      avgCer: avg(cerValues),
      avgWer: avg(werValues),
      avgFirstLatencyMs: avg(firstLatencyValues),
      avgFinalLatencyMs: avg(finalLatencyValues),
      entityAccuracy: avg(entityValues),
      passRate: completed > 0 ? passed / completed : null,
    }
  })
}

export function calculateVendorScore(summary: Pick<VendorSummary, 'passRate' | 'avgCer' | 'avgWer' | 'entityAccuracy' | 'avgFirstLatencyMs' | 'avgFinalLatencyMs'>): number | null {
  const metrics = [
    summary.passRate,
    summary.avgCer === null ? null : 1 - Math.min(summary.avgCer, 1),
    summary.avgWer === null ? null : 1 - Math.min(summary.avgWer, 1),
    summary.entityAccuracy,
    summary.avgFirstLatencyMs === null ? null : 1 - Math.min(summary.avgFirstLatencyMs / 5000, 1),
    summary.avgFinalLatencyMs === null ? null : 1 - Math.min(summary.avgFinalLatencyMs / 10000, 1),
  ] as Array<number | null>

  const weights = [0.35, 0.25, 0.12, 0.12, 0.08, 0.08]
  let weightSum = 0
  let score = 0
  metrics.forEach((value, idx) => {
    if (value === null || Number.isNaN(value)) return
    const weight = weights[idx] ?? 0
    score += value * weight
    weightSum += weight
  })
  if (weightSum === 0) return null
  return score / weightSum
}

export function calculateResultScore(result: Pick<VendorEvalResult, 'pass' | 'cer' | 'wer' | 'entityAccuracy' | 'firstLatencyMs' | 'finalLatencyMs'>): number | null {
  const metrics = [
    result.pass === null ? null : (result.pass ? 1 : 0),
    result.cer === null ? null : 1 - Math.min(result.cer, 1),
    result.wer === null ? null : 1 - Math.min(result.wer, 1),
    result.entityAccuracy,
    result.firstLatencyMs === null ? null : 1 - Math.min(result.firstLatencyMs / 5000, 1),
    result.finalLatencyMs === null ? null : 1 - Math.min(result.finalLatencyMs / 10000, 1),
  ] as Array<number | null>

  const weights = [0.4, 0.22, 0.12, 0.12, 0.06, 0.08]
  let weightSum = 0
  let score = 0
  metrics.forEach((value, idx) => {
    if (value === null || Number.isNaN(value)) return
    const weight = weights[idx] ?? 0
    score += value * weight
    weightSum += weight
  })
  if (weightSum === 0) return null
  return score / weightSum
}

export function compareVendorResults(a: VendorEvalResult, b: VendorEvalResult): number {
  const passA = a.pass ? 1 : 0
  const passB = b.pass ? 1 : 0
  if (passA !== passB) return passB - passA
  const cerA = a.cer ?? Number.POSITIVE_INFINITY
  const cerB = b.cer ?? Number.POSITIVE_INFINITY
  if (cerA !== cerB) return cerA - cerB
  const finalA = a.finalLatencyMs ?? Number.POSITIVE_INFINITY
  const finalB = b.finalLatencyMs ?? Number.POSITIVE_INFINITY
  if (finalA !== finalB) return finalA - finalB
  return a.vendor.localeCompare(b.vendor)
}

export function pickBestVendorResult(results: VendorEvalResult[]): VendorEvalResult | null {
  if (results.length === 0) return null
  return [...results].sort(compareVendorResults)[0] ?? null
}

export function compareVendorSummary(a: VendorSummary, b: VendorSummary): number {
  const passA = a.passRate ?? Number.NEGATIVE_INFINITY
  const passB = b.passRate ?? Number.NEGATIVE_INFINITY
  if (passA !== passB) return passB - passA
  const cerA = a.avgCer ?? Number.POSITIVE_INFINITY
  const cerB = b.avgCer ?? Number.POSITIVE_INFINITY
  if (cerA !== cerB) return cerA - cerB
  const finalA = a.avgFinalLatencyMs ?? Number.POSITIVE_INFINITY
  const finalB = b.avgFinalLatencyMs ?? Number.POSITIVE_INFINITY
  if (finalA !== finalB) return finalA - finalB
  return (a.vendor as string).localeCompare(b.vendor as string)
}

export function pickBestVendor(summaryList: VendorSummary[]): VendorSummary | null {
  if (summaryList.length === 0) return null
  return [...summaryList].sort(compareVendorSummary)[0] ?? null
}

export function formatPercent(value: number | null, digits = 1): string {
  if (value === null || Number.isNaN(value)) return '-'
  return `${(value * 100).toFixed(digits)}%`
}

export function formatLatency(value: number | null): string {
  if (value === null || Number.isNaN(value)) return '-'
  return `${Math.round(value)} ms`
}

export function formatScore(value: number | null): string {
  if (value === null || Number.isNaN(value)) return '-'
  return value.toFixed(3)
}
