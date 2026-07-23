import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { listCases, updateCase, updateCaseAudio, deleteCase, getCaseAudioUrl, saveCase, type CaseMetaUpdate, type TestCase } from '../api/benchCases'
import {
  CASE_TYPE_LIST,
  PASS_RULE_LIST,
  backendCaseToEvalCase,
  createBlankCase,
  getCaseTypeLabel,
  getPassRuleLabel,
  type EvalCaseConfig,
} from '../lib/asrEval'
import { getAudioDurationSeconds, mimeFromName } from '../lib/audioFrames'
import { navigateWithAppBase } from '../lib/appRoutes'
import { PressHoldRecorder } from '../components/eval/PressHoldRecorder'
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

const CASES_PER_PAGE = 10

function createHeader(title: string, subtitle: string) {
  return (
    <header className="eval-topbar">
        <div>
          <div className="eyebrow">ASR Evaluation Dashboard</div>
          <h1>{title}</h1>
          <p>{subtitle}</p>
        </div>
        <div className="route-note">
        <button type="button" className="ghost-btn" onClick={() => navigateWithAppBase('/eval')}>回到看板</button>
          <span className="pill">/eval/cases</span>
        </div>
      </header>
  )
}

function convertCase(item: TestCase): EvalCaseConfig {
  return backendCaseToEvalCase(item)
}

interface EvalCasesPageProps {
  embedded?: boolean
  onChanged?: () => void
}

export function EvalCasesPage({ embedded = false, onChanged }: EvalCasesPageProps = {}) {
  const [cases, setCases] = useState<EvalCaseConfig[]>([])
  const [selectedCaseId, setSelectedCaseId] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [savingCaseId, setSavingCaseId] = useState<string | null>(null)
  const [currentPage, setCurrentPage] = useState(1)
  const [error, setError] = useState('')
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [playingCaseId, setPlayingCaseId] = useState<string | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewPlaying, setPreviewPlaying] = useState(false)
  const [caseLengthFilter, setCaseLengthFilter] = useState<CaseLengthFilter>('all')
  const [noiseScenarioFilter, setNoiseScenarioFilter] = useState<NoiseScenarioFilter>('all')
  const previewAudioRef = useRef<HTMLAudioElement | null>(null)
  const previewObjectUrlRef = useRef<string | null>(null)
  const previewStartingRef = useRef(false)

  const activeCase = useMemo(() => cases.find(item => item.id === selectedCaseId) ?? null, [cases, selectedCaseId])
  const filteredCases = useMemo(
    () => cases.filter(item => matchesCaseFilters(item, caseLengthFilter, noiseScenarioFilter)),
    [caseLengthFilter, cases, noiseScenarioFilter],
  )
  const totalPages = Math.max(1, Math.ceil(filteredCases.length / CASES_PER_PAGE))
  const visibleCases = useMemo(() => {
    const start = (currentPage - 1) * CASES_PER_PAGE
    return filteredCases.slice(start, start + CASES_PER_PAGE)
  }, [currentPage, filteredCases])
  const pageStart = filteredCases.length === 0 ? 0 : (currentPage - 1) * CASES_PER_PAGE + 1
  const pageEnd = Math.min(currentPage * CASES_PER_PAGE, filteredCases.length)

  const loadCases = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const list = await listCases()
      const next = list.map(convertCase)
      setCases(next)
      setCurrentPage(1)
      setSelectedCaseId(prev => prev ?? next[0]?.id ?? null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载 case 失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadCases()
  }, [loadCases])

  useEffect(() => {
    setCurrentPage(prev => Math.min(prev, totalPages))
  }, [totalPages])

  const upsertCase = useCallback((caseId: string, updater: (item: EvalCaseConfig) => EvalCaseConfig) => {
    setCases(prev => prev.map(item => (item.id === caseId ? updater(item) : item)))
  }, [])

  const addLocalCase = useCallback(() => {
    const next = createBlankCase()
    setCases(prev => [next, ...prev])
    setCurrentPage(1)
    setSelectedCaseId(next.id)
  }, [])

  const handleAudioFileUpload = useCallback(async (caseId: string, file: File | null) => {
    if (!file) return
    setError('')
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
      setCases(prev => prev.map(item => (item.id === caseId ? { ...item, durationSeconds } : item)))
    } catch {
      setError(`无法读取 ${file.name} 时长`)
    }
  }, [])

  const persistCase = useCallback(async () => {
    if (!activeCase) return
    setSavingCaseId(activeCase.id)
    const payload: CaseMetaUpdate = {
      name: activeCase.name,
      note: activeCase.note,
      caseType: activeCase.caseType,
      referenceText: activeCase.referenceText,
      cantoneseTraditionalReferenceText: activeCase.cantoneseTraditionalReferenceText,
      criticalTermsText: activeCase.criticalTermsText,
      acceptableTextsText: activeCase.acceptableTextsText,
      passRuleType: activeCase.passRuleType,
      passThreshold: activeCase.passThreshold,
      enabled: activeCase.enabled,
      durationSeconds: activeCase.durationSeconds,
    }

    try {
      if (activeCase.source === 'backend' || activeCase.backendId) {
        const caseId = activeCase.backendId ?? activeCase.id
        let saved = await updateCase(caseId, payload)
        if (activeCase.audioFile) {
          saved = await updateCaseAudio(
            caseId,
            activeCase.audioFile,
            activeCase.audioFileName ?? 'audio',
            activeCase.durationSeconds ?? 0,
          )
        }
        setCases(prev => prev.map(item => (item.id === activeCase.id ? backendCaseToEvalCase(saved) : item)))
        onChanged?.()
        return
      }

      const saved = await saveCase(
        activeCase.audioFile ?? null,
        activeCase.audioFileName ?? 'audio',
        activeCase.name,
        activeCase.note,
        activeCase.durationSeconds ?? 0,
        payload,
      )
      setCases(prev => prev.map(item => (item.id === activeCase.id ? backendCaseToEvalCase(saved) : item)))
      setSelectedCaseId(saved.id)
      onChanged?.()
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败')
    } finally {
      setSavingCaseId(null)
    }
  }, [activeCase, onChanged])

  const handleDelete = useCallback(async (id: string) => {
    if (!confirm('确认删除这个 case？')) return
    try {
      await deleteCase(id)
      setCases(prev => prev.filter(item => item.id !== id))
      setSelectedCaseId(prev => {
        if (prev !== id) return prev
        return cases.find(item => item.id !== id)?.id ?? null
      })
      onChanged?.()
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败')
    }
  }, [cases, onChanged])

  const clearPreview = useCallback(() => {
    previewStartingRef.current = false

    const audio = previewAudioRef.current
    if (audio) {
      audio.pause()
      audio.removeAttribute('src')
      audio.load()
    }

    if (previewObjectUrlRef.current) {
      URL.revokeObjectURL(previewObjectUrlRef.current)
      previewObjectUrlRef.current = null
    }
    setPreviewUrl(null)
    setPlayingCaseId(null)
    setPreviewPlaying(false)
    setPreviewLoading(false)
  }, [])

  const playPreview = useCallback(async (caseItem: EvalCaseConfig) => {
    if (!caseItem.hasAudio) return

    const audio = previewAudioRef.current
    if (playingCaseId === caseItem.id && audio?.src) {
      if (audio.paused) {
        try {
          await audio.play()
        } catch (err) {
          setError(err instanceof Error ? err.message : '预览播放失败')
        }
      } else {
        audio.pause()
      }
      return
    }

    if (previewStartingRef.current) return
    previewStartingRef.current = true
    clearPreview()
    previewStartingRef.current = true
    setPreviewLoading(true)
    setError('')

    try {
      let url: string
      if (caseItem.audioFile) {
        url = URL.createObjectURL(caseItem.audioFile)
        previewObjectUrlRef.current = url
      } else {
        url = getCaseAudioUrl(caseItem.backendId ?? caseItem.id)
      }

      setPreviewUrl(url)
      setPlayingCaseId(caseItem.id)
      const visibleAudio = previewAudioRef.current
      if (!visibleAudio) throw new Error('播放器初始化失败')
      visibleAudio.src = url
      visibleAudio.load()
      try {
        await visibleAudio.play()
      } catch (err) {
        // Keep the visible player available if an embedded browser rejects media playback.
        if (err instanceof DOMException && err.name === 'NotAllowedError') {
          setError('音频已加载，请点击下方播放控件或再次点击“继续预览”开始播放')
          return
        }
        if (err instanceof DOMException && err.name === 'AbortError') {
          return
        }
        throw err
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '预览失败')
      clearPreview()
    } finally {
      previewStartingRef.current = false
      setPreviewLoading(false)
    }
  }, [clearPreview, playingCaseId])

  useEffect(() => () => clearPreview(), [clearPreview])

  return (
    <div className={embedded ? 'eval-embedded-content' : 'eval-shell'}>
      {!embedded ? createHeader('Case 管理', '管理评估用例、标准文本、关键实体和通过规则。') : null}

      <section className="stats-grid">
        <div className="eval-card metric-card">
          <div className="metric-label">Case 数量</div>
          <div className="metric-value">{cases.length}</div>
          <div className="metric-hint">后端样本 + 本地临时样本</div>
        </div>
        <div className="eval-card metric-card">
          <div className="metric-label">当前选择</div>
          <div className="metric-value">{activeCase ? activeCase.name : '-'}</div>
          <div className="metric-hint">{activeCase ? getCaseTypeLabel(activeCase.caseType) : '未选中'}</div>
        </div>
        <div className="eval-card metric-card">
          <div className="metric-label">保存状态</div>
          <div className="metric-value">{savingCaseId ? '保存中' : '就绪'}</div>
          <div className="metric-hint">修改后点保存会写回 `meta.json`</div>
        </div>
        <div className="eval-card metric-card">
          <div className="metric-label">预览</div>
          <div className="metric-value">{playingCaseId ? '播放中' : '待机'}</div>
          <div className="metric-hint">支持音频回放</div>
        </div>
      </section>

      {error ? <div className="notice error">{error}</div> : null}

      <section className="case-manager-grid">
        <div className="eval-card panel case-manager-list">
          <div className="panel-head">
            <div>
              <h2>Case 列表</h2>
              <p>选择后可在右侧编辑和保存。</p>
            </div>
            <div className="panel-actions">
              <button type="button" className="ghost-btn" onClick={() => void loadCases()} disabled={loading}>
                {loading ? '刷新中...' : '刷新'}
              </button>
              <button type="button" className="ghost-btn" onClick={addLocalCase}>新增临时 case</button>
            </div>
          </div>

          <div className="case-filter-bar" aria-label="Case 筛选">
            <label>
              <span>对话长短</span>
              <select value={caseLengthFilter} onChange={(event) => { setCaseLengthFilter(event.target.value as CaseLengthFilter); setCurrentPage(1) }}>
                {CASE_LENGTH_OPTIONS.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
              </select>
            </label>
            <label>
              <span>噪音场景</span>
              <select value={noiseScenarioFilter} onChange={(event) => { setNoiseScenarioFilter(event.target.value as NoiseScenarioFilter); setCurrentPage(1) }}>
                {NOISE_SCENARIO_OPTIONS.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
              </select>
            </label>
            <span className="case-filter-count">匹配 {filteredCases.length} / {cases.length} 条</span>
          </div>

          <div className="case-table-wrap">
            <table className="case-table">
              <thead>
                <tr>
                  <th>名称</th>
                  <th>类型</th>
                  <th>对话长短</th>
                  <th>噪音场景</th>
                  <th>参考文本</th>
                  <th>关键实体</th>
                  <th>规则</th>
                  <th>音频</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {visibleCases.map(item => (
                  <tr key={item.id} className={selectedCaseId === item.id ? 'selected' : ''} onClick={() => setSelectedCaseId(item.id)}>
                    <td>
                      <div className="case-name">{item.name}</div>
                      <div className="case-note">{item.source}</div>
                    </td>
                    <td>{getCaseTypeLabel(item.caseType)}</td>
                    <td>{getCaseLengthLabel(item)}</td>
                    <td>{getNoiseScenarioLabel(item)}</td>
                    <td className="table-text">{item.referenceText || '待填写'}</td>
                    <td className="table-text">{item.criticalTermsText || '—'}</td>
                    <td>{getPassRuleLabel(item.passRuleType)} / {item.passThreshold.toFixed(2)}</td>
                    <td>{item.hasAudio ? (item.audioFileName || '已上传') : <span className="badge badge-danger">待上传</span>}</td>
                    <td onClick={(e) => e.stopPropagation()}>
                      <button
                        type="button"
                        className="ghost-btn"
                        disabled={!item.hasAudio || previewLoading}
                        onClick={() => {
                          setSelectedCaseId(item.id)
                          void playPreview(item)
                        }}
                      >
                        {playingCaseId === item.id && previewPlaying ? '暂停' : playingCaseId === item.id ? '继续' : '播放'}
                      </button>
                      <button type="button" className="ghost-btn danger" onClick={() => void handleDelete(item.id)}>删除</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="case-pagination" aria-label="Case 列表分页">
            <div className="case-pagination-status">
              {filteredCases.length === 0 ? '当前筛选条件下暂无 Case' : `显示第 ${pageStart}-${pageEnd} 条，匹配 ${filteredCases.length} / ${cases.length} 条`}
            </div>
            <div className="case-pagination-actions">
              <button type="button" className="ghost-btn" onClick={() => setCurrentPage(1)} disabled={currentPage === 1}>首页</button>
              <button type="button" className="ghost-btn" onClick={() => setCurrentPage(prev => Math.max(1, prev - 1))} disabled={currentPage === 1}>上一页</button>
              <label className="case-page-select">
                <span>第</span>
                <select aria-label="选择 Case 页码" value={currentPage} onChange={(event) => setCurrentPage(Number(event.target.value))} disabled={filteredCases.length === 0}>
                  {Array.from({ length: totalPages }, (_, index) => index + 1).map(page => <option key={page} value={page}>{page}</option>)}
                </select>
                <span>/ {totalPages} 页</span>
              </label>
              <button type="button" className="ghost-btn" onClick={() => setCurrentPage(prev => Math.min(totalPages, prev + 1))} disabled={currentPage === totalPages}>下一页</button>
              <button type="button" className="ghost-btn" onClick={() => setCurrentPage(totalPages)} disabled={currentPage === totalPages}>末页</button>
            </div>
          </div>
        </div>

        <div className="eval-card panel case-manager-detail">
            <div className="panel-head compact">
              <div>
                <h2>Case 详情</h2>
                <p>保存后会直接写入本地 `meta.json`。</p>
              </div>
              <div className="panel-actions">
                <button
                  type="button"
                  className="primary-btn"
                  onClick={() => void persistCase()}
                  disabled={!activeCase || savingCaseId === activeCase?.id}
                >
                  {savingCaseId === activeCase?.id ? '保存中...' : activeCase?.hasAudio ? '保存' : '暂存'}
                </button>
              </div>
            </div>

            {activeCase ? (
              <div className="inspector">
                <label>
                  <span>名称</span>
                  <input value={activeCase.name} onChange={(e) => upsertCase(activeCase.id, item => ({ ...item, name: e.target.value }))} />
                </label>
                <label>
                  <span>备注</span>
                  <input value={activeCase.note} onChange={(e) => upsertCase(activeCase.id, item => ({ ...item, note: e.target.value }))} />
                </label>
                <label>
                  <span>类型</span>
                  <select
                    value={activeCase.caseType}
                    onChange={(e) => upsertCase(activeCase.id, item => ({
                      ...item,
                      caseType: e.target.value as EvalCaseConfig['caseType'],
                      passRuleType: e.target.value === 'mixed'
                        ? 'mixed'
                        : (e.target.value === 'number' || e.target.value === 'money' || e.target.value === 'name')
                          ? 'entity'
                          : 'cer',
                    }))}
                  >
                    {CASE_TYPE_LIST.map(item => <option key={item.key} value={item.key}>{item.label}</option>)}
                  </select>
                </label>
                <label>
                  <span>参考文本</span>
                  <textarea rows={5} value={activeCase.referenceText} onChange={(e) => upsertCase(activeCase.id, item => ({ ...item, referenceText: e.target.value }))} />
                </label>
                <label>
                  <span>粤语繁体参考文本</span>
                  <textarea rows={5} value={activeCase.cantoneseTraditionalReferenceText} onChange={(e) => upsertCase(activeCase.id, item => ({ ...item, cantoneseTraditionalReferenceText: e.target.value }))} placeholder="用于与主参考文本并行评分，自动采用较优结果" />
                </label>
                <label>
                  <span>关键实体</span>
                  <input value={activeCase.criticalTermsText} onChange={(e) => upsertCase(activeCase.id, item => ({ ...item, criticalTermsText: e.target.value }))} />
                </label>
                <label>
                  <span>可接受文本</span>
                  <textarea rows={3} value={activeCase.acceptableTextsText} onChange={(e) => upsertCase(activeCase.id, item => ({ ...item, acceptableTextsText: e.target.value }))} placeholder="每行一个等价转写，例如：咁 / 噉的完整句子" />
                </label>
                <div className="inline-grid">
                  <label>
                    <span>通过规则</span>
                    <select value={activeCase.passRuleType} onChange={(e) => upsertCase(activeCase.id, item => ({ ...item, passRuleType: e.target.value as EvalCaseConfig['passRuleType'] }))}>
                      {PASS_RULE_LIST.map(item => <option key={item.key} value={item.key}>{item.label}</option>)}
                    </select>
                  </label>
                  <label>
                    <span>CER 阈值</span>
                    <input type="number" step="0.01" min="0" max="1" value={activeCase.passThreshold} onChange={(e) => upsertCase(activeCase.id, item => ({ ...item, passThreshold: Number(e.target.value) }))} />
                  </label>
                </div>
                <label>
                  <span>音频</span>
                  <input type="file" accept="audio/*" onChange={(e) => {
                    const file = e.target.files?.[0] ?? null
                    if (file) void handleAudioFileUpload(activeCase.id, file)
                    e.target.value = ''
                  }} />
                </label>
                <PressHoldRecorder
                  onRecorded={async (file) => {
                    await handleAudioFileUpload(activeCase.id, file)
                  }}
                  onError={(message) => setError(message)}
                />
                <div className="run-actions">
                  <button type="button" className="ghost-btn" onClick={() => void playPreview(activeCase)} disabled={!activeCase.hasAudio || previewLoading}>
                    {previewLoading ? '加载中...' : playingCaseId === activeCase.id && previewPlaying ? '暂停预览' : playingCaseId === activeCase.id ? '继续预览' : '预览音频'}
                  </button>
                  <button type="button" className="ghost-btn" onClick={() => setCases(prev => prev.map(item => item.id === activeCase.id ? { ...item, enabled: !item.enabled } : item))}>
                    {activeCase.enabled ? '停用' : '启用'}
                  </button>
                </div>
                <div className="audio-upload">
                  <div className="audio-title">当前音频</div>
                  <div className="audio-meta">{activeCase.audioFileName || '暂未上传，可先保存后续再补充'}</div>
                  <div className="hint">{activeCase.source === 'backend' ? '选择或录制新音频后点击保存，会补传或替换当前 Case 的音频。' : '音频不是必填项，可以先暂存 Case 信息。'}</div>
                </div>
                <audio
                  ref={previewAudioRef}
                  controls
                  style={{ width: '100%', display: previewUrl ? 'block' : 'none' }}
                  onPlay={() => setPreviewPlaying(true)}
                  onPause={() => setPreviewPlaying(false)}
                  onEnded={() => setPreviewPlaying(false)}
                  onError={() => setError('音频播放失败，请检查音频格式')}
                />
              </div>
            ) : (
              <div className="empty-state">先选择一个 case。</div>
            )}
        </div>
      </section>
    </div>
  )
}
