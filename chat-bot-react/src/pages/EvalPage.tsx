import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { listCases, fetchCaseAudio, saveCase, updateCase, updateCaseAudio, type CaseMetaUpdate } from '../api/benchCases'
import {
  createEvalRun,
  getEvalRun,
  pauseEvalRun,
  resumeEvalRun,
  stopEvalRun,
  type EvalRunRecord,
} from '../api/evalRuns'
import {
  CASE_TYPE_LIST,
  CASE_PHASE_LABELS,
  PASS_RULE_LIST,
  VENDOR_LIST,
  backendCaseToEvalCase,
  computeVendorSummaries,
  createBlankCase,
  createCaseRecord,
  formatLatency,
  formatPercent,
  getCaseTypeLabel,
  getPassRuleLabel,
  getVendorLabel,
  normalizeText,
  type EvalTextMode,
  type CasePhase,
  type CaseEvalRecord,
  type EvalCaseConfig,
  type PassRuleType,
  type RunStatus,
  type Vendor,
  type VendorEvalResult,
  type VendorSummary,
} from '../lib/asrEval'
import { decodeAudioBlobToPcm, getAudioDurationSeconds, mimeFromName, pcmToDataUrl } from '../lib/audioFrames'
import { diffText } from '../lib/textDiff'
import { EvalModal } from '../components/eval/EvalModal'
import { PressHoldRecorder } from '../components/eval/PressHoldRecorder'
import { EvalRunHistoryContent } from '../components/eval/EvalRunHistoryContent'
import { EvalCasesPage } from './EvalCasesPage'
import { EvalRunPage } from './EvalRunPage'
import {
  CASE_LENGTH_OPTIONS,
  NOISE_SCENARIO_OPTIONS,
  getCaseLengthLabel,
  getNoiseScenarioLabel,
  matchesCaseFilters,
  type CaseLengthFilter,
  type NoiseScenarioFilter,
} from '../lib/caseFilters'
import './EvalPage.css'

const DASHBOARD_CASES_PER_PAGE = 10

type DetailSelection = { caseId: string; vendor: Vendor } | null
type ResultFilter = 'all' | 'failed' | 'unfinished' | 'passed'
type SortKey = 'default' | 'name' | 'cer' | 'latency' | 'status'
type EvalModalState =
  | { type: 'case-manager' }
  | { type: 'case-editor' }
  | { type: 'result-detail' }
  | { type: 'run-history' }
  | { type: 'run-detail'; runId: string }
  | { type: 'run-log' }
  | null

interface LogLine {
  id: string
  time: string
  text: string
}

function nowTime(): string {
  return new Date().toLocaleTimeString([], { hour12: false })
}

function createMetricCard(label: string, value: string, hint?: string) {
  return (
    <div className="eval-card metric-card">
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      {hint ? <div className="metric-hint">{hint}</div> : null}
    </div>
  )
}

function vendorOrderOf(record: CaseEvalRecord, vendors: Vendor[]) {
  const done = vendors.filter(v => record.vendors[v].status === 'done' || record.vendors[v].status === 'failed' || record.vendors[v].status === 'timeout')
  const passed = done.filter(v => record.vendors[v].pass)
  const pool = passed.length > 0 ? passed : done
  if (pool.length === 0) return null
  return [...pool].sort((a, b) => {
    const ar = record.vendors[a]
    const br = record.vendors[b]
    const aCer = ar.cer ?? Number.POSITIVE_INFINITY
    const bCer = br.cer ?? Number.POSITIVE_INFINITY
    if (aCer !== bCer) return aCer - bCer
    return (ar.finalLatencyMs ?? Number.POSITIVE_INFINITY) - (br.finalLatencyMs ?? Number.POSITIVE_INFINITY)
  })[0]
}

function statusBadge(status: VendorEvalResult['status']) {
  if (status === 'done') return <span className="badge badge-success">完成</span>
  if (status === 'failed') return <span className="badge badge-danger">失败</span>
  if (status === 'timeout') return <span className="badge badge-danger">超时</span>
  if (status === 'running') return <span className="badge badge-info">运行中</span>
  if (status === 'paused') return <span className="badge badge-warn">已暂停</span>
  return <span className="badge">待机</span>
}

function phaseBadge(phase: CasePhase) {
  if (phase === 'done') return <span className="badge badge-success">{CASE_PHASE_LABELS[phase]}</span>
  if (phase === 'failed' || phase === 'timeout') return <span className="badge badge-danger">{CASE_PHASE_LABELS[phase]}</span>
  if (phase === 'paused') return <span className="badge badge-warn">{CASE_PHASE_LABELS[phase]}</span>
  if (phase === 'recognizing' || phase === 'sending_audio' || phase === 'fetching_audio') return <span className="badge badge-info">{CASE_PHASE_LABELS[phase]}</span>
  return <span className="badge">{CASE_PHASE_LABELS[phase]}</span>
}

function getCaseFlowState(record: CaseEvalRecord, vendors: Vendor[]) {
  const results = vendors.map(vendor => record.vendors[vendor])
  if (results.some(result => result.status === 'running')) return { key: 'running', label: '运行中' }
  if (results.some(result => result.status === 'paused')) return { key: 'paused', label: '已暂停' }
  if (results.some(result => result.status === 'timeout')) return { key: 'timeout', label: '超时' }
  if (results.some(result => result.status === 'failed')) return { key: 'failed', label: '失败' }
  if (results.every(result => result.status === 'done' || result.status === 'failed' || result.status === 'timeout')) return { key: 'done', label: '已完成' }
  return { key: 'queued', label: '排队中' }
}

function hasReferenceText(record?: { referenceText?: string }) {
  return Boolean(record?.referenceText && record.referenceText.trim())
}

function hasCaseAudio(record?: Pick<EvalCaseConfig, 'audioFile' | 'hasAudio'>) {
  return Boolean(record?.audioFile || record?.hasAudio)
}

function isTerminalStatus(status: VendorEvalResult['status']) {
  return status === 'done' || status === 'failed' || status === 'timeout'
}

function isResultPassable(result: VendorEvalResult) {
  return result.pass === true
}

function formatEditTriple(substitutions: number | null, insertions: number | null, deletions: number | null) {
  if (substitutions === null || insertions === null || deletions === null) return '—'
  return `S${substitutions} / I${insertions} / D${deletions}`
}

export function EvalPage() {
  const [cases, setCases] = useState<EvalCaseConfig[]>([])
  const [selectedCaseIds, setSelectedCaseIds] = useState<string[]>([])
  const [selectedVendors, setSelectedVendors] = useState<Vendor[]>(['stepfun', 'volc', 'aliyun'])
  const [evaluationMode, setEvaluationMode] = useState<EvalTextMode>('loose')
  const [runStatus, setRunStatus] = useState<RunStatus>('idle')
  const [activeTask, setActiveTask] = useState<{ caseId: string; vendor: Vendor } | null>(null)
  const [caseResults, setCaseResults] = useState<Record<string, CaseEvalRecord>>({})
  const [serverVendorSummaries, setServerVendorSummaries] = useState<VendorSummary[] | null>(null)
  const [selectedCaseId, setSelectedCaseId] = useState<string | null>(null)
  const [detailSelection, setDetailSelection] = useState<DetailSelection>(null)
  const [backendLoading, setBackendLoading] = useState(false)
  const [backendError, setBackendError] = useState('')
  const [savingCaseId, setSavingCaseId] = useState<string | null>(null)
  const [currentRunId, setCurrentRunId] = useState<string | null>(null)
  const [runName, setRunName] = useState(`评估任务 ${new Date().toLocaleDateString()}`)
  const [logs, setLogs] = useState<LogLine[]>([])
  const [resultFilter, setResultFilter] = useState<ResultFilter>('all')
  const [sortKey, setSortKey] = useState<SortKey>('default')
  const [modalState, setModalState] = useState<EvalModalState>(null)
  const [detailAudioUrl, setDetailAudioUrl] = useState<string | null>(null)
  const [detailAudioLoading, setDetailAudioLoading] = useState(false)
  const [playingCaseId, setPlayingCaseId] = useState<string | null>(null)
  const [matrixAudioLoadingCaseId, setMatrixAudioLoadingCaseId] = useState<string | null>(null)
  const [caseListPage, setCaseListPage] = useState(1)
  const [caseLengthFilter, setCaseLengthFilter] = useState<CaseLengthFilter>('all')
  const [noiseScenarioFilter, setNoiseScenarioFilter] = useState<NoiseScenarioFilter>('all')

  const casesRef = useRef<EvalCaseConfig[]>([])
  const matrixAudioRef = useRef<{ caseId: string; audio: HTMLAudioElement; url: string } | null>(null)
  const matrixAudioRequestRef = useRef(0)

  casesRef.current = cases

  const addLog = useCallback((text: string) => {
    setLogs(prev => [
      ...prev,
      { id: `${Date.now()}_${Math.random()}`, time: nowTime(), text },
    ].slice(-40))
  }, [])

  const syncBackendCases = useCallback(async () => {
    setBackendLoading(true)
    setBackendError('')
    try {
      const backend = await listCases()
      const currentCases = casesRef.current
      const localCases = currentCases.filter(item => item.source === 'local')
      const mergedBackend = backend.map((item): EvalCaseConfig => {
        const existing = currentCases.find(c => c.id === item.id)
        const next = backendCaseToEvalCase(item)
        if (!existing) return next
        return {
          ...next,
          ...existing,
          id: next.id,
          source: 'backend' as const,
          backendId: next.id,
          audioFileName: existing.audioFile ? existing.audioFileName : next.audioFileName,
          backendAudioExt: next.backendAudioExt,
          durationSeconds: existing.durationSeconds ?? next.durationSeconds,
          hasAudio: existing.audioFile ? true : next.hasAudio,
        }
      })
      const merged = [...mergedBackend, ...localCases]
      setCases(merged)
      setCaseListPage(1)
      setSelectedCaseIds(prevIds => {
        if (prevIds.length === 0) {
          return merged.filter(item => item.enabled && hasCaseAudio(item)).map(item => item.id)
        }
        return prevIds.filter(id => merged.some(item => item.id === id && hasCaseAudio(item)))
      })
      setSelectedCaseId(prev => prev ?? merged[0]?.id ?? null)
    } catch (err) {
      setBackendError(err instanceof Error ? err.message : '加载后端样本失败')
    } finally {
      setBackendLoading(false)
    }
  }, [])

  useEffect(() => {
    void syncBackendCases()
  }, [syncBackendCases])

  const selectedCases = useMemo(() => {
    const map = new Map(cases.map(item => [item.id, item]))
    return selectedCaseIds.map(id => map.get(id)).filter((item): item is EvalCaseConfig => Boolean(item))
  }, [cases, selectedCaseIds])

  const filteredDashboardCases = useMemo(
    () => cases.filter(item => matchesCaseFilters(item, caseLengthFilter, noiseScenarioFilter)),
    [caseLengthFilter, cases, noiseScenarioFilter],
  )
  const caseListTotalPages = Math.max(1, Math.ceil(filteredDashboardCases.length / DASHBOARD_CASES_PER_PAGE))
  const visibleDashboardCases = useMemo(() => {
    const start = (caseListPage - 1) * DASHBOARD_CASES_PER_PAGE
    return filteredDashboardCases.slice(start, start + DASHBOARD_CASES_PER_PAGE)
  }, [caseListPage, filteredDashboardCases])
  const caseListStart = filteredDashboardCases.length === 0 ? 0 : (caseListPage - 1) * DASHBOARD_CASES_PER_PAGE + 1
  const caseListEnd = Math.min(caseListPage * DASHBOARD_CASES_PER_PAGE, filteredDashboardCases.length)

  useEffect(() => {
    setCaseListPage(prev => Math.min(prev, caseListTotalPages))
  }, [caseListTotalPages])

  const activeRecords = useMemo(() => {
    return selectedCases.map(item => caseResults[item.id] ?? createCaseRecord(item))
  }, [caseResults, selectedCases])

  const vendorSummaries = useMemo(
    () => serverVendorSummaries ?? computeVendorSummaries(activeRecords, selectedVendors),
    [activeRecords, selectedVendors, serverVendorSummaries],
  )

  const overallStats = useMemo(() => {
    const completed = activeRecords.filter(record =>
      selectedVendors.every(vendor => isTerminalStatus(record.vendors[vendor].status))
    ).length
    const totalVendors = selectedCases.length * selectedVendors.length
    const terminalResults = activeRecords.flatMap(record => selectedVendors.map(vendor => record.vendors[vendor])).filter(result => isTerminalStatus(result.status))
    const doneVendors = terminalResults.length
    const passedVendors = activeRecords.flatMap(record => selectedVendors.map(vendor => record.vendors[vendor])).filter(result => result.pass).length
    const timeoutVendors = terminalResults.filter(result => result.status === 'timeout').length
    const failureVendors = terminalResults.filter(result => result.status === 'failed').length
    const cerValues = activeRecords.flatMap(record => selectedVendors.map(vendor => record.vendors[vendor].cer)).filter((value): value is number => typeof value === 'number')
    const firstLatencyValues = activeRecords.flatMap(record => selectedVendors.map(vendor => record.vendors[vendor].firstLatencyMs)).filter((value): value is number => typeof value === 'number')
    const finalLatencyValues = activeRecords.flatMap(record => selectedVendors.map(vendor => record.vendors[vendor].finalLatencyMs)).filter((value): value is number => typeof value === 'number')
    const avgCer = cerValues.length ? cerValues.reduce((sum, value) => sum + value, 0) / cerValues.length : null
    const avgFirstLatencyMs = firstLatencyValues.length ? firstLatencyValues.reduce((sum, value) => sum + value, 0) / firstLatencyValues.length : null
    const avgFinalLatencyMs = finalLatencyValues.length ? finalLatencyValues.reduce((sum, value) => sum + value, 0) / finalLatencyValues.length : null
    return {
      completedCases: completed,
      totalCases: selectedCases.length,
      doneVendors,
      totalVendors,
      passedVendors,
      timeoutVendors,
      failureVendors,
      avgCer,
      avgFirstLatencyMs,
      avgFinalLatencyMs,
      timeoutRate: totalVendors > 0 ? timeoutVendors / totalVendors : null,
      failureRate: totalVendors > 0 ? failureVendors / totalVendors : null,
    }
  }, [activeRecords, selectedCases.length, selectedVendors])

  const casesMissingReference = useMemo(() => {
    return selectedCases.filter(item => !hasReferenceText(item)).map(item => item.id)
  }, [selectedCases])

  const casesMissingAudio = useMemo(() => {
    return selectedCases.filter(item => !hasCaseAudio(item)).map(item => item.id)
  }, [selectedCases])

  const canStartRun = selectedCases.length > 0
    && selectedVendors.length > 0
    && casesMissingReference.length === 0
    && casesMissingAudio.length === 0

  const visibleRecords = useMemo(() => {
    let next = [...activeRecords]

    if (resultFilter === 'failed') {
      next = next.filter(record => selectedVendors.some(vendor => {
        const result = record.vendors[vendor]
        return result.status === 'failed' || result.status === 'timeout'
      }))
    } else if (resultFilter === 'unfinished') {
      next = next.filter(record => selectedVendors.some(vendor => !isTerminalStatus(record.vendors[vendor].status)))
    } else if (resultFilter === 'passed') {
      next = next.filter(record => selectedVendors.every(vendor => isResultPassable(record.vendors[vendor])))
    }

    const compareValue = (record: CaseEvalRecord) => {
      const results = selectedVendors.map(vendor => record.vendors[vendor])
      const cerValues = results.map(result => result.cer).filter((value): value is number => typeof value === 'number')
      const latencyValues = results.map(result => result.finalLatencyMs).filter((value): value is number => typeof value === 'number')
      const terminalCount = results.filter(result => isTerminalStatus(result.status)).length
      const passedCount = results.filter(result => result.pass).length
      return {
        name: record.caseName.toLowerCase(),
        cer: cerValues.length ? cerValues.reduce((sum, value) => sum + value, 0) / cerValues.length : Number.POSITIVE_INFINITY,
        latency: latencyValues.length ? latencyValues.reduce((sum, value) => sum + value, 0) / latencyValues.length : Number.POSITIVE_INFINITY,
        status: terminalCount === 0 ? 0 : passedCount === selectedVendors.length ? 3 : passedCount > 0 ? 2 : 1,
      }
    }

    if (sortKey === 'name') {
      next.sort((a, b) => compareValue(a).name.localeCompare(compareValue(b).name))
    } else if (sortKey === 'cer') {
      next.sort((a, b) => compareValue(a).cer - compareValue(b).cer)
    } else if (sortKey === 'latency') {
      next.sort((a, b) => compareValue(a).latency - compareValue(b).latency)
    } else if (sortKey === 'status') {
      next.sort((a, b) => compareValue(b).status - compareValue(a).status)
    }

    return next
  }, [activeRecords, resultFilter, selectedVendors, sortKey])

  const upsertCase = useCallback((caseId: string, updater: (item: EvalCaseConfig) => EvalCaseConfig) => {
    setCases(prev => prev.map(item => (item.id === caseId ? updater(item) : item)))
  }, [])

  const toggleCaseSelected = useCallback((caseId: string) => {
    setSelectedCaseIds(prev => (prev.includes(caseId) ? prev.filter(id => id !== caseId) : [...prev, caseId]))
  }, [])

  const toggleVendor = useCallback((vendor: Vendor) => {
    setSelectedVendors(prev => (prev.includes(vendor) ? prev.filter(item => item !== vendor) : [...prev, vendor]))
  }, [])

  const selectAllCases = useCallback(() => {
    setSelectedCaseIds(filteredDashboardCases.filter(item => item.enabled && hasCaseAudio(item)).map(item => item.id))
  }, [filteredDashboardCases])

  const selectNoiseSuite = useCallback(() => {
    const noiseCases = cases.filter(item => item.enabled && hasCaseAudio(item) && item.sourceCaseId && item.noiseProfile)
    const sourceIds = new Set(noiseCases.map(item => item.sourceCaseId))
    const cleanSources = cases.filter(item => item.enabled && hasCaseAudio(item) && sourceIds.has(item.id))
    setSelectedCaseIds([...cleanSources, ...noiseCases].map(item => item.id))
    setRunName('抗噪评估任务')
  }, [cases])

  const selectNonNoiseCases = useCallback(() => {
    setSelectedCaseIds(cases
      .filter(item => item.enabled && hasCaseAudio(item) && !item.sourceCaseId && !item.noiseProfile)
      .map(item => item.id))
    setRunName('常规评估任务')
  }, [cases])

  const clearSelection = useCallback(() => {
    setSelectedCaseIds([])
  }, [])

  const addLocalCase = useCallback(() => {
    const next = createBlankCase()
    setCases(prev => [next, ...prev])
    setCaseListPage(1)
    setSelectedCaseId(next.id)
    setModalState({ type: 'case-editor' })
  }, [])

  const applyBackendRun = useCallback((run: EvalRunRecord) => {
    setRunStatus(run.status)
    setCurrentRunId(run.runId)
    setServerVendorSummaries(run.summary?.vendors ?? null)
    setLogs((run.logs ?? []).map((line, index) => ({ id: `${run.runId}_${index}`, time: line.time, text: line.text })))
    const cases = run.cases ?? []
    const vendors = run.selectedVendors ?? []
    const running = cases.flatMap(item => vendors.map(vendor => ({ caseId: item.id, vendor, result: item.vendors?.[vendor] }))).find(item => item.result?.status === 'running')
    setActiveTask(running ? { caseId: running.caseId, vendor: running.vendor } : null)
    setCaseResults(Object.fromEntries(cases.map(item => [item.id, {
      caseId: item.id,
      caseName: item.name,
      referenceText: item.referenceText,
      cantoneseTraditionalReferenceText: item.cantoneseTraditionalReferenceText,
      caseType: item.caseType,
      vendors: item.vendors,
    }])))
  }, [])

  const pollRun = useCallback(async () => {
    if (!currentRunId) return
    try {
      applyBackendRun(await getEvalRun(currentRunId))
    } catch (err) {
      setBackendError(err instanceof Error ? err.message : '刷新后端任务失败')
    }
  }, [applyBackendRun, currentRunId])

  useEffect(() => {
    if (!currentRunId || !['running', 'pausing', 'paused'].includes(runStatus)) return
    void pollRun()
    const timer = window.setInterval(() => void pollRun(), 1000)
    return () => window.clearInterval(timer)
  }, [currentRunId, pollRun, runStatus])

  const commandRun = useCallback(async (command: (runId: string) => Promise<EvalRunRecord>) => {
    if (!currentRunId) return
    try {
      applyBackendRun(await command(currentRunId))
    } catch (err) {
      setBackendError(err instanceof Error ? err.message : '任务控制失败')
    }
  }, [applyBackendRun, currentRunId])

  const pauseRun = useCallback(() => void commandRun(pauseEvalRun), [commandRun])
  const resumeRun = useCallback(() => void commandRun(resumeEvalRun), [commandRun])
  const stopRun = useCallback(() => void commandRun(stopEvalRun), [commandRun])

  const resolveCaseAudio = useCallback(async (caseItem: EvalCaseConfig): Promise<Blob> => {
    if (caseItem.source === 'local') {
      if (!caseItem.audioFile) {
        throw new Error(`「${caseItem.name}」还没有上传音频文件`)
      }
      return caseItem.audioFile
    }

    const backendId = caseItem.backendId ?? caseItem.id
    const audioBuffer = await fetchCaseAudio(backendId)
    const mimeType = mimeFromName(caseItem.audioFileName ?? caseItem.backendAudioExt ?? backendId)
    return new Blob([audioBuffer], { type: mimeType })
  }, [])

  const stopMatrixAudio = useCallback(() => {
    matrixAudioRequestRef.current += 1
    const current = matrixAudioRef.current
    if (current) {
      current.audio.pause()
      current.audio.removeAttribute('src')
      current.audio.load()
      URL.revokeObjectURL(current.url)
      matrixAudioRef.current = null
    }
    setPlayingCaseId(null)
    setMatrixAudioLoadingCaseId(null)
  }, [])

  const playMatrixCaseAudio = useCallback(async (caseId: string) => {
    if (matrixAudioRef.current?.caseId === caseId) {
      stopMatrixAudio()
      return
    }

    stopMatrixAudio()
    const requestId = ++matrixAudioRequestRef.current
    const caseItem = casesRef.current.find(item => item.id === caseId)
    if (!caseItem || !hasCaseAudio(caseItem)) {
      setBackendError('该 case 没有可播放的音频')
      return
    }

    setMatrixAudioLoadingCaseId(caseId)
    try {
      const blob = await resolveCaseAudio(caseItem)
      if (matrixAudioRequestRef.current !== requestId) return

      const url = URL.createObjectURL(blob)
      const audio = new Audio(url)
      const cleanup = () => {
        if (matrixAudioRef.current?.audio !== audio) return
        URL.revokeObjectURL(url)
        matrixAudioRef.current = null
        setPlayingCaseId(null)
        setMatrixAudioLoadingCaseId(null)
      }
      audio.onended = cleanup
      audio.onerror = cleanup
      matrixAudioRef.current = { caseId, audio, url }
      setPlayingCaseId(caseId)
      setMatrixAudioLoadingCaseId(null)
      await audio.play()
    } catch (err) {
      if (matrixAudioRequestRef.current !== requestId) return
      const message = err instanceof Error ? err.message : '音频播放失败'
      setBackendError(message)
      addLog(`结果矩阵音频播放失败：${message}`)
      stopMatrixAudio()
    }
  }, [addLog, resolveCaseAudio, stopMatrixAudio])

  const buildAudioPcmDataUrls = useCallback(async (): Promise<Record<string, string>> => {
    const pcmDataUrls: Record<string, string> = {}
    for (const caseItem of selectedCases) {
      const audio = await resolveCaseAudio(caseItem)
      const pcm = await decodeAudioBlobToPcm(audio)
      pcmDataUrls[caseItem.id] = pcmToDataUrl(pcm.pcm)
    }
    return pcmDataUrls
  }, [resolveCaseAudio, selectedCases])

  const runTask = useCallback(async () => {
    if (selectedCases.length === 0 || selectedVendors.length === 0) {
      addLog('请至少选择一个 case 和一个厂商')
      return
    }
    if (casesMissingReference.length > 0) {
      const missingNames = selectedCases.filter(item => !hasReferenceText(item)).map(item => item.name).join('、')
      addLog(`存在未填写 referenceText 的 case：${missingNames}`)
      return
    }
    if (casesMissingAudio.length > 0) {
      const missingNames = selectedCases.filter(item => !hasCaseAudio(item)).map(item => item.name).join('、')
      setBackendError(`以下 case 还没有音频，暂时不能批跑：${missingNames}`)
      return
    }
    if (selectedCases.some(item => item.source !== 'backend' || !item.backendId)) {
      setBackendError('临时 case 需要先保存为后端样本后才能运行')
      return
    }

    setBackendLoading(true)
    try {
      const created = await createEvalRun({
        name: runName,
        evaluationMode,
        selectedVendors,
        selectedCaseIds: selectedCases.map(item => item.id),
        audioPcmDataUrls: await buildAudioPcmDataUrls(),
      })
      applyBackendRun(created)
    } catch (err) {
      setBackendError(err instanceof Error ? err.message : '创建后端任务失败')
    } finally {
      setBackendLoading(false)
    }
  }, [addLog, applyBackendRun, buildAudioPcmDataUrls, casesMissingAudio.length, casesMissingReference.length, evaluationMode, runName, selectedCases, selectedVendors])

  useEffect(() => {
    if (selectedCaseId && cases.some(item => item.id === selectedCaseId)) return
    setSelectedCaseId(selectedCases[0]?.id ?? cases[0]?.id ?? null)
  }, [selectedCaseId, selectedCases])

  const activeDetailCase = useMemo(() => cases.find(item => item.id === selectedCaseId) ?? null, [cases, selectedCaseId])

  const persistActiveCase = useCallback(async () => {
    if (!activeDetailCase) return

    const payload: CaseMetaUpdate = {
      name: activeDetailCase.name,
      note: activeDetailCase.note,
      caseType: activeDetailCase.caseType,
      referenceText: activeDetailCase.referenceText,
      cantoneseTraditionalReferenceText: activeDetailCase.cantoneseTraditionalReferenceText,
      criticalTermsText: activeDetailCase.criticalTermsText,
      acceptableTextsText: activeDetailCase.acceptableTextsText,
      passRuleType: activeDetailCase.passRuleType,
      passThreshold: activeDetailCase.passThreshold,
      enabled: activeDetailCase.enabled,
      durationSeconds: activeDetailCase.durationSeconds,
    }

    setSavingCaseId(activeDetailCase.id)
    try {
      if (activeDetailCase.source === 'backend' || activeDetailCase.backendId) {
        const caseId = activeDetailCase.backendId ?? activeDetailCase.id
        let saved = await updateCase(caseId, payload)
        if (activeDetailCase.audioFile) {
          saved = await updateCaseAudio(
            caseId,
            activeDetailCase.audioFile,
            activeDetailCase.audioFileName ?? 'audio',
            activeDetailCase.durationSeconds ?? 0,
          )
        }
        setCases(prev => prev.map(item => (item.id === activeDetailCase.id ? backendCaseToEvalCase(saved) : item)))
        addLog(`已保存 ${saved.name} 的修改`)
        return
      }

      const saved = await saveCase(
        activeDetailCase.audioFile ?? null,
        activeDetailCase.audioFileName ?? 'audio',
        activeDetailCase.name,
        activeDetailCase.note,
        activeDetailCase.durationSeconds ?? 0,
        payload,
      )

      setCases(prev => prev.map(item => (item.id === activeDetailCase.id ? backendCaseToEvalCase(saved) : item)))
      setSelectedCaseIds(prev => activeDetailCase.hasAudio
        ? prev.map(id => (id === activeDetailCase.id ? saved.id : id))
        : prev.filter(id => id !== activeDetailCase.id))
      setSelectedCaseId(saved.id)
      setCaseResults(prev => {
        const next = { ...prev }
        if (next[activeDetailCase.id]) {
          next[saved.id] = next[activeDetailCase.id]
          delete next[activeDetailCase.id]
        }
        return next
      })
      addLog(`已保存为新 case：${saved.name}`)
    } catch (err) {
      const message = err instanceof Error ? err.message : '保存失败'
      setBackendError(message)
      addLog(`保存失败：${message}`)
    } finally {
      setSavingCaseId(null)
    }
  }, [activeDetailCase, addLog])

  const detailCaseRecord = useMemo(() => (detailSelection ? caseResults[detailSelection.caseId] : null), [caseResults, detailSelection])

  const selectedVendorResult = useMemo(() => {
    if (!detailSelection) return null
    return caseResults[detailSelection.caseId]?.vendors[detailSelection.vendor] ?? null
  }, [caseResults, detailSelection])

  const detailCaseConfig = useMemo(() => {
    if (!detailSelection) return null
    return cases.find(item => item.id === detailSelection.caseId) ?? null
  }, [cases, detailSelection])

  const detailDiffSegments = useMemo(() => {
    if (!detailCaseRecord || !selectedVendorResult) return []
    return diffText(
      (selectedVendorResult.normalizedReference || normalizeText(selectedVendorResult.referenceVariantUsed || detailCaseRecord.referenceText || '')).replace(/\s+/g, ''),
      (selectedVendorResult.normalizedTranscript || normalizeText(selectedVendorResult.transcript || '')).replace(/\s+/g, ''),
    )
  }, [detailCaseRecord, selectedVendorResult])

  const selectedCaseCompleteCount = useMemo(() => {
    return activeRecords.filter(record =>
      selectedVendors.every(vendor => record.vendors[vendor].status === 'done' || record.vendors[vendor].status === 'failed' || record.vendors[vendor].status === 'timeout')
    ).length
  }, [activeRecords, selectedVendors])

  const hasRetryableResult = useMemo(() => activeRecords.some(record =>
    selectedVendors.some(vendor => record.vendors[vendor].status !== 'done'),
  ), [activeRecords, selectedVendors])

  const handleAudioFileUpload = useCallback(async (caseId: string, file: File | null) => {
    if (!file) return
    setBackendError('')
    setCases(prev => prev.map(item => {
      if (item.id !== caseId) return item
      return {
        ...item,
        audioFile: file,
        audioFileName: file.name,
        audioMimeType: file.type || mimeFromName(file.name),
        hasAudio: true,
      }
    }))
    try {
      const durationSeconds = await getAudioDurationSeconds(file)
      setCases(prev => prev.map(item => {
        if (item.id !== caseId) return item
        return {
          ...item,
          durationSeconds,
        }
      }))
    } catch {
      addLog(`无法解析 ${file.name} 的时长，保存时会使用 0`)
    }
  }, [addLog])

  const clearDetailAudio = useCallback(() => {
    setDetailAudioUrl((current) => {
      if (current) URL.revokeObjectURL(current)
      return null
    })
  }, [])

  const closeModal = useCallback(() => {
    clearDetailAudio()
    setModalState(null)
  }, [clearDetailAudio])

  const openCaseEditor = useCallback((caseId: string) => {
    setSelectedCaseId(caseId)
    setModalState({ type: 'case-editor' })
  }, [])

  const openResultDetail = useCallback((caseId: string, vendor: Vendor) => {
    clearDetailAudio()
    setDetailSelection({ caseId, vendor })
    setModalState({ type: 'result-detail' })
  }, [clearDetailAudio])

  const loadDetailAudio = useCallback(async () => {
    if (!detailCaseConfig) return
    setDetailAudioLoading(true)
    try {
      const blob = await resolveCaseAudio(detailCaseConfig)
      clearDetailAudio()
      setDetailAudioUrl(URL.createObjectURL(blob))
    } catch (err) {
      const message = err instanceof Error ? err.message : '音频加载失败'
      addLog(`音频预览失败：${message}`)
      setBackendError(message)
    } finally {
      setDetailAudioLoading(false)
    }
  }, [addLog, clearDetailAudio, detailCaseConfig, resolveCaseAudio])

  useEffect(() => () => clearDetailAudio(), [clearDetailAudio])
  useEffect(() => () => stopMatrixAudio(), [stopMatrixAudio])

  const renderVendorCell = (record: CaseEvalRecord, vendor: Vendor) => {
    const result = record.vendors[vendor]
    return (
      <button
        type="button"
        className={`vendor-cell ${result.pass ? 'pass' : result.status === 'failed' || result.status === 'timeout' ? 'fail' : ''}`}
        onClick={() => openResultDetail(record.caseId, vendor)}
      >
        <div className="vendor-cell-head">
          <span>{getVendorLabel(vendor)}</span>
          {statusBadge(result.status)}
        </div>
        <div className="vendor-transcript">
          {result.transcript || result.errorMsg || CASE_PHASE_LABELS[result.phase] || '—'}
        </div>
        <div className="vendor-metrics">
          {result.status === 'running' || result.status === 'paused' ? phaseBadge(result.phase) : null}
          <span>CER {result.cer === null ? '—' : result.cer.toFixed(3)}</span>
          <span>WER {result.wer === null ? '—' : result.wer.toFixed(3)}</span>
          <span>首包 {formatLatency(result.firstLatencyMs)}</span>
          <span>{formatLatency(result.finalLatencyMs)}</span>
          <span>实体 {result.entityAccuracy === null ? '—' : formatPercent(result.entityAccuracy)}</span>
        </div>
      </button>
    )
  }

  return (
    <div className="eval-shell">
      <header className="eval-topbar">
        <div>
          <div className="eyebrow">ASR Evaluation Dashboard</div>
          <h1>三厂商评估看板</h1>
          <p>顺序跑批、结果对比和厂商决策集中在这里。</p>
        </div>
        <div className="route-note">
          <button type="button" className="ghost-btn" onClick={() => setModalState({ type: 'case-manager' })}>Case 管理</button>
          <button type="button" className="ghost-btn" onClick={() => setModalState({ type: 'run-history' })}>Run 历史</button>
          <button type="button" className="ghost-btn" onClick={() => setModalState({ type: 'run-log' })}>运行日志 {logs.length > 0 ? `(${logs.length})` : ''}</button>
          <span className="pill">/eval</span>
        </div>
      </header>

      <section className="stats-grid">
        {createMetricCard('任务状态', runStatus.toUpperCase(), activeTask ? `${activeTask.caseId} / ${getVendorLabel(activeTask.vendor)}` : '等待开始')}
        {createMetricCard('Case 进度', `${selectedCaseCompleteCount}/${selectedCases.length}`, `${selectedVendors.length} 个厂商顺序执行`)}
        {createMetricCard('厂商完成', `${overallStats.doneVendors}/${overallStats.totalVendors}`, `通过 ${overallStats.passedVendors} 项`)}
        {createMetricCard('平均 CER', overallStats.avgCer === null ? '-' : overallStats.avgCer.toFixed(3), '当前任务内的均值')}
        {createMetricCard('首包时延', overallStats.avgFirstLatencyMs === null ? '-' : formatLatency(overallStats.avgFirstLatencyMs), '所有已完成厂商均值')}
        {createMetricCard('最终时延', overallStats.avgFinalLatencyMs === null ? '-' : formatLatency(overallStats.avgFinalLatencyMs), '所有已完成厂商均值')}
        {createMetricCard('超时 / 失败', `${formatPercent(overallStats.timeoutRate)} / ${formatPercent(overallStats.failureRate)}`, '按厂商结果统计')}
      </section>

      <section className="eval-card panel">
        <div className="panel-head">
          <div>
            <h2>Case 选择</h2>
            <p>点击 case 行可快速编辑；完整管理在全屏弹框中完成。</p>
          </div>
          <div className="panel-actions">
            <button type="button" className="ghost-btn" onClick={syncBackendCases} disabled={backendLoading}>
              {backendLoading ? '同步中...' : '同步后端样本'}
            </button>
            <button type="button" className="ghost-btn" onClick={addLocalCase}>新增临时 case</button>
            <button type="button" className="ghost-btn" onClick={selectAllCases}>全选当前筛选</button>
            <button type="button" className="ghost-btn" onClick={selectNoiseSuite} disabled={!cases.some(item => item.sourceCaseId && item.noiseProfile)}>一键选择抗噪集</button>
            <button type="button" className="ghost-btn" onClick={selectNonNoiseCases}>一键选择非抗噪集</button>
            <button type="button" className="ghost-btn" onClick={clearSelection}>清空选择</button>
          </div>
        </div>

        <div className="case-filter-bar" aria-label="Case 筛选">
          <label>
            <span>对话长短</span>
            <select value={caseLengthFilter} onChange={(event) => { setCaseLengthFilter(event.target.value as CaseLengthFilter); setCaseListPage(1) }}>
              {CASE_LENGTH_OPTIONS.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
            </select>
          </label>
          <label>
            <span>噪音场景</span>
            <select value={noiseScenarioFilter} onChange={(event) => { setNoiseScenarioFilter(event.target.value as NoiseScenarioFilter); setCaseListPage(1) }}>
              {NOISE_SCENARIO_OPTIONS.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
            </select>
          </label>
          <span className="case-filter-count">匹配 {filteredDashboardCases.length} / {cases.length} 条</span>
        </div>

        {backendError ? <div className="notice error">{backendError}</div> : null}
        {casesMissingReference.length > 0 ? (
          <div className="notice error">有 {casesMissingReference.length} 个 case 还没有填写 referenceText，请先补齐后再批跑。</div>
        ) : null}
        {casesMissingAudio.length > 0 ? (
          <div className="notice error">有 {casesMissingAudio.length} 个 case 还没有上传音频，已暂存但不能参与批跑。</div>
        ) : null}

        <div className="case-table-wrap">
          <table className="case-table">
            <thead>
              <tr>
                <th>用</th>
                <th>名称</th>
                <th>类型</th>
                <th>对话长短</th>
                <th>噪音场景</th>
                <th>状态</th>
                <th>参考文本</th>
                <th>关键实体</th>
                <th>阈值</th>
                <th>音频</th>
              </tr>
            </thead>
            <tbody>
              {visibleDashboardCases.map((item) => (
                <tr
                  key={item.id}
                  className={`${selectedCaseId === item.id ? 'selected' : ''} ${item.enabled ? '' : 'disabled'}`}
                  onClick={() => openCaseEditor(item.id)}
                >
                  <td onClick={(event) => event.stopPropagation()}>
                    <input
                      type="checkbox"
                      checked={selectedCaseIds.includes(item.id)}
                      disabled={!hasCaseAudio(item)}
                      title={hasCaseAudio(item) ? '选择 Case' : '请先上传音频'}
                      onChange={() => toggleCaseSelected(item.id)}
                    />
                  </td>
                  <td><div className="case-name">{item.name}</div><div className="case-note">{item.note || item.source}</div></td>
                  <td>{getCaseTypeLabel(item.caseType)}</td>
                  <td>{getCaseLengthLabel(item)}</td>
                  <td>{getNoiseScenarioLabel(item)}</td>
                  <td>{hasReferenceText(item) ? <span className="badge badge-success">已填写</span> : <span className="badge badge-danger">缺少</span>}</td>
                  <td className="table-text">{item.referenceText || '待填写'}</td>
                  <td className="table-text">{item.criticalTermsText || '—'}</td>
                  <td>{getPassRuleLabel(item.passRuleType)} / {item.passThreshold.toFixed(2)}</td>
                  <td>{hasCaseAudio(item) ? (item.audioFileName || '已上传') : <span className="badge badge-danger">待上传</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="case-pagination" aria-label="评估看板 Case 列表分页">
          <div className="case-pagination-status">
            {filteredDashboardCases.length === 0 ? '当前筛选条件下暂无 Case' : `显示第 ${caseListStart}-${caseListEnd} 条，匹配 ${filteredDashboardCases.length} / ${cases.length} 条`}
          </div>
          <div className="case-pagination-actions">
            <button type="button" className="ghost-btn" onClick={() => setCaseListPage(1)} disabled={caseListPage === 1}>首页</button>
            <button type="button" className="ghost-btn" onClick={() => setCaseListPage(prev => Math.max(1, prev - 1))} disabled={caseListPage === 1}>上一页</button>
            <label className="case-page-select">
              <span>第</span>
              <select aria-label="选择评估看板 Case 页码" value={caseListPage} onChange={(event) => setCaseListPage(Number(event.target.value))} disabled={filteredDashboardCases.length === 0}>
                {Array.from({ length: caseListTotalPages }, (_, index) => index + 1).map(page => <option key={page} value={page}>{page}</option>)}
              </select>
              <span>/ {caseListTotalPages} 页</span>
            </label>
            <button type="button" className="ghost-btn" onClick={() => setCaseListPage(prev => Math.min(caseListTotalPages, prev + 1))} disabled={caseListPage === caseListTotalPages}>下一页</button>
            <button type="button" className="ghost-btn" onClick={() => setCaseListPage(caseListTotalPages)} disabled={caseListPage === caseListTotalPages}>末页</button>
          </div>
        </div>
      </section>

      <section className="eval-card panel">
        <div className="panel-head">
          <div>
            <h2>运行控制</h2>
            <p>选择口径和厂商后按顺序批跑，暂停会在当前厂商结束后生效。</p>
          </div>
        </div>
        <div className="run-control-grid">
          <div className="control-block">
            <div className="detail-title">评估口径</div>
            <div className="run-actions">
              <button type="button" className={`toggle-row ${evaluationMode === 'loose' ? 'active' : ''}`} onClick={() => setEvaluationMode('loose')}><span>宽松模式</span><span>Loose</span></button>
              <button type="button" className={`toggle-row ${evaluationMode === 'strict' ? 'active' : ''}`} onClick={() => setEvaluationMode('strict')}><span>严格模式</span><span>Strict</span></button>
            </div>
          </div>
          <div className="control-block">
            <div className="detail-title">厂商选择</div>
            <div className="vendor-list">
              {VENDOR_LIST.map(({ key, label }) => (
                <button key={key} type="button" className={`toggle-row ${selectedVendors.includes(key) ? 'active' : ''}`} onClick={() => toggleVendor(key)}>
                  <span>{label}</span><span>{selectedVendors.includes(key) ? '已选' : '未选'}</span>
                </button>
              ))}
            </div>
          </div>
          <div className="control-block">
            <label className="run-name"><span>任务名称</span><input value={runName} onChange={(event) => setRunName(event.target.value)} /></label>
            <div className="run-actions">
              <button type="button" className="primary-btn" onClick={() => void runTask()} disabled={backendLoading || runStatus === 'running' || runStatus === 'pausing' || !canStartRun}>{backendLoading ? '准备音频中...' : '开始批跑'}</button>
              <button type="button" className="ghost-btn" onClick={pauseRun} disabled={runStatus !== 'running'}>暂停</button>
              <button type="button" className="ghost-btn" onClick={resumeRun} disabled={!(runStatus === 'paused' || runStatus === 'failed' || runStatus === 'stopped' || (runStatus === 'completed' && hasRetryableResult))}>{runStatus === 'paused' ? '继续' : '断点续跑'}</button>
              <button type="button" className="ghost-btn danger" onClick={stopRun} disabled={runStatus === 'idle' || runStatus === 'completed' || runStatus === 'stopped'}>停止</button>
            </div>
            <div className="hint">{canStartRun ? '当前配置可开始运行。' : '请选择至少一个已上传音频且已填写参考文本的 case，并选择厂商。'}</div>
          </div>
        </div>
      </section>

      <section className="eval-card panel">
        <div className="panel-head">
          <div><h2>厂商汇总</h2><p>当前 run 的均值对比，完整排名在历史任务详情中查看。</p></div>
        </div>
        <div className="vendor-summary-grid">
          {vendorSummaries.map((summary) => (
            <div className="summary-item" key={summary.vendor}>
              <div className="summary-head"><strong>{getVendorLabel(summary.vendor)}</strong><span>{summary.completed}/{selectedCases.length}</span></div>
              <div className="summary-grid">
                <span>Pass {summary.passRate === null ? '-' : formatPercent(summary.passRate)}</span>
                <span>CER {summary.avgCer === null ? '-' : summary.avgCer.toFixed(3)}</span>
                <span>WER {summary.avgWer === null ? '-' : summary.avgWer.toFixed(3)}</span>
                <span>首包 {summary.avgFirstLatencyMs === null ? '-' : formatLatency(summary.avgFirstLatencyMs)}</span>
                <span>Latency {summary.avgFinalLatencyMs === null ? '-' : formatLatency(summary.avgFinalLatencyMs)}</span>
                <span>超时 {summary.timeoutCount ?? 0}</span>
                <span>失败 {summary.failureCount ?? 0}</span>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="eval-card panel results-panel">
        <div className="panel-head">
          <div><h2>批跑结果矩阵</h2><p>点击任一厂商结果可在抽屉中查看 diff、音频、指标和日志。</p></div>
          <div className="panel-actions">
            <select value={resultFilter} onChange={(event) => setResultFilter(event.target.value as ResultFilter)}>
              <option value="all">全部 case</option><option value="failed">仅失败/超时</option><option value="unfinished">仅未完成</option><option value="passed">仅全通过</option>
            </select>
            <select value={sortKey} onChange={(event) => setSortKey(event.target.value as SortKey)}>
              <option value="default">默认顺序</option><option value="status">按状态</option><option value="name">按名称</option><option value="cer">按 CER</option><option value="latency">按时延</option>
            </select>
          </div>
        </div>
        <div className="result-table-wrap">
          <table className="result-table">
            <thead><tr><th>Case</th><th>音频</th><th>状态</th>{selectedVendors.map(vendor => <th key={vendor}>{getVendorLabel(vendor)}</th>)}<th>最佳</th><th>结论</th></tr></thead>
            <tbody>
              {visibleRecords.map((record) => {
                const bestVendor = vendorOrderOf(record, selectedVendors)
                const doneCount = selectedVendors.filter(vendor => isTerminalStatus(record.vendors[vendor].status)).length
                const passCount = selectedVendors.filter(vendor => record.vendors[vendor].pass).length
                const flowState = getCaseFlowState(record, selectedVendors)
                const caseItem = cases.find(item => item.id === record.caseId)
                const playable = Boolean(caseItem && hasCaseAudio(caseItem))
                const isPlaying = playingCaseId === record.caseId
                const isLoadingAudio = matrixAudioLoadingCaseId === record.caseId
                return (
                  <tr key={record.caseId} className="result-row">
                    <td><div className="case-name">{record.caseName}</div><div className="case-note">{getCaseTypeLabel(record.caseType)}</div></td>
                    <td className="matrix-audio-cell">
                      <button
                        type="button"
                        className="matrix-audio-btn"
                        disabled={!playable || isLoadingAudio}
                        title={playable ? (isPlaying ? '停止播放' : '播放 case 音频') : '该 case 没有音频'}
                        onClick={() => void playMatrixCaseAudio(record.caseId)}
                      >
                        {isLoadingAudio ? '加载中...' : isPlaying ? '■ 停止' : '▶ 播放'}
                      </button>
                    </td>
                    <td><span className={`badge ${flowState.key === 'done' ? 'badge-success' : flowState.key === 'failed' || flowState.key === 'timeout' ? 'badge-danger' : flowState.key === 'paused' ? 'badge-warn' : 'badge-info'}`}>{flowState.label}</span></td>
                    {selectedVendors.map(vendor => <td key={vendor}>{renderVendorCell(record, vendor)}</td>)}
                    <td>{bestVendor ? getVendorLabel(bestVendor) : '—'}</td>
                    <td><div className="result-summary"><span className="pill">{passCount}/{selectedVendors.length} 通过</span><span className="pill subtle">{doneCount}/{selectedVendors.length} 完成</span></div></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </section>

      {modalState?.type === 'case-manager' ? (
        <EvalModal title="Case 管理" variant="fullscreen" onClose={() => { closeModal(); void syncBackendCases() }}>
          <EvalCasesPage embedded onChanged={() => void syncBackendCases()} />
        </EvalModal>
      ) : null}

      {modalState?.type === 'case-editor' ? (
        <EvalModal
          title={activeDetailCase ? `编辑 Case：${activeDetailCase.name}` : '编辑 Case'}
          variant="drawer"
          onClose={closeModal}
          actions={(
            <button type="button" className="primary-btn" onClick={() => void persistActiveCase()} disabled={!activeDetailCase || savingCaseId === activeDetailCase?.id}>
              {savingCaseId === activeDetailCase?.id ? '保存中...' : activeDetailCase?.hasAudio ? '保存' : '暂存'}
            </button>
          )}
        >
          {activeDetailCase ? (
            <div className="inspector">
              <label><span>名称</span><input value={activeDetailCase.name} onChange={(event) => upsertCase(activeDetailCase.id, item => ({ ...item, name: event.target.value }))} /></label>
              <label><span>备注</span><input value={activeDetailCase.note} onChange={(event) => upsertCase(activeDetailCase.id, item => ({ ...item, note: event.target.value }))} /></label>
              <label>
                <span>类型</span>
                <select value={activeDetailCase.caseType} onChange={(event) => upsertCase(activeDetailCase.id, item => ({
                  ...item,
                  caseType: event.target.value as EvalCaseConfig['caseType'],
                  passRuleType: event.target.value === 'mixed' ? 'mixed' : (event.target.value === 'number' || event.target.value === 'money' || event.target.value === 'name') ? 'entity' : 'cer',
                }))}>
                  {CASE_TYPE_LIST.map(item => <option key={item.key} value={item.key}>{item.label}</option>)}
                </select>
              </label>
              <label><span>参考文本</span><textarea rows={5} value={activeDetailCase.referenceText} onChange={(event) => upsertCase(activeDetailCase.id, item => ({ ...item, referenceText: event.target.value }))} placeholder="填写标准转写文本" /></label>
              <label><span>粤语繁体参考文本</span><textarea rows={5} value={activeDetailCase.cantoneseTraditionalReferenceText} onChange={(event) => upsertCase(activeDetailCase.id, item => ({ ...item, cantoneseTraditionalReferenceText: event.target.value }))} placeholder="用于并行评分，自动采用较优结果" /></label>
              <label><span>关键实体</span><input value={activeDetailCase.criticalTermsText} onChange={(event) => upsertCase(activeDetailCase.id, item => ({ ...item, criticalTermsText: event.target.value }))} placeholder="例如：12345, 2026年7月9日" /></label>
              <label><span>可接受文本</span><textarea rows={3} value={activeDetailCase.acceptableTextsText} onChange={(event) => upsertCase(activeDetailCase.id, item => ({ ...item, acceptableTextsText: event.target.value }))} placeholder="每行一个等价转写" /></label>
              <div className="inline-grid">
                <label><span>通过规则</span><select value={activeDetailCase.passRuleType} onChange={(event) => upsertCase(activeDetailCase.id, item => ({ ...item, passRuleType: event.target.value as PassRuleType }))}>{PASS_RULE_LIST.map(item => <option key={item.key} value={item.key}>{item.label}</option>)}</select></label>
                <label><span>CER 阈值</span><input type="number" step="0.01" min="0" max="1" value={activeDetailCase.passThreshold} onChange={(event) => upsertCase(activeDetailCase.id, item => ({ ...item, passThreshold: Number(event.target.value) }))} /></label>
              </div>
              <div className="audio-upload">
                <div className="audio-title">音频文件（可后续补充）</div><div className="audio-meta">{activeDetailCase.audioFileName || '暂未上传，可先保存 Case'}</div>
                <input type="file" accept="audio/*" onChange={(event) => { const file = event.target.files?.[0] ?? null; if (file) void handleAudioFileUpload(activeDetailCase.id, file); event.target.value = '' }} />
                <PressHoldRecorder
                  onRecorded={async (file) => {
                    await handleAudioFileUpload(activeDetailCase.id, file)
                    addLog(`已录制音频：${file.name}`)
                  }}
                  onError={(message) => {
                    setBackendError(message)
                    addLog(`录音失败：${message}`)
                  }}
                />
                <div className="hint">{activeDetailCase.source === 'backend' ? '选择或录制新音频后点击保存，会补传或替换当前 Case 的音频。' : '音频不是必填项，可以先暂存 Case 信息，后续再上传。'}</div>
              </div>
            </div>
          ) : <div className="empty-state">先从看板中选择一个 case。</div>}
        </EvalModal>
      ) : null}

      {modalState?.type === 'result-detail' ? (
        <EvalModal
          title={detailSelection ? `${getVendorLabel(detailSelection.vendor)} 识别详情` : '识别详情'}
          variant="drawer"
          onClose={closeModal}
          actions={<button type="button" className="ghost-btn" onClick={() => void loadDetailAudio()} disabled={!detailCaseConfig || detailAudioLoading}>{detailAudioLoading ? '加载中...' : '播放音频'}</button>}
        >
          {selectedVendorResult && detailSelection ? (
            <div className="inspector">
              <div className="audio-upload"><div className="audio-title">采用的参考文本</div><div className="detail-text">{selectedVendorResult.referenceVariantUsed || detailCaseRecord?.referenceText || '未填写'}</div></div>
              {detailCaseRecord?.cantoneseTraditionalReferenceText ? <div className="audio-upload"><div className="audio-title">粤语繁体参考文本</div><div className="detail-text">{detailCaseRecord.cantoneseTraditionalReferenceText}</div></div> : null}
              <div className="audio-upload"><div className="audio-title">识别结果</div><div className="detail-text">{selectedVendorResult.transcript || selectedVendorResult.errorMsg || '空结果'}</div></div>
              <div className="audio-upload"><div className="audio-title">归一化字符差异</div><div className="diff-row">{detailDiffSegments.map((segment, index) => <span key={`${segment.type}_${index}`} className={`diff-segment diff-${segment.type}`}>{segment.text}</span>)}</div></div>
              <div className="audio-upload"><div className="audio-title">归一化后</div><div className="detail-text muted">REF: {normalizeText(selectedVendorResult.referenceVariantUsed || detailCaseRecord?.referenceText || '') || '—'}<br />HYP: {normalizeText(selectedVendorResult.transcript || '') || '—'}</div></div>
              <div className="audio-upload"><div className="audio-title">指标</div><div className="detail-metrics"><span>CER {selectedVendorResult.cer === null ? '—' : selectedVendorResult.cer.toFixed(3)}</span><span>WER {selectedVendorResult.wer === null ? '—' : selectedVendorResult.wer.toFixed(3)}</span><span>首包 {formatLatency(selectedVendorResult.firstLatencyMs)}</span><span>总时延 {formatLatency(selectedVendorResult.finalLatencyMs)}</span><span>实体 {selectedVendorResult.entityAccuracy === null ? '—' : formatPercent(selectedVendorResult.entityAccuracy)}</span><span>结果 {selectedVendorResult.pass ? '通过' : '未通过'}</span>{selectedVendorResult.referenceVariantUsed ? <span>采用参考 {selectedVendorResult.referenceVariantUsed}</span> : null}{selectedVendorResult.normalizerVersion ? <span>归一化 {selectedVendorResult.normalizerVersion}</span> : null}</div></div>
              <div className="audio-upload"><div className="audio-title">编辑统计</div><div className="detail-metrics detail-metrics-stacked"><span>字符级 {formatEditTriple(selectedVendorResult.characterSubstitutions, selectedVendorResult.characterInsertions, selectedVendorResult.characterDeletions)}</span><span>词级 {formatEditTriple(selectedVendorResult.wordSubstitutions, selectedVendorResult.wordInsertions, selectedVendorResult.wordDeletions)}</span></div></div>
              {detailAudioUrl ? <audio controls src={detailAudioUrl} style={{ width: '100%' }} /> : null}
            </div>
          ) : <div className="empty-state">没有可展示的识别结果。</div>}
        </EvalModal>
      ) : null}

      {modalState?.type === 'run-log' ? (
        <EvalModal title="运行日志" variant="drawer" onClose={closeModal}>
          <div className="log-list">{logs.slice().reverse().map(item => <div className="log-line" key={item.id}><span>{item.time}</span><span>{item.text}</span></div>)}{logs.length === 0 ? <div className="empty-state">还没有日志。</div> : null}</div>
        </EvalModal>
      ) : null}

      {modalState?.type === 'run-history' || modalState?.type === 'run-detail' ? (
        <EvalModal
          title={modalState.type === 'run-detail' ? '评估任务详情' : 'Run 历史'}
          variant="fullscreen"
          onClose={closeModal}
          onBack={modalState.type === 'run-detail' ? () => setModalState({ type: 'run-history' }) : undefined}
        >
          {modalState.type === 'run-detail'
            ? <EvalRunPage key={modalState.runId} embedded runId={modalState.runId} />
            : <EvalRunHistoryContent onOpenRun={(runId) => setModalState({ type: 'run-detail', runId })} />}
        </EvalModal>
      ) : null}
    </div>
  )
}
