import { useCallback, useEffect, useState } from 'react'
import { listEvalRuns, resumeEvalRun, stopEvalRun, type EvalRunListItem } from '../../api/evalRuns'
import { formatLatency, formatPercent, getVendorLabel } from '../../lib/asrEval'

interface EvalRunHistoryContentProps {
  onOpenRun: (runId: string) => void
}

function average(values: Array<number | null | undefined>) {
  const numbers = values.filter((value): value is number => typeof value === 'number' && Number.isFinite(value))
  if (numbers.length === 0) return null
  return numbers.reduce((sum, value) => sum + value, 0) / numbers.length
}

export function EvalRunHistoryContent({ onOpenRun }: EvalRunHistoryContentProps) {
  const [runs, setRuns] = useState<EvalRunListItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [operatingRunId, setOperatingRunId] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      setRuns(await listEvalRuns())
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载 run 历史失败')
    } finally {
      setLoading(false)
    }
  }, [])

  const runCommand = useCallback(async (runId: string, command: 'resume' | 'stop') => {
    setOperatingRunId(runId)
    setError('')
    try {
      const updated = command === 'resume' ? await resumeEvalRun(runId) : await stopEvalRun(runId)
      await refresh()
      onOpenRun(updated.runId)
    } catch (err) {
      setError(err instanceof Error ? err.message : '任务操作失败')
    } finally {
      setOperatingRunId(null)
    }
  }, [onOpenRun, refresh])

  useEffect(() => {
    let disposed = false
    void listEvalRuns()
      .then((next) => {
        if (!disposed) setRuns(next)
      })
      .catch((err) => {
        if (!disposed) setError(err instanceof Error ? err.message : '加载 run 历史失败')
      })
      .finally(() => {
        if (!disposed) setLoading(false)
      })
    return () => {
      disposed = true
    }
  }, [])

  return (
    <div className="eval-modal-content">
      <div className="eval-modal-toolbar">
        <div>
          <div className="eyebrow">Run History</div>
          <h3>历史任务</h3>
          <p>选择一次任务查看厂商排名、结果矩阵和单 case 明细。</p>
        </div>
        <button type="button" className="ghost-btn" onClick={() => void refresh()} disabled={loading}>
          {loading ? '刷新中...' : '刷新'}
        </button>
      </div>

      {error ? <div className="notice error">{error}</div> : null}

      <div className="result-table-wrap">
        <table className="result-table">
          <thead>
            <tr>
              <th>任务</th>
              <th>状态</th>
              <th>Case</th>
              <th>厂商完成</th>
              <th>最佳厂商</th>
              <th>通过率</th>
              <th>CER</th>
              <th>最终时延</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {runs.map((run) => {
              const summaries = run.summary.vendors
              const best = [...summaries].sort((a, b) => {
                const passDelta = (b.passRate ?? -1) - (a.passRate ?? -1)
                if (passDelta !== 0) return passDelta
                return (a.avgCer ?? Number.POSITIVE_INFINITY) - (b.avgCer ?? Number.POSITIVE_INFINITY)
              })[0]
              const passRate = average(summaries.map(summary => summary.passRate))
              const avgCer = average(summaries.map(summary => summary.avgCer))
              const finalLatency = average(summaries.map(summary => summary.avgFinalLatencyMs))
              const retryable = (run.summary.failureVendors + run.summary.timeoutVendors) > 0
              const canStart = run.status === 'failed' || run.status === 'stopped' || (run.status === 'completed' && retryable)
              const canStop = run.status === 'running' || run.status === 'pausing' || run.status === 'paused'
              const operating = operatingRunId === run.runId
              return (
                <tr key={run.runId}>
                  <td>
                    <div className="case-name">{run.name}</div>
                    <div className="case-note">{run.updatedAt}</div>
                  </td>
                  <td><span className="badge">{run.status}</span></td>
                  <td>{run.summary.completedCases}/{run.summary.totalCases}</td>
                  <td>{run.summary.doneVendors}/{run.summary.totalVendors}</td>
                  <td>{best ? getVendorLabel(best.vendor) : '-'}</td>
                  <td>{formatPercent(passRate)}</td>
                  <td>{avgCer === null ? '-' : avgCer.toFixed(3)}</td>
                  <td>{formatLatency(finalLatency)}</td>
                  <td>
                    <div className="run-actions">
                      <button type="button" className="ghost-btn" disabled={!canStart || operating} onClick={() => void runCommand(run.runId, 'resume')}>开始</button>
                      <button type="button" className="ghost-btn danger" disabled={!canStop || operating} onClick={() => void runCommand(run.runId, 'stop')}>停止</button>
                      <button type="button" className="ghost-btn" disabled={!retryable || canStop || operating} onClick={() => void runCommand(run.runId, 'resume')}>重跑失败 case</button>
                      <button type="button" className="ghost-btn" disabled={operating} onClick={() => onOpenRun(run.runId)}>查看详情</button>
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {runs.length === 0 && !loading ? <div className="empty-state">还没有保存的评估任务。</div> : null}
    </div>
  )
}
