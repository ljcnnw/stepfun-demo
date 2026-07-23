import { useCallback, useEffect, useMemo, useState } from 'react'
import { getEvalRun, listEvalRuns, rerunEvalCase, rescoreEvalRun, exportEvalRun, type EvalRunRecord, type EvalRunCaseRecord, type EvalRunListItem } from '../api/evalRuns'
import { fetchCaseAudio } from '../api/benchCases'
import {
  getVendorLabel,
  formatLatency,
  formatPercent,
  CASE_PHASE_LABELS,
  compareVendorSummary,
  normalizeLooseText,
  normalizeStrictText,
  pickBestVendorResult,
  type Vendor,
  type VendorEvalResult,
} from '../lib/asrEval'
import { diffText } from '../lib/textDiff'
import { calculateNoiseVendorSummaries } from '../lib/noiseEval'
import { mimeFromName } from '../lib/audioFrames'
import { navigateWithAppBase } from '../lib/appRoutes'
import './EvalPage.css'

function normalizeRun(run: EvalRunRecord): EvalRunRecord {
  return {
    ...run,
    selectedVendors: run.selectedVendors ?? [],
    selectedCaseIds: run.selectedCaseIds ?? [],
    cases: run.cases ?? [],
    logs: run.logs ?? [],
    summary: {
      totalCases: run.summary?.totalCases ?? 0,
      completedCases: run.summary?.completedCases ?? 0,
      passedCases: run.summary?.passedCases ?? 0,
      failedCases: run.summary?.failedCases ?? 0,
      timeoutCases: run.summary?.timeoutCases ?? 0,
      totalVendors: run.summary?.totalVendors ?? 0,
      doneVendors: run.summary?.doneVendors ?? 0,
      passedVendors: run.summary?.passedVendors ?? 0,
      timeoutVendors: run.summary?.timeoutVendors ?? 0,
      failureVendors: run.summary?.failureVendors ?? 0,
      vendors: (run.summary?.vendors ?? []).map(sv => ({
        vendor: sv.vendor,
        completed: sv.completed ?? 0,
        passed: sv.passed ?? 0,
        timeoutCount: sv.timeoutCount ?? 0,
        failureCount: sv.failureCount ?? 0,
        avgCer: sv.avgCer ?? null,
        avgWer: sv.avgWer ?? null,
        avgFirstLatencyMs: sv.avgFirstLatencyMs ?? null,
        avgFinalLatencyMs: sv.avgFinalLatencyMs ?? null,
        entityAccuracy: sv.entityAccuracy ?? null,
        passRate: sv.passRate ?? null,
        timeoutRate: sv.timeoutRate ?? null,
        failureRate: sv.failureRate ?? null,
      })),
    },
  }
}

function titleText(text: string) {
  return text.length > 60 ? `${text.slice(0, 60)}...` : text
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

function createHeader(runName: string) {
  return (
    <header className="eval-topbar">
      <div>
        <div className="eyebrow">ASR Evaluation Dashboard</div>
        <h1>{runName}</h1>
        <p>历史任务详情、导出和单 case 重跑。</p>
      </div>
      <div className="route-note">
        <button type="button" className="ghost-btn" onClick={() => navigateWithAppBase('/eval')}>回到看板</button>
        <span className="pill">/eval/runs</span>
      </div>
    </header>
  )
}

function averageNumber(values: Array<number | null | undefined>) {
  const numeric = values.filter((value): value is number => typeof value === 'number' && Number.isFinite(value))
  if (numeric.length === 0) return null
  return numeric.reduce((sum, value) => sum + value, 0) / numeric.length
}

function formatEditTriple(substitutions: number | null, insertions: number | null, deletions: number | null) {
  if (substitutions === null || insertions === null || deletions === null) return '—'
  return `S${substitutions} / I${insertions} / D${deletions}`
}

function formatCerDelta(value: number | null) {
  if (value === null) return '—'
  return `${value >= 0 ? '+' : ''}${value.toFixed(3)}`
}

function summarizeHistoryItem(item: EvalRunListItem) {
  return {
    passRate: averageNumber(item.summary.vendors.map(vendor => vendor.passRate)),
    avgCer: averageNumber(item.summary.vendors.map(vendor => vendor.avgCer)),
    avgWer: averageNumber(item.summary.vendors.map(vendor => vendor.avgWer)),
    avgFirstLatencyMs: averageNumber(item.summary.vendors.map(vendor => vendor.avgFirstLatencyMs)),
    avgFinalLatencyMs: averageNumber(item.summary.vendors.map(vendor => vendor.avgFinalLatencyMs)),
    timeoutRate: averageNumber(item.summary.vendors.map(vendor => vendor.timeoutRate)),
    failureRate: averageNumber(item.summary.vendors.map(vendor => vendor.failureRate)),
  }
}

interface EvalRunPageProps {
  runId: string
  embedded?: boolean
}

export function EvalRunPage({ runId, embedded = false }: EvalRunPageProps) {
  const [run, setRun] = useState<EvalRunRecord | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedCaseId, setSelectedCaseId] = useState<string | null>(null)
  const [selectedVendor, setSelectedVendor] = useState<Vendor | null>(null)
  const [busyCaseId, setBusyCaseId] = useState<string | null>(null)
  const [rescoring, setRescoring] = useState(false)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [historyRuns, setHistoryRuns] = useState<EvalRunListItem[]>([])
  const [resultFilter, setResultFilter] = useState<'all' | 'failed' | 'unfinished' | 'passed'>('all')
  const [sortKey, setSortKey] = useState<'default' | 'name' | 'cer' | 'latency' | 'status'>('default')
  const [noiseSortKey, setNoiseSortKey] = useState<'robustness' | 'vendor' | 'pairs' | 'retention' | 'cerDelta' | 'entityRetention'>('robustness')

  const activeCase = useMemo(() => run?.cases.find(item => item.id === selectedCaseId) ?? null, [run, selectedCaseId])
  const selectedVendorResult = useMemo(() => {
    if (!activeCase || !selectedVendor) return null
    return activeCase.vendors[selectedVendor] ?? null
  }, [activeCase, selectedVendor])

  const runSummary = useMemo(() => summarizeHistoryItem({
    runId: run?.runId ?? runId,
    name: run?.name ?? '',
    status: run?.status ?? 'idle',
    evaluationMode: run?.evaluationMode ?? 'loose',
    selectedVendors: run?.selectedVendors ?? [],
    selectedCaseIds: run?.selectedCaseIds ?? [],
    startedAt: run?.startedAt ?? '',
    updatedAt: run?.updatedAt ?? '',
    finishedAt: run?.finishedAt ?? null,
    summary: run?.summary ?? {
      totalCases: 0,
      completedCases: 0,
      passedCases: 0,
      totalVendors: 0,
      doneVendors: 0,
      passedVendors: 0,
      timeoutVendors: 0,
      failureVendors: 0,
      vendors: [],
    },
  }), [run, runId])

  const historyComparison = useMemo(() => {
    const [latest, previous] = historyRuns
    if (!latest || !previous) return null
    return {
      latest,
      previous,
      latestSummary: summarizeHistoryItem(latest),
      previousSummary: summarizeHistoryItem(previous),
    }
  }, [historyRuns])

  const vendorComparison = useMemo(() => {
    if (!run) return []
    const winCounter = Object.fromEntries(run.selectedVendors.map(vendor => [vendor, 0])) as Record<Vendor, number>
    run.cases.forEach((caseItem) => {
      const best = pickBestVendorResult(run.selectedVendors.map(vendor => caseItem.vendors[vendor]))
      if (best) {
        winCounter[best.vendor] += 1
      }
    })

    return [...run.summary.vendors]
      .map((summary) => ({
        ...summary,
        winCount: winCounter[summary.vendor] ?? 0,
      }))
      .sort(compareVendorSummary)
      .map((summary, idx) => ({
        ...summary,
        rank: idx + 1,
      }))
  }, [run])

  const bestVendorOverall = useMemo(() => vendorComparison[0] ?? null, [vendorComparison])

  const bestCaseResultMap = useMemo(() => {
    if (!run) return new Map<string, VendorEvalResult | null>()
    return new Map(
      run.cases.map((caseItem) => [
        caseItem.id,
        pickBestVendorResult(run.selectedVendors.map(vendor => caseItem.vendors[vendor])),
      ]),
    )
  }, [run])

  const noiseComparison = useMemo(() => {
    if (!run) return []
    return calculateNoiseVendorSummaries(run.cases, run.selectedVendors)
  }, [run])

  const sortedNoiseComparison = useMemo(() => {
    const nullLast = (value: number | null) => value === null ? Number.NEGATIVE_INFINITY : value
    return [...noiseComparison].sort((a, b) => {
      if (noiseSortKey === 'vendor') return getVendorLabel(a.vendor).localeCompare(getVendorLabel(b.vendor))
      if (noiseSortKey === 'pairs') return b.validPairs - a.validPairs
      if (noiseSortKey === 'retention') return nullLast(b.passRetention) - nullLast(a.passRetention)
      if (noiseSortKey === 'cerDelta') {
        const left = a.avgCerDelta === null ? Number.POSITIVE_INFINITY : a.avgCerDelta
        const right = b.avgCerDelta === null ? Number.POSITIVE_INFINITY : b.avgCerDelta
        return left - right
      }
      if (noiseSortKey === 'entityRetention') return nullLast(b.entityRetention) - nullLast(a.entityRetention)
      return nullLast(b.robustnessScore) - nullLast(a.robustnessScore)
    })
  }, [noiseComparison, noiseSortKey])

  const noiseProfiles = noiseComparison[0]?.profiles ?? []
  const hasNoiseComparison = noiseComparison.some(summary => summary.validPairs > 0)

  const visibleCases = useMemo(() => {
    if (!run) return []
    let next = [...run.cases]
    if (resultFilter === 'failed') {
      next = next.filter(item => run.selectedVendors.some(vendor => {
        const result = item.vendors[vendor]
        return result.status === 'failed' || result.status === 'timeout'
      }))
    } else if (resultFilter === 'unfinished') {
      next = next.filter(item => run.selectedVendors.some(vendor => {
        const status = item.vendors[vendor].status
        return status !== 'done' && status !== 'failed' && status !== 'timeout'
      }))
    } else if (resultFilter === 'passed') {
      next = next.filter(item => run.selectedVendors.every(vendor => item.vendors[vendor].pass))
    }

    const rowMetrics = (item: EvalRunCaseRecord) => {
      const results = run.selectedVendors.map(vendor => item.vendors[vendor])
      const cer = averageNumber(results.map(result => result.cer))
      const latency = averageNumber(results.map(result => result.finalLatencyMs))
      const passCount = results.filter(result => result.pass).length
      const terminalCount = results.filter(result => result.status === 'done' || result.status === 'failed' || result.status === 'timeout').length
      return {
        name: item.name.toLowerCase(),
        cer: cer ?? Number.POSITIVE_INFINITY,
        latency: latency ?? Number.POSITIVE_INFINITY,
        passScore: passCount,
        terminalScore: terminalCount,
      }
    }

    if (sortKey === 'name') {
      next.sort((a, b) => a.name.localeCompare(b.name))
    } else if (sortKey === 'cer') {
      next.sort((a, b) => rowMetrics(a).cer - rowMetrics(b).cer)
    } else if (sortKey === 'latency') {
      next.sort((a, b) => rowMetrics(a).latency - rowMetrics(b).latency)
    } else if (sortKey === 'status') {
      next.sort((a, b) => rowMetrics(b).passScore - rowMetrics(a).passScore || rowMetrics(b).terminalScore - rowMetrics(a).terminalScore)
    }

    return next
  }, [resultFilter, run, sortKey])

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const next = normalizeRun(await getEvalRun(runId))
      setRun(next)
      setSelectedCaseId(prev => prev ?? next.cases[0]?.id ?? null)
      setSelectedVendor(prev => prev ?? next.selectedVendors[0] ?? null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载任务失败')
    } finally {
      setLoading(false)
    }
  }, [runId])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    let mounted = true
    void listEvalRuns()
      .then(list => {
        if (mounted) setHistoryRuns(list)
      })
      .catch(() => {
        if (mounted) setHistoryRuns([])
      })
    return () => {
      mounted = false
    }
  }, [])

  const stopPreview = useCallback(() => {
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl)
      setPreviewUrl(null)
    }
  }, [previewUrl])

  useEffect(() => () => stopPreview(), [stopPreview])

  const openPreview = useCallback(async (caseItem: EvalRunCaseRecord) => {
    stopPreview()
    try {
      let blob: Blob
      if (caseItem.audioDataUrl) {
        const res = await fetch(caseItem.audioDataUrl)
        blob = await res.blob()
      } else if (caseItem.backendId) {
        const buf = await fetchCaseAudio(caseItem.backendId)
        blob = new Blob([buf], { type: mimeFromName(caseItem.audioFileName || caseItem.backendAudioExt || caseItem.backendId) })
      } else {
        throw new Error('这个 case 没有可重放的音频')
      }
      const url = URL.createObjectURL(blob)
      setPreviewUrl(url)
      const audio = new Audio(url)
      await audio.play()
    } catch (err) {
      setError(err instanceof Error ? err.message : '预览失败')
    }
  }, [stopPreview])

  const rerunCase = useCallback(async (caseId: string) => {
    if (!run) return
    setBusyCaseId(caseId)
    setError('')
    try {
      setRun(normalizeRun(await rerunEvalCase(run.runId, caseId)))
    } catch (err) {
      setError(err instanceof Error ? err.message : '重跑失败')
    } finally {
      setBusyCaseId(null)
    }
  }, [run])

  const rescoreRun = useCallback(async () => {
    if (!run) return
    setRescoring(true)
    setError('')
    try {
      const next = normalizeRun(await rescoreEvalRun(run.runId))
      setRun(next)
      setHistoryRuns(await listEvalRuns())
    } catch (err) {
      setError(err instanceof Error ? err.message : '重新评分失败')
    } finally {
      setRescoring(false)
    }
  }, [run])

  useEffect(() => {
    if (!run || !['running', 'pausing', 'paused'].includes(run.status)) return
    const timer = window.setInterval(() => void refresh(), 1000)
    return () => window.clearInterval(timer)
  }, [refresh, run])

  const exportRunFile = useCallback(async (format: 'csv' | 'json') => {
    try {
      const blob = await exportEvalRun(runId, format)
      downloadBlob(blob, `${runId}.${format}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '导出失败')
    }
  }, [runId])

  if (loading && !run) {
    return <div className={embedded ? 'eval-embedded-content' : 'eval-shell'}>加载中...</div>
  }

  if (!run) {
    return <div className={embedded ? 'eval-embedded-content' : 'eval-shell'}>{error || '任务不存在'}</div>
  }

  const selectedCase = activeCase
  const currentResult = selectedVendorResult
  const refText = currentResult?.referenceVariantUsed || selectedCase?.referenceText || ''
  const hypText = currentResult?.transcript || ''
  const refDisplay = (currentResult?.normalizedReference || (run.evaluationMode === 'strict' ? normalizeStrictText(refText) : normalizeLooseText(refText))).replace(/\s+/g, '')
  const hypDisplay = (currentResult?.normalizedTranscript || (run.evaluationMode === 'strict' ? normalizeStrictText(hypText) : normalizeLooseText(hypText))).replace(/\s+/g, '')
  const diffSegments = diffText(refDisplay, hypDisplay)

  return (
    <div className={embedded ? 'eval-embedded-content' : 'eval-shell'}>
      {!embedded ? createHeader(run.name) : null}
      {error ? <div className="notice error">{error}</div> : null}

      <section className="stats-grid">
        <div className="eval-card metric-card">
          <div className="metric-label">任务状态</div>
          <div className="metric-value">{run.status.toUpperCase()}</div>
          <div className="metric-hint">{run.evaluationMode} mode</div>
        </div>
        <div className="eval-card metric-card">
          <div className="metric-label">Case 完成</div>
          <div className="metric-value">{run.summary.completedCases}/{run.summary.totalCases}</div>
          <div className="metric-hint">已完成/总数</div>
        </div>
        <div className="eval-card metric-card">
          <div className="metric-label">三家全通过 / 至少一家通过</div>
          <div className="metric-value">{run.summary.allPassedCases ?? run.summary.passedCases} / {run.summary.anyPassedCases ?? 0}</div>
          <div className="metric-hint">按 case 统计，不等同于厂商通过率</div>
        </div>
        <div className="eval-card metric-card">
          <div className="metric-label">厂商完成</div>
          <div className="metric-value">{run.summary.doneVendors}/{run.summary.totalVendors}</div>
          <div className="metric-hint">通过 {run.summary.passedVendors}</div>
        </div>
        <div className="eval-card metric-card">
          <div className="metric-label">记录更新</div>
          <div className="metric-value">{titleText(run.updatedAt)}</div>
          <div className="metric-hint">导出与重跑后会同步写入 run.json</div>
        </div>
        <div className="eval-card metric-card">
          <div className="metric-label">平均首包</div>
          <div className="metric-value">{runSummary.avgFirstLatencyMs === null ? '-' : formatLatency(runSummary.avgFirstLatencyMs)}</div>
          <div className="metric-hint">所有已完成厂商均值</div>
        </div>
        <div className="eval-card metric-card">
          <div className="metric-label">超时 / 失败</div>
          <div className="metric-value">{formatPercent(runSummary.timeoutRate)} / {formatPercent(runSummary.failureRate)}</div>
          <div className="metric-hint">按厂商结果统计</div>
        </div>
        <div className="eval-card metric-card">
          <div className="metric-label">当前最佳厂商</div>
          <div className="metric-value">{bestVendorOverall ? getVendorLabel(bestVendorOverall.vendor) : '-'}</div>
          <div className="metric-hint">
            {bestVendorOverall ? `按通过率、CER 排名 · 赢 ${bestVendorOverall.winCount ?? 0} 个 case` : '暂无可比较结果'}
          </div>
        </div>
      </section>

      <section className="eval-card panel">
        <div className="panel-head">
          <div>
            <h2>厂商总对比</h2>
            <p>按通过率、CER 排序；时延仅用于平分。</p>
          </div>
          <div className="panel-actions">
            {bestVendorOverall ? <span className="pill">最佳：{getVendorLabel(bestVendorOverall.vendor)}</span> : null}
          </div>
        </div>
        <div className="result-table-wrap">
          <table className="result-table">
            <thead>
              <tr>
                <th>排名</th>
                <th>厂商</th>
                <th>通过率</th>
                <th>CER</th>
                <th>实体</th>
                <th>胜出</th>
                <th>超时</th>
                <th>失败</th>
              </tr>
            </thead>
            <tbody>
              {vendorComparison.map((summary) => (
                <tr key={summary.vendor} className={bestVendorOverall?.vendor === summary.vendor ? 'selected' : ''}>
                  <td>#{summary.rank}</td>
                  <td>
                    <div className="case-name">{getVendorLabel(summary.vendor)}</div>
                    {summary.rank === 1 ? <div className="case-note">本次最优</div> : null}
                  </td>
                  <td>{summary.passRate === null ? '-' : formatPercent(summary.passRate)}</td>
                  <td>{summary.avgCer === null ? '-' : summary.avgCer.toFixed(3)}</td>
                  <td>{summary.entityAccuracy === null ? '-' : formatPercent(summary.entityAccuracy)}</td>
                  <td>{summary.winCount ?? 0}</td>
                  <td>{summary.timeoutCount ?? 0}</td>
                  <td>{summary.failureCount ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {hasNoiseComparison ? (
        <section className="eval-card panel">
          <div className="panel-head">
            <div>
              <h2>抗噪能力</h2>
              <p>仅比较同一源 case 的干净基线与噪声副本；不纳入 WER、时延或综合分。</p>
            </div>
            <div className="panel-actions">
              <span className="pill">抗噪指数：70% 通过保持 + 30% CER 稳定度</span>
              <select value={noiseSortKey} onChange={(event) => setNoiseSortKey(event.target.value as typeof noiseSortKey)}>
                <option value="robustness">按抗噪指数</option>
                <option value="retention">按通过保持</option>
                <option value="cerDelta">按 CER 增量</option>
                <option value="entityRetention">按实体保持</option>
                <option value="pairs">按有效配对</option>
                <option value="vendor">按厂商名称</option>
              </select>
            </div>
          </div>
          <div className="result-table-wrap">
            <table className="result-table">
              <thead>
                <tr>
                  <th>厂商</th>
                  <th>有效配对</th>
                  <th>通过保持</th>
                  <th>CER 增量</th>
                  <th>实体保持</th>
                  <th>抗噪指数</th>
                  {noiseProfiles.map(profile => <th key={profile.profile}>{profile.label}</th>)}
                </tr>
              </thead>
              <tbody>
                {sortedNoiseComparison.map(summary => (
                  <tr key={summary.vendor}>
                    <td>{getVendorLabel(summary.vendor)}</td>
                    <td>{summary.validPairs}</td>
                    <td>{summary.passRetention === null ? '样本不足' : `${summary.retainedPassPairs}/${summary.cleanPassedPairs} · ${formatPercent(summary.passRetention)}`}</td>
                    <td>{formatCerDelta(summary.avgCerDelta)}</td>
                    <td>{summary.entityRetention === null ? '—' : formatPercent(summary.entityRetention)}</td>
                    <td>{summary.robustnessScore === null ? '—' : summary.robustnessScore.toFixed(3)}</td>
                    {summary.profiles.map(profile => <td key={profile.profile}>{profile.passRetention === null ? '—' : `${formatPercent(profile.passRetention)} / ${formatCerDelta(profile.avgCerDelta)}`}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ) : null}

      <section className="eval-card panel">
        <div className="panel-head">
          <div>
            <h2>运行概览</h2>
            <p>历史任务信息、导出结果和单 case 重跑。</p>
          </div>
          <div className="panel-actions">
            <button type="button" className="ghost-btn" onClick={() => void refresh()}>刷新</button>
            <button type="button" className="ghost-btn" disabled={rescoring} onClick={() => void rescoreRun()}>{rescoring ? '重新评分中...' : '按当前规则重新评分'}</button>
            <button type="button" className="ghost-btn" onClick={() => void exportRunFile('json')}>导出 JSON</button>
            <button type="button" className="ghost-btn" onClick={() => void exportRunFile('csv')}>导出 CSV</button>
          </div>
        </div>
        <div className="summary-list">
          {run.summary.vendors.map(summary => (
            <div className="summary-item" key={summary.vendor}>
              <div className="summary-head">
                <strong>{getVendorLabel(summary.vendor)}</strong>
                <span>{summary.completed}/{run.summary.totalCases}</span>
              </div>
              <div className="summary-grid">
                <span>Pass {summary.passRate === null ? '-' : formatPercent(summary.passRate)}</span>
                <span>CER {summary.avgCer === null ? '-' : summary.avgCer.toFixed(3)}</span>
                <span>WER {summary.avgWer === null ? '-' : summary.avgWer.toFixed(3)}</span>
                <span>Latency {summary.avgFinalLatencyMs === null ? '-' : formatLatency(summary.avgFinalLatencyMs)}</span>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="layout-grid">
        <div className="eval-card panel">
        <div className="panel-head">
          <div>
            <h2>结果矩阵</h2>
            <p>点击行查看详情，行内可单 case 重跑。</p>
          </div>
          <div className="panel-actions">
            <select value={resultFilter} onChange={(e) => setResultFilter(e.target.value as typeof resultFilter)}>
              <option value="all">全部 case</option>
              <option value="failed">仅失败/超时</option>
              <option value="unfinished">仅未完成</option>
              <option value="passed">仅三家全通过</option>
            </select>
            <select value={sortKey} onChange={(e) => setSortKey(e.target.value as typeof sortKey)}>
              <option value="default">默认顺序</option>
              <option value="status">按状态</option>
              <option value="name">按名称</option>
              <option value="cer">按 CER</option>
              <option value="latency">按时延</option>
            </select>
          </div>
        </div>
          <div className="result-table-wrap">
            <table className="result-table">
              <thead>
              <tr>
                <th>Case</th>
                <th>音频</th>
                <th>状态</th>
                <th>最佳</th>
                {run.selectedVendors.map(vendor => <th key={vendor}>{getVendorLabel(vendor)}</th>)}
                <th>操作</th>
              </tr>
            </thead>
              <tbody>
                {visibleCases.map(item => {
                  const bestResult = bestCaseResultMap.get(item.id) ?? null
                  const playable = Boolean(item.audioDataUrl || item.backendId)
                  return (
                    <tr key={item.id} className={selectedCaseId === item.id ? 'selected' : ''} onClick={() => setSelectedCaseId(item.id)}>
                      <td>
                        <div className="case-name">{item.name}</div>
                        <div className="case-note">{item.caseType}</div>
                      </td>
                      <td className="matrix-audio-cell" onClick={(event) => event.stopPropagation()}>
                        <button
                          type="button"
                          className="matrix-audio-btn"
                          disabled={!playable}
                          title={playable ? '播放 case 音频' : '该历史 case 没有可重放的音频'}
                          onClick={() => {
                            setSelectedCaseId(item.id)
                            void openPreview(item)
                          }}
                        >
                          ▶ 播放
                        </button>
                      </td>
                      <td>
                        <span className="badge">
                          {run.selectedVendors[0] ? CASE_PHASE_LABELS[item.vendors[run.selectedVendors[0]]?.phase ?? 'queued'] : '—'}
                        </span>
                      </td>
                      <td>
                        {bestResult ? (
                          <div className="result-summary">
                            <span className="pill">{getVendorLabel(bestResult.vendor)}</span>
                            <span className="pill subtle">CER {bestResult.cer === null ? '—' : bestResult.cer.toFixed(3)}</span>
                          </div>
                        ) : (
                          '—'
                        )}
                      </td>
                      {run.selectedVendors.map(vendor => {
                        const result = item.vendors[vendor]
                        return (
                          <td key={vendor}>
                            <button
                              type="button"
                              className={`vendor-cell ${bestResult?.vendor === vendor ? 'best' : ''} ${result.pass ? 'pass' : result.status === 'failed' || result.status === 'timeout' ? 'fail' : ''}`}
                              onClick={(e) => {
                                e.stopPropagation()
                                setSelectedCaseId(item.id)
                                setSelectedVendor(vendor)
                              }}
                            >
                              <div className="vendor-cell-head">
                                <span>{getVendorLabel(vendor)}</span>
                                <span className="badge">{result.status}</span>
                              </div>
                              <div className="vendor-transcript">{result.transcript || result.errorMsg || '—'}</div>
                              <div className="vendor-metrics">
                                <span>CER {result.cer === null ? '—' : result.cer.toFixed(3)}</span>
                                <span>{formatLatency(result.finalLatencyMs)}</span>
                              </div>
                            </button>
                          </td>
                        )
                      })}
                      <td onClick={(e) => e.stopPropagation()}>
                        <button type="button" className="ghost-btn" disabled={busyCaseId === item.id} onClick={() => void rerunCase(item.id)}>
                          {busyCaseId === item.id ? '重跑中...' : '重跑 case'}
                        </button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>

        <div className="side-stack">
          <div className="eval-card panel">
            <div className="panel-head compact">
              <div>
                <h2>明细</h2>
                <p>文本差异、音频和原始指标。</p>
              </div>
            </div>

            {selectedCase && currentResult ? (
              <div className="inspector">
                <div className="run-actions">
                  <button type="button" className="ghost-btn" onClick={() => void openPreview(selectedCase)}>播放音频</button>
                  <button type="button" className="ghost-btn" onClick={() => setSelectedVendor(run.selectedVendors[0] ?? null)}>默认厂商</button>
                </div>
                <div className="audio-upload">
                  <div className="audio-title">采用的参考文本</div>
                  <div className="detail-text">{currentResult.referenceVariantUsed || selectedCase.referenceText || '未填写'}</div>
                </div>
                <div className="audio-upload">
                  <div className="audio-title">识别结果</div>
                  <div className="detail-text">{currentResult.transcript || '空结果'}</div>
                </div>
                <div className="audio-upload">
                  <div className="audio-title">归一化字符差异</div>
                  <div className="diff-row">
                    {diffSegments.map((segment, idx) => (
                      <span key={idx} className={`diff-segment diff-${segment.type}`}>{segment.text}</span>
                    ))}
                  </div>
                </div>
                <div className="audio-upload">
                  <div className="audio-title">指标</div>
                  <div className="detail-metrics">
                    <span>阶段 {CASE_PHASE_LABELS[currentResult.phase]}</span>
                    <span>CER {currentResult.cer === null ? '—' : currentResult.cer.toFixed(3)}</span>
                    <span>WER {currentResult.wer === null ? '—' : currentResult.wer.toFixed(3)}</span>
                    <span>首包 {formatLatency(currentResult.firstLatencyMs)}</span>
                    <span>总时延 {formatLatency(currentResult.finalLatencyMs)}</span>
                    <span>实体 {currentResult.entityMatchedCount === null ? '—' : `${currentResult.entityMatchedCount}/${currentResult.entityTotalCount ?? 0}`}</span>
                    <span>实体准确率 {currentResult.entityAccuracy === null ? '—' : formatPercent(currentResult.entityAccuracy)}</span>
                    <span>结果 {currentResult.pass ? '通过' : '未通过'}</span>
                    {currentResult.passReason ? <span>原因 {currentResult.passReason}</span> : null}
                    {currentResult.normalizerVersion ? <span>归一化 {currentResult.normalizerVersion}</span> : null}
                  </div>
                </div>
                <div className="audio-upload">
                  <div className="audio-title">编辑统计</div>
                  <div className="detail-metrics detail-metrics-stacked">
                    <span>字符级 {formatEditTriple(currentResult.characterSubstitutions, currentResult.characterInsertions, currentResult.characterDeletions)}</span>
                    <span>词级 {formatEditTriple(currentResult.wordSubstitutions, currentResult.wordInsertions, currentResult.wordDeletions)}</span>
                  </div>
                </div>
                <div className="audio-upload">
                  <div className="audio-title">实体缺失</div>
                  <div className="detail-text">{currentResult.entityMissedTerms.length > 0 ? currentResult.entityMissedTerms.join('、') : '无'}</div>
                </div>
                {previewUrl ? <audio controls src={previewUrl} style={{ width: '100%' }} /> : null}
              </div>
            ) : (
              <div className="empty-state">点击结果矩阵中的任意一项查看详情。</div>
            )}
          </div>

          <div className="eval-card panel">
            <div className="panel-head compact">
              <div>
                <h2>历史对比</h2>
                <p>查看最近两次 run 的平均指标变化。</p>
              </div>
            </div>
            {historyComparison ? (
              <div className="summary-list">
                <div className="summary-item">
                  <div className="summary-head">
                    <strong>{historyComparison.latest.name}</strong>
                    <span>最新</span>
                  </div>
                  <div className="summary-grid">
                    <span>Pass {formatPercent(historyComparison.latestSummary.passRate)}</span>
                    <span>CER {historyComparison.latestSummary.avgCer === null ? '-' : historyComparison.latestSummary.avgCer.toFixed(3)}</span>
                    <span>WER {historyComparison.latestSummary.avgWer === null ? '-' : historyComparison.latestSummary.avgWer.toFixed(3)}</span>
                    <span>Final {historyComparison.latestSummary.avgFinalLatencyMs === null ? '-' : formatLatency(historyComparison.latestSummary.avgFinalLatencyMs)}</span>
                  </div>
                </div>
                <div className="summary-item">
                  <div className="summary-head">
                    <strong>{historyComparison.previous.name}</strong>
                    <span>上一轮</span>
                  </div>
                  <div className="summary-grid">
                    <span>Pass {formatPercent(historyComparison.previousSummary.passRate)}</span>
                    <span>CER {historyComparison.previousSummary.avgCer === null ? '-' : historyComparison.previousSummary.avgCer.toFixed(3)}</span>
                    <span>WER {historyComparison.previousSummary.avgWer === null ? '-' : historyComparison.previousSummary.avgWer.toFixed(3)}</span>
                    <span>Final {historyComparison.previousSummary.avgFinalLatencyMs === null ? '-' : formatLatency(historyComparison.previousSummary.avgFinalLatencyMs)}</span>
                  </div>
                </div>
              </div>
            ) : (
              <div className="empty-state">至少需要两次 run 才能比较。</div>
            )}
          </div>

          <div className="eval-card panel">
            <div className="panel-head compact">
              <div>
                <h2>运行日志</h2>
                <p>存储在 run.json 内。</p>
              </div>
            </div>
            <div className="log-list">
              {run.logs.slice().reverse().map((item, idx) => (
                <div className="log-line" key={`${item.time}_${idx}`}>
                  <span>{item.time}</span>
                  <span>{item.text}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>
    </div>
  )
}
